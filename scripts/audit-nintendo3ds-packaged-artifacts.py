#!/usr/bin/env python3
"""Final artifact-only audit for the Nintendo 3DS AAB and universal APK."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
import tempfile
from pathlib import Path
from typing import Callable, Iterable
from zipfile import BadZipFile, ZipFile


SCRIPT_ROOT = Path(__file__).resolve().parent
PUBLIC_SOURCE_REPOSITORY = (
    "https://github.com/MateusSouzaAlves/GLP-EmuOrbitAzahar3ds"
)
PUBLIC_SOURCE_COMMIT = "85ff5ce78e439e5d9fae84dd45165f061a9f18ed"
EXPECTED_CORE_BYTES = 23_157_736
EXPECTED_CORE_SHA256 = (
    "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c"
)
AAB_RAW_CORE = "nintendo3dscore/lib/arm64-v8a/libazahar_libretro.so"
AAB_BOOTSTRAP = "nintendo3dscore/lib/arm64-v8a/libemuorbit_n3ds_bootstrap.so"
AAB_NOTICE = "nintendo3dscore/assets/nintendo3ds/THIRD_PARTY_NOTICES.txt"
APK_RAW_CORE = "lib/arm64-v8a/libazahar_libretro.so"
APK_BOOTSTRAP = "lib/arm64-v8a/libemuorbit_n3ds_bootstrap.so"
APK_NOTICE = "assets/nintendo3ds/THIRD_PARTY_NOTICES.txt"
DEFAULT_CORE_CONTEXT = "n3ds-arm64-v8a"
EXPECTED_CONTAINER_BYTES = EXPECTED_CORE_BYTES + 68
ROM_SUFFIXES = (".3ds", ".3dsx", ".cia", ".cci", ".cxi")
SOURCE_SUFFIXES = (".c", ".cc", ".cpp", ".h", ".hpp", ".java", ".kt", ".kts")
FORBIDDEN_NAME_MARKERS = (
    "nintendo3ds-sbom",
    "corresponding-source",
    "corresponding_source",
    "source-archive",
)
NativeAuditor = Callable[[str, bytes, Path, Iterable[str]], tuple[list[str], dict]]


def _load_native_module():
    script = SCRIPT_ROOT / "audit-nintendo3ds-native-artifacts.py"
    spec = importlib.util.spec_from_file_location("audit_n3ds_native_final", script)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"unable to load native auditor: {script}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _load_container_module():
    script = SCRIPT_ROOT / "package_core_container.py"
    spec = importlib.util.spec_from_file_location("audit_n3ds_container", script)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"unable to load container codec: {script}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


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


def _recover_core(container_bytes: bytes, seed: str, context: str) -> bytes:
    module = _load_container_module()
    with tempfile.TemporaryDirectory(prefix="emuorbit-n3ds-container-audit-") as directory:
        root = Path(directory)
        packed = root / "payload.bin"
        recovered = root / "recovered.so"
        packed.write_bytes(container_bytes)
        module.unpack(packed, recovered, seed, context)
        return recovered.read_bytes()


def _audit_native(
    kind: str,
    artifact_bytes: bytes,
    ndk_root: Path,
    forbidden_paths: Iterable[str],
) -> tuple[list[str], dict]:
    module = _load_native_module()
    with tempfile.TemporaryDirectory(prefix="emuorbit-n3ds-elf-audit-") as directory:
        suffix = "core.so" if kind == "core" else "bootstrap.so"
        artifact = Path(directory) / suffix
        artifact.write_bytes(artifact_bytes)
        readelf = module._tool(ndk_root, "llvm-readelf")
        nm = module._tool(ndk_root, "llvm-nm")
        outputs = module.ToolOutput(
            header=module._run(readelf, ("--file-header", "--wide"), artifact),
            dynamic=module._run(readelf, ("--dynamic", "--wide"), artifact),
            program_headers=module._run(
                readelf, ("--program-headers", "--wide"), artifact
            ),
            sections=module._run(readelf, ("--sections", "--wide"), artifact),
            notes=module._run(readelf, ("--notes", "--wide"), artifact),
            dynamic_symbols=module._run(
                nm, ("-D", "--defined-only", "--format=posix"), artifact
            ),
        )
        errors = module.audit_outputs(
            kind=kind,
            artifact_bytes=artifact_bytes,
            output=outputs,
            forbidden_paths=forbidden_paths,
        )
        report = {
            "bytes": len(artifact_bytes),
            "sha256": _sha256(artifact_bytes),
            "soname": module._soname(outputs.dynamic),
            "needed": sorted(module._needed_libraries(outputs.dynamic)),
            "definedExportCount": len(module._defined_symbols(outputs.dynamic_symbols)),
        }
        return errors, report


def _inventory_errors(names: Iterable[str], *, label: str) -> list[str]:
    errors: list[str] = []
    for name in names:
        lowered = name.lower()
        suffix = Path(lowered).suffix
        if suffix in ROM_SUFFIXES:
            errors.append(f"{label} contains ROM or homebrew content: {name}")
        if suffix in SOURCE_SUFFIXES:
            errors.append(f"{label} contains source code: {name}")
        if any(marker in lowered for marker in FORBIDDEN_NAME_MARKERS):
            errors.append(f"{label} contains external source/SBOM material: {name}")
    return errors


def _notice_errors(notice: bytes, *, label: str) -> list[str]:
    text = notice.decode("utf-8", errors="replace")
    errors: list[str] = []
    if PUBLIC_SOURCE_REPOSITORY not in text:
        errors.append(f"{label} notice does not link the public corresponding source")
    if PUBLIC_SOURCE_COMMIT not in text:
        errors.append(f"{label} notice does not pin the public source commit")
    if "GPL-3.0-or-later" not in text:
        errors.append(f"{label} notice does not state the combined component license")
    return errors


def audit_packaged_artifacts(
    bundle_path: Path,
    apk_path: Path,
    ndk_root: Path,
    *,
    seed: str,
    context: str = DEFAULT_CORE_CONTEXT,
    forbidden_paths: Iterable[str] = (),
    native_auditor: NativeAuditor = _audit_native,
) -> tuple[list[str], dict[str, object]]:
    errors: list[str] = []
    report: dict[str, object] = {}
    try:
        asset_name = protected_asset_name(seed, context)
        aab_container_entry = f"nintendo3dscore/assets/{asset_name}"
        apk_container_entry = f"assets/{asset_name}"
        with ZipFile(bundle_path) as bundle, ZipFile(apk_path) as apk:
            aab_names = set(bundle.namelist())
            apk_names = set(apk.namelist())
            errors.extend(_inventory_errors(aab_names, label="AAB"))
            errors.extend(_inventory_errors(apk_names, label="APK"))

            required_aab = {aab_container_entry, AAB_BOOTSTRAP, AAB_NOTICE}
            required_apk = {apk_container_entry, APK_BOOTSTRAP, APK_NOTICE}
            for missing in sorted(required_aab - aab_names):
                errors.append(f"AAB is missing required entry: {missing}")
            for missing in sorted(required_apk - apk_names):
                errors.append(f"APK is missing required entry: {missing}")
            if AAB_RAW_CORE in aab_names or APK_RAW_CORE in apk_names:
                errors.append("a plaintext Nintendo 3DS core remains directly extractable")
            feature_opaque_assets = sorted(
                name for name in aab_names
                if name.startswith("nintendo3dscore/assets/q")
            )
            if feature_opaque_assets != [aab_container_entry]:
                errors.append(
                    "AAB protected 3DS asset inventory differs from the expected seed: "
                    + ", ".join(feature_opaque_assets)
                )
            apk_opaque_assets = sorted(
                name for name in apk_names if name.startswith("assets/q")
            )
            expected_apk_opaque_assets = sorted({
                "assets/" + name.rsplit("/", 1)[-1]
                for name in aab_names
                if name.count("/") == 2 and "/assets/q" in name
            })
            if apk_opaque_assets != expected_apk_opaque_assets:
                errors.append(
                    "APK protected asset inventory differs from the audited AAB modules: "
                    + ", ".join(apk_opaque_assets)
                )
            if errors:
                return errors, report

            aab_container = bundle.read(aab_container_entry)
            apk_container = apk.read(apk_container_entry)
            aab_bootstrap = bundle.read(AAB_BOOTSTRAP)
            apk_bootstrap = apk.read(APK_BOOTSTRAP)
            aab_notice = bundle.read(AAB_NOTICE)
            apk_notice = apk.read(APK_NOTICE)

            if aab_container != apk_container:
                errors.append("AAB and APK contain different Nintendo 3DS containers")
            if aab_bootstrap != apk_bootstrap:
                errors.append("AAB and APK contain different Nintendo 3DS bootstraps")
            if aab_notice != apk_notice:
                errors.append("AAB and APK contain different Nintendo 3DS notices")
            if len(aab_container) != EXPECTED_CONTAINER_BYTES:
                errors.append("protected container size differs from the pinned payload envelope")
            if aab_container.startswith(b"\x7fELF"):
                errors.append("protected container exposes a plaintext ELF header")
            recovered_core = _recover_core(aab_container, seed, context)
            if len(recovered_core) != EXPECTED_CORE_BYTES:
                errors.append("recovered core size differs from the pinned hardened core")
            if _sha256(recovered_core) != EXPECTED_CORE_SHA256:
                errors.append("recovered core hash differs from the pinned hardened core")
            errors.extend(_notice_errors(aab_notice, label="AAB"))
            errors.extend(_notice_errors(apk_notice, label="APK"))

            native_reports: dict[str, dict] = {}
            for kind, payload in (("core", recovered_core), ("bootstrap", aab_bootstrap)):
                native_errors, native_report = native_auditor(
                    kind, payload, ndk_root, forbidden_paths
                )
                errors.extend(f"{kind}: {error}" for error in native_errors)
                native_reports[kind] = native_report

            base_n3ds_native = sorted(
                name
                for name in aab_names
                if name.startswith("base/lib/")
                and ("azahar" in name.lower() or "n3ds" in name.lower())
            )
            if base_n3ds_native:
                errors.append("Nintendo 3DS native code leaked into the AAB base module")

            report = {
                "bundleBytes": bundle_path.stat().st_size,
                "bundleSha256": _sha256(bundle_path.read_bytes()),
                "apkBytes": apk_path.stat().st_size,
                "apkSha256": _sha256(apk_path.read_bytes()),
                "containerEntry": aab_container_entry,
                "containerBytes": len(aab_container),
                "containerSha256": _sha256(aab_container),
                "coreBytes": len(recovered_core),
                "coreSha256": _sha256(recovered_core),
                "bootstrapBytes": len(aab_bootstrap),
                "bootstrapSha256": _sha256(aab_bootstrap),
                "publicSourceRepository": PUBLIC_SOURCE_REPOSITORY,
                "publicSourceCommit": PUBLIC_SOURCE_COMMIT,
                "sourceOrSbomEntries": 0,
                "romOrHomebrewEntries": 0,
                "native": native_reports,
            }
    except (OSError, BadZipFile, KeyError, ValueError) as error:
        errors.append(f"packaged artifacts could not be audited: {error}")
    return errors, report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--universal-apk", type=Path, required=True)
    parser.add_argument("--ndk-root", type=Path, required=True)
    parser.add_argument("--seed", required=True)
    parser.add_argument("--context", default=DEFAULT_CORE_CONTEXT)
    parser.add_argument("--forbid-path", action="append", default=[])
    args = parser.parse_args()

    bundle = args.bundle.resolve(strict=True)
    apk = args.universal_apk.resolve(strict=True)
    ndk_root = args.ndk_root.resolve(strict=True)
    errors, report = audit_packaged_artifacts(
        bundle,
        apk,
        ndk_root,
        seed=args.seed,
        context=args.context,
        forbidden_paths=args.forbid_path,
    )
    report["bundle"] = str(bundle)
    report["universalApk"] = str(apk)
    report["status"] = "PASSED" if not errors else "FAILED"
    report["errors"] = errors
    print(json.dumps(report, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
