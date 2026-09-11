#!/usr/bin/env python3
"""Generate deterministic Nintendo 3DS CycloneDX and notice artifacts."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import uuid


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = PROJECT_ROOT / "config" / "nintendo3ds-source-scope.json"
DEFAULT_SBOM = (
    PROJECT_ROOT / "nintendo3dscore" / "compliance" / "nintendo3ds-sbom.cdx.json"
)
DEFAULT_NOTICES = (
    PROJECT_ROOT / "nintendo3dscore" / "compliance" / "THIRD_PARTY_NOTICES.txt"
)
SERIAL_NAMESPACE = uuid.UUID("a848a342-d166-5ea8-b63c-b0351b23c957")


def _repository_reference(repository: str) -> list[dict]:
    return [{"type": "vcs", "url": repository}]


def _component(
    *,
    name: str,
    version: str,
    repository: str,
    license_expression: str,
    bom_ref: str,
) -> dict:
    return {
        "type": "library",
        "bom-ref": bom_ref,
        "name": name,
        "version": version,
        "licenses": [{"expression": license_expression}],
        "externalReferences": _repository_reference(repository),
        "properties": [{"name": "emuorbit:git-commit", "value": version}],
    }


def generate_sbom(manifest: dict) -> dict:
    upstream = next(
        item for item in manifest["distributedUpstreams"] if item["id"] == "azahar"
    )
    audit = manifest["upstreamAudit"]
    build = manifest["referenceBuild"]
    root_ref = f"pkg:generic/azahar-libretro@{upstream['commit']}"
    components = []
    dependency_refs = []
    for dependency in sorted(audit["linkedDependencies"], key=lambda item: item["path"]):
        dependency_ref = f"pkg:generic/{dependency['path']}@{dependency['commit']}"
        dependency_refs.append(dependency_ref)
        components.append(
            _component(
                name=dependency["path"],
                version=dependency["commit"],
                repository=dependency["repository"],
                license_expression=dependency["licenseExpression"],
                bom_ref=dependency_ref,
            )
        )

    timestamp = datetime.fromtimestamp(
        build["sourceDateEpoch"], tz=timezone.utc
    ).isoformat().replace("+00:00", "Z")
    serial_seed = f"{upstream['commit']}:{audit['recursiveSubmoduleStatusSha256']}"
    return {
        "$schema": "http://cyclonedx.org/schema/bom-1.6.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": f"urn:uuid:{uuid.uuid5(SERIAL_NAMESPACE, serial_seed)}",
        "version": 1,
        "metadata": {
            "timestamp": timestamp,
            "component": _component(
                name="azahar-libretro",
                version=upstream["commit"],
                repository=upstream["repository"],
                license_expression=audit["aggregateLicense"],
                bom_ref=root_ref,
            ),
            "properties": [
                {
                    "name": "emuorbit:recursive-submodule-status-sha256",
                    "value": audit["recursiveSubmoduleStatusSha256"],
                },
                {
                    "name": "emuorbit:build-artifact-sha256",
                    "value": build["strippedSha256"],
                },
            ],
        },
        "components": components,
        "dependencies": [{"ref": root_ref, "dependsOn": dependency_refs}],
    }


def generate_notices(manifest: dict) -> str:
    upstream = next(
        item for item in manifest["distributedUpstreams"] if item["id"] == "azahar"
    )
    audit = manifest["upstreamAudit"]
    lines = [
        "# Nintendo 3DS third-party notice index",
        "",
        "Generated from `config/nintendo3ds-source-scope.json`. This is the pinned",
        "notice and license-evidence index for the private implementation baseline; it",
        "does not authorize distribution. Before release, the complete corresponding",
        "source and required license texts/notices must be published and matched to the",
        "distributed artifact.",
        "",
        "## Combined component",
        "",
        f"- Azahar libretro tag `{upstream['tag']}` at `{upstream['commit']}`.",
        f"- Upstream declaration: `{upstream['license']}`.",
        f"- Combined linked-work license: `{audit['aggregateLicense']}`.",
        f"- Repository: {upstream['repository']}",
        "",
        "## Adapted frontend source",
        "",
        "| Upstream path | Commit | License | Incorporated destinations | Repository |",
        "| --- | --- | --- | --- | --- |",
    ]
    for reference in manifest["referenceImplementations"]:
        for source in reference.get("incorporatedSourcePaths", []):
            destinations = "<br>".join(
                f"`{destination}`" for destination in source["destinations"]
            )
            lines.append(
                f"| `{source['source']}` | `{reference['commit']}` | "
                f"`{source['license']}` | {destinations} | "
                f"{reference['repository']} |"
            )
    lines.extend(
        [
        "",
        "## Linked dependencies",
        "",
        "| Source path | Commit | License expression | Evidence | Repository |",
        "| --- | --- | --- | --- | --- |",
        ]
    )
    for dependency in sorted(audit["linkedDependencies"], key=lambda item: item["path"]):
        evidence = "<br>".join(f"`{item}`" for item in dependency["licenseEvidence"])
        lines.append(
            f"| `{dependency['path']}` | `{dependency['commit']}` | "
            f"`{dependency['licenseExpression']}` | {evidence} | "
            f"{dependency['repository']} |"
        )
    lines.extend(
        [
            "",
            "## Explicit exclusion",
            "",
            f"`{audit['excludedLegacyTls']['path']}` at "
            f"`{audit['excludedLegacyTls']['commit']}` is not linked. "
            f"{audit['excludedLegacyTls']['reason']}",
            "",
        ]
    )
    return "\n".join(lines)


def render_json(document: dict) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--sbom", type=Path, default=DEFAULT_SBOM)
    parser.add_argument("--notices", type=Path, default=DEFAULT_NOTICES)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    outputs = {
        args.sbom: render_json(generate_sbom(manifest)),
        args.notices: generate_notices(manifest),
    }
    if args.check:
        stale = [
            path
            for path, expected in outputs.items()
            if not path.is_file() or path.read_text(encoding="utf-8") != expected
        ]
        if stale:
            raise SystemExit(
                "Nintendo 3DS compliance artifacts are stale: "
                + ", ".join(str(path) for path in stale)
            )
    else:
        for path, content in outputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8", newline="\n")
    print("Nintendo 3DS compliance artifacts are deterministic and current.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
