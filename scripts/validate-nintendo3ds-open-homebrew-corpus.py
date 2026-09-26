#!/usr/bin/env python3
"""Validate the pinned, redistributable N3DS-13D homebrew corpus."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = PROJECT_ROOT / "config" / "nintendo3ds-open-homebrew-corpus.json"
EXPECTED_SLOTS = [f"OPEN_HOMEBREW_{number}" for number in range(2, 8)]
APPROVED_LICENSES = {"MIT", "GPL-3.0"}
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def _github_repository_path(url: str) -> str | None:
    parsed = urlparse(url)
    parts = [part for part in parsed.path.split("/") if part]
    if parsed.scheme != "https" or parsed.netloc != "github.com" or len(parts) != 2:
        return None
    return "/".join(parts)


def validate_manifest(manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if manifest.get("schemaVersion") != 1 or manifest.get("planItem") != "N3DS-13D":
        errors.append("homebrew corpus identity changed")
    if manifest.get("purpose") != "TEMPORARY_PHYSICAL_REGRESSION_ONLY":
        errors.append("homebrew corpus purpose changed")
    if manifest.get("redistributableOnly") is not True:
        errors.append("homebrew corpus must remain redistributable-only")
    if manifest.get("commercialContentAllowed") is not False:
        errors.append("commercial content must remain forbidden")
    if manifest.get("binariesCommitted") is not False:
        errors.append("homebrew binaries must not be committed")

    entries = manifest.get("entries")
    if not isinstance(entries, list) or len(entries) != len(EXPECTED_SLOTS):
        errors.append("homebrew corpus must contain exactly six expansion entries")
        return errors
    if [entry.get("slotId") for entry in entries if isinstance(entry, dict)] != EXPECTED_SLOTS:
        errors.append("homebrew corpus slots or order changed")

    repositories: set[str] = set()
    content_digests: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            errors.append("homebrew corpus entry must be an object")
            continue
        repository_url = entry.get("repositoryUrl")
        repository_path = (
            _github_repository_path(repository_url)
            if isinstance(repository_url, str)
            else None
        )
        if repository_path is None:
            errors.append("homebrew repository must be an official HTTPS GitHub URL")
            continue
        if repository_path in repositories:
            errors.append("homebrew corpus repositories must be distinct")
        repositories.add(repository_path)

        release_tag = entry.get("releaseTag")
        required_prefix = f"https://github.com/{repository_path}/releases/"
        asset_prefix = f"{required_prefix}download/{release_tag}/"
        if not isinstance(entry.get("releaseUrl"), str) or not entry["releaseUrl"].startswith(
            required_prefix
        ):
            errors.append("homebrew release URL does not belong to its repository")
        if not isinstance(entry.get("assetUrl"), str) or not entry["assetUrl"].startswith(
            asset_prefix
        ):
            errors.append("homebrew asset URL is not pinned to its official release")
        if not isinstance(entry.get("licenseUrl"), str) or not entry["licenseUrl"].startswith(
            f"https://github.com/{repository_path}/blob/"
        ):
            errors.append("homebrew license URL does not belong to its repository")
        if entry.get("licenseSpdx") not in APPROVED_LICENSES:
            errors.append("homebrew entry does not use an approved redistributable license")

        for size_key in ("assetBytes", "contentBytes"):
            if not isinstance(entry.get(size_key), int) or entry[size_key] <= 0:
                errors.append(f"homebrew entry has invalid {size_key}")
        for digest_key in ("assetSha256", "contentSha256"):
            digest = entry.get(digest_key)
            if not isinstance(digest, str) or not SHA256.fullmatch(digest):
                errors.append(f"homebrew entry has invalid {digest_key}")
        content_digest = entry.get("contentSha256")
        if isinstance(content_digest, str):
            if content_digest in content_digests:
                errors.append("homebrew contents must be binary-distinct")
            content_digests.add(content_digest)

        archive_path = entry.get("archiveContentPath")
        if archive_path is None:
            if entry.get("assetBytes") != entry.get("contentBytes") or entry.get(
                "assetSha256"
            ) != entry.get("contentSha256"):
                errors.append("direct 3DSX asset identity must equal content identity")
            if not str(entry.get("assetUrl", "")).lower().endswith(".3dsx"):
                errors.append("direct homebrew asset must be a 3DSX binary")
        elif (
            not isinstance(archive_path, str)
            or archive_path.startswith(("/", "\\"))
            or ".." in Path(archive_path).parts
            or not archive_path.lower().endswith(".3dsx")
            or not str(entry.get("assetUrl", "")).lower().endswith(".zip")
        ):
            errors.append("archived homebrew content path is unsafe or unsupported")
    return errors


def tracked_binary_errors(project_root: Path = PROJECT_ROOT) -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=project_root,
        check=True,
        capture_output=True,
    )
    tracked = result.stdout.decode("utf-8").split("\0")
    forbidden = [
        path
        for path in tracked
        if path.lower().endswith((".3ds", ".3dsx", ".cia", ".cci", ".cxi"))
    ]
    return ["Nintendo 3DS content binary is tracked: " + path for path in forbidden]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    errors = validate_manifest(manifest)
    errors.extend(tracked_binary_errors())
    print(json.dumps({"status": "PASSED" if not errors else "FAILED", "errors": errors}, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
