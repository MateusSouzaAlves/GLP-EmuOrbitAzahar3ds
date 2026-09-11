#!/usr/bin/env python3
"""Audit the pinned Azahar checkout, linked submodules, and license evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = PROJECT_ROOT / "config" / "nintendo3ds-source-scope.json"
SUBMODULE_PATTERN = re.compile(r"^(.)([0-9a-f]{40})\s+([^\s]+)(?:\s+.*)?$")


def run(*arguments: str, cwd: Path | None = None) -> str:
    return subprocess.run(
        list(arguments),
        cwd=cwd,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout


def parse_submodule_status(output: str) -> dict[str, str]:
    submodules: dict[str, str] = {}
    for line in output.splitlines():
        if not line:
            continue
        match = SUBMODULE_PATTERN.fullmatch(line)
        if not match:
            raise ValueError(f"unrecognized submodule status: {line}")
        marker, commit, relative_path = match.groups()
        if marker != " ":
            raise ValueError(
                f"submodule is not initialized at the pinned commit: {relative_path}"
            )
        if relative_path in submodules:
            raise ValueError(f"duplicate submodule path: {relative_path}")
        submodules[relative_path] = commit
    return submodules


def submodule_digest(submodules: dict[str, str]) -> str:
    normalized = "".join(
        f"{path} {submodules[path]}\n" for path in sorted(submodules)
    ).encode("utf-8")
    return hashlib.sha256(normalized).hexdigest()


def discover_build_references(
    commands: str, submodules: dict[str, str]
) -> set[str]:
    normalized = commands.replace("\\", "/")
    return {path for path in submodules if path in normalized}


def validate_license_evidence(
    source_root: Path, dependencies: list[dict]
) -> list[str]:
    errors: list[str] = []
    for dependency in dependencies:
        path = dependency.get("path", "")
        license_expression = dependency.get("licenseExpression", "")
        evidence = dependency.get("licenseEvidence", [])
        if not path or not license_expression or not evidence:
            errors.append(f"incomplete linked dependency entry: {path or '<missing>'}")
            continue
        dependency_root = (source_root / path).resolve()
        for relative_evidence in evidence:
            evidence_path = (dependency_root / relative_evidence).resolve()
            try:
                evidence_path.relative_to(dependency_root)
            except ValueError:
                errors.append(f"license evidence escapes dependency: {path}")
                continue
            if not evidence_path.is_file():
                errors.append(
                    f"missing license evidence: {path}/{relative_evidence}"
                )
    return errors


def audit(
    manifest: dict,
    source_root: Path,
    build_commands: str | None = None,
    candidate: bool = False,
) -> list[str]:
    errors: list[str] = []
    upstream = next(
        (
            item
            for item in manifest.get("distributedUpstreams", [])
            if item.get("id") == "azahar"
        ),
        None,
    )
    upstream_audit = manifest.get("upstreamAudit", {})
    dependencies = upstream_audit.get("linkedDependencies", [])
    if upstream is None:
        return ["Azahar upstream is absent from the manifest"]

    expected_revision = upstream
    expected_digest = upstream_audit.get("recursiveSubmoduleStatusSha256")
    if candidate:
        expected_revision = (
            manifest.get("n3ds09Decision", {}).get("updateCompatibilityCandidate", {})
        )
        expected_digest = expected_revision.get("recursiveSubmoduleStatusSha256")
        if not expected_revision:
            return ["Azahar update compatibility candidate is absent from the manifest"]

    actual_head = run("git", "rev-parse", "HEAD", cwd=source_root).strip()
    if actual_head != expected_revision.get("commit"):
        errors.append(f"Azahar HEAD differs: {actual_head}")

    submodules = parse_submodule_status(
        run("git", "submodule", "status", "--recursive", cwd=source_root)
    )
    expected_count = expected_revision.get("recursiveSubmoduleCount")
    if len(submodules) != expected_count:
        errors.append(
            f"recursive submodule count differs: {len(submodules)} != {expected_count}"
        )
    actual_digest = submodule_digest(submodules)
    if actual_digest != expected_digest:
        errors.append(f"recursive submodule digest differs: {actual_digest}")

    expected_linked: set[str] = set()
    for dependency in dependencies:
        path = dependency.get("path", "")
        if path in expected_linked:
            errors.append(f"duplicate linked dependency: {path}")
        expected_linked.add(path)
        if submodules.get(path) != dependency.get("commit"):
            errors.append(f"linked dependency commit differs: {path}")
    if len(expected_linked) != upstream_audit.get("linkedDependencyCount"):
        errors.append("linked dependency count differs from manifest")
    errors.extend(validate_license_evidence(source_root, dependencies))

    if build_commands is not None:
        actual_linked = discover_build_references(build_commands, submodules)
        if actual_linked != expected_linked:
            missing = sorted(actual_linked - expected_linked)
            stale = sorted(expected_linked - actual_linked)
            if missing:
                errors.append(f"build references uninventoried submodules: {missing}")
            if stale:
                errors.append(f"inventoried submodules absent from build: {stale}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--build-root", type=Path)
    parser.add_argument("--ninja", default="ninja")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument(
        "--candidate",
        action="store_true",
        help="audit the separately pinned N3DS-09 update compatibility candidate",
    )
    args = parser.parse_args(argv)

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    build_commands = None
    if args.build_root:
        build_commands = run(
            args.ninja,
            "-C",
            str(args.build_root.resolve()),
            "-t",
            "commands",
            "azahar_libretro",
        )
    try:
        errors = audit(
            manifest,
            args.source_root.resolve(),
            build_commands,
            candidate=args.candidate,
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Nintendo 3DS upstream audit failed: {error}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    audit_spec = manifest["upstreamAudit"]
    revision_kind = "candidate" if args.candidate else "baseline"
    print(
        f"Nintendo 3DS {revision_kind} upstream audit approved: "
        f"{audit_spec['linkedDependencyCount']} linked dependencies with license evidence."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
