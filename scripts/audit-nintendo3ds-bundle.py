#!/usr/bin/env python3
"""Audit the Nintendo 3DS dynamic-feature boundary inside an Android App Bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile


FEATURE = "nintendo3dscore"
RAW_CORE_ENTRY = f"{FEATURE}/lib/arm64-v8a/libazahar_libretro.so"
BOOTSTRAP_ENTRY = f"{FEATURE}/lib/arm64-v8a/libemuorbit_n3ds_bootstrap.so"
NOTICE_ENTRY = f"{FEATURE}/assets/nintendo3ds/THIRD_PARTY_NOTICES.txt"
SBOM_ENTRY = f"{FEATURE}/assets/nintendo3ds/nintendo3ds-sbom.cdx.json"
EXPECTED_CORE_BYTES = 23_157_736
EXPECTED_CORE_SHA256 = (
    "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c"
)
DEFAULT_CORE_CONTEXT = "n3ds-arm64-v8a"
EXPECTED_CONTAINER_BYTES = EXPECTED_CORE_BYTES + 68
FEATURE_DESCRIPTOR = b"Lcom/mateussouza/emuorbit/n3ds/core/"
DIST_NAMESPACE = "http://schemas.android.com/apk/distribution"
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
DELIVERY_TEST_PACKAGE = "com.mateussouza.emuorbit.advance.n3ds.deliverytest"
DELIVERY_TEST_PROBE = (
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsDeliveryProbeActivity"
)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def protected_asset_name(seed: str, context: str) -> str:
    if not 8 <= len(seed) <= 128 or not all(
        value.isascii() and (value.isalnum() or value in "._-")
        for value in seed
    ):
        raise ValueError("build seed format is invalid")
    if not context or not all(
        value.isascii() and (value.isalnum() or value in "._-")
        for value in context
    ):
        raise ValueError("core context format is invalid")
    digest = hashlib.sha256(
        f"{seed}|{context}|container-asset".encode("ascii")
    ).hexdigest()
    return "q" + digest[:23]


def audit_manifest(
    manifest_path: Path, *, allow_isolated_delivery_test_probe: bool = False
) -> list[str]:
    errors: list[str] = []
    try:
        root = ET.parse(manifest_path).getroot()
    except (OSError, ET.ParseError) as error:
        return [f"feature manifest could not be parsed: {error}"]

    feature_name = root.get("featureSplit") or root.get("split")
    android_feature_flag = root.get(f"{{{ANDROID_NAMESPACE}}}isFeatureSplit")
    if feature_name != FEATURE or android_feature_flag not in (None, "true"):
        errors.append("featureSplit is not nintendo3dscore")
    module = root.find(f"{{{DIST_NAMESPACE}}}module")
    if module is None:
        errors.append("distribution module declaration is missing")
    else:
        if module.get(f"{{{DIST_NAMESPACE}}}instant") != "false":
            errors.append("feature must not be instant-enabled")
        delivery = module.find(f"{{{DIST_NAMESPACE}}}delivery")
        install_time = None if delivery is None else delivery.find(
            f"{{{DIST_NAMESPACE}}}install-time"
        )
        if install_time is None:
            errors.append("feature is not declared for install-time delivery")
        else:
            removable = install_time.find(f"{{{DIST_NAMESPACE}}}removable")
            if (
                removable is None
                or removable.get(f"{{{DIST_NAMESPACE}}}value") != "true"
            ):
                errors.append("install-time feature is not kept as a removable split")
        if delivery is not None and delivery.find(
            f"{{{DIST_NAMESPACE}}}on-demand"
        ) is not None:
            errors.append("feature must not use on-demand delivery")
        fusing = module.find(f"{{{DIST_NAMESPACE}}}fusing")
        if fusing is None or fusing.get(f"{{{DIST_NAMESPACE}}}include") != "true":
            errors.append("feature is not fused into universal audit APKs")

    application = root.find("application")
    if application is None:
        errors.append("feature application declaration is missing")
    else:
        for component in list(application):
            if component.get(f"{{{ANDROID_NAMESPACE}}}exported") == "true":
                is_disabled_protected_probe = (
                    component.tag == "activity"
                    and component.get(f"{{{ANDROID_NAMESPACE}}}name")
                    == DELIVERY_TEST_PROBE
                    and component.get(f"{{{ANDROID_NAMESPACE}}}enabled") == "false"
                    and component.get(f"{{{ANDROID_NAMESPACE}}}permission")
                    == "android.permission.DUMP"
                    and component.get(f"{{{ANDROID_NAMESPACE}}}noHistory") == "true"
                    and component.get(f"{{{ANDROID_NAMESPACE}}}excludeFromRecents")
                    == "true"
                )
                is_isolated_delivery_probe = (
                    allow_isolated_delivery_test_probe
                    and root.get("package") == DELIVERY_TEST_PACKAGE
                    and application.get(f"{{{ANDROID_NAMESPACE}}}debuggable") == "true"
                    and component.tag == "activity"
                    and component.get(f"{{{ANDROID_NAMESPACE}}}name")
                    == DELIVERY_TEST_PROBE
                    and component.get(f"{{{ANDROID_NAMESPACE}}}enabled") == "true"
                    and component.get(f"{{{ANDROID_NAMESPACE}}}permission")
                    == "android.permission.DUMP"
                    and component.get(f"{{{ANDROID_NAMESPACE}}}noHistory") == "true"
                    and component.get(f"{{{ANDROID_NAMESPACE}}}excludeFromRecents")
                    == "true"
                )
                if is_disabled_protected_probe or is_isolated_delivery_probe:
                    continue
                errors.append("feature contains an exported Android component")
                break
    return errors


def audit_bundle(
    bundle_path: Path,
    *,
    seed: str,
    context: str = DEFAULT_CORE_CONTEXT,
) -> tuple[list[str], dict[str, object]]:
    errors: list[str] = []
    report: dict[str, object] = {}
    try:
        asset_name = protected_asset_name(seed, context)
        container_entry = f"{FEATURE}/assets/{asset_name}"
        with ZipFile(bundle_path) as bundle:
            entries = {entry.filename: entry for entry in bundle.infolist()}
            required = {container_entry, BOOTSTRAP_ENTRY, NOTICE_ENTRY}
            missing = sorted(required - entries.keys())
            if missing:
                errors.append("feature entries are missing: " + ", ".join(missing))
            if SBOM_ENTRY in entries:
                errors.append("the external corresponding-source SBOM leaked into the app bundle")
            if RAW_CORE_ENTRY in entries:
                errors.append("plaintext Nintendo 3DS core remains directly extractable")
            feature_opaque_assets = sorted(
                name for name in entries
                if name.startswith(f"{FEATURE}/assets/q")
            )
            if feature_opaque_assets != [container_entry]:
                errors.append(
                    "feature protected asset inventory differs from the expected seed: "
                    + ", ".join(feature_opaque_assets)
                )
            if f"base/assets/{asset_name}" in entries:
                errors.append("Nintendo 3DS protected container leaked into base")

            base_native_leaks = sorted(
                name
                for name in entries
                if name.startswith("base/lib/")
                and ("azahar" in name.lower() or "n3ds" in name.lower())
            )
            if base_native_leaks:
                errors.append(
                    "Nintendo 3DS native payload leaked into base: "
                    + ", ".join(base_native_leaks)
                )

            unexpected_feature_abis = sorted(
                name
                for name in entries
                if name.startswith(f"{FEATURE}/lib/")
                and not name.startswith(f"{FEATURE}/lib/arm64-v8a/")
            )
            if unexpected_feature_abis:
                errors.append(
                    "feature contains unsupported native ABIs: "
                    + ", ".join(unexpected_feature_abis)
                )
            feature_native_entries = sorted(
                name for name in entries if name.startswith(f"{FEATURE}/lib/")
            )
            expected_feature_native_entries = [BOOTSTRAP_ENTRY]
            if feature_native_entries != expected_feature_native_entries:
                errors.append(
                    "feature native payload differs from the approved core and bootstrap: "
                    + ", ".join(feature_native_entries)
                )

            base_dex_entries = sorted(
                name
                for name in entries
                if name.startswith("base/dex/") and name.endswith(".dex")
            )
            if not base_dex_entries:
                errors.append("base DEX entries are missing")
            elif any(
                FEATURE_DESCRIPTOR in bundle.read(name) for name in base_dex_entries
            ):
                errors.append("Nintendo 3DS feature classes leaked into base DEX")

            feature_dex_entries = sorted(
                name
                for name in entries
                if name.startswith(f"{FEATURE}/dex/") and name.endswith(".dex")
            )
            if not feature_dex_entries or not any(
                FEATURE_DESCRIPTOR in bundle.read(name) for name in feature_dex_entries
            ):
                errors.append("Nintendo 3DS classes are absent from feature DEX")

            container_bytes = b""
            if container_entry in entries:
                container_bytes = bundle.read(container_entry)
                if len(container_bytes) != EXPECTED_CONTAINER_BYTES:
                    errors.append("protected container size does not match the pinned envelope")
                if container_bytes.startswith(b"\x7fELF"):
                    errors.append("protected container exposes a plaintext ELF header")

            module_sizes: dict[str, dict[str, int]] = {}
            for module in ("base", FEATURE):
                selected = [
                    entry
                    for name, entry in entries.items()
                    if name.startswith(module + "/")
                ]
                module_sizes[module] = {
                    "uncompressedBytes": sum(entry.file_size for entry in selected),
                    "compressedBytes": sum(entry.compress_size for entry in selected),
                    "entryCount": len(selected),
                }
            report = {
                "bundle": str(bundle_path),
                "bundleBytes": bundle_path.stat().st_size,
                "bundleSha256": _sha256(bundle_path.read_bytes()),
                "containerEntry": container_entry,
                "containerBytes": len(container_bytes),
                "containerSha256": _sha256(container_bytes) if container_bytes else None,
                "baseDexEntries": len(base_dex_entries),
                "featureDexEntries": len(feature_dex_entries),
                "featureNativeEntries": feature_native_entries,
                "moduleSizes": module_sizes,
            }
    except (OSError, ValueError) as error:
        errors.append(f"bundle could not be audited: {error}")
    return errors, report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--feature-manifest", type=Path, required=True)
    parser.add_argument("--seed", required=True)
    parser.add_argument("--context", default=DEFAULT_CORE_CONTEXT)
    parser.add_argument(
        "--allow-isolated-delivery-test-probe",
        action="store_true",
        help="Allow only the DUMP-protected probe in the isolated test package.",
    )
    args = parser.parse_args()

    bundle = args.bundle.resolve(strict=True)
    manifest = args.feature_manifest.resolve(strict=True)
    errors, report = audit_bundle(bundle, seed=args.seed, context=args.context)
    errors.extend(
        audit_manifest(
            manifest,
            allow_isolated_delivery_test_probe=args.allow_isolated_delivery_test_probe,
        )
    )
    report["featureManifest"] = str(manifest)
    report["status"] = "PASSED" if not errors else "FAILED"
    report["errors"] = errors
    print(json.dumps(report, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
