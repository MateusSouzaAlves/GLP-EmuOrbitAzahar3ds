#!/usr/bin/env python3
"""Validate the portable, fail-closed physical Adreno acceptance contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = PROJECT_ROOT / "config" / "nintendo3ds-adreno-gate.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_TEST_IDS = [
    "VULKAN_SWAPCHAIN",
    "VULKAN_SURFACE_RECREATION",
    "AZAHAR_60_FRAMES",
    "PROFILE_SWITCH",
    "AUDIO_FRONTEND",
    "INPUT_TOUCH_LIFECYCLE",
]
EXPECTED_BUNDLE_FILES = {
    "adreno-gate.json",
    "azahar_libretro.so",
    "bundle-manifest.json",
    "emuorbit-n3ds-adreno-test.apk",
    "emuorbit-n3ds-firebase-target.apk",
    "emuorbit-n3ds-firebase-test.apk",
    "MARS3DS_LICENSE.txt",
    "open-homebrew.3dsx",
    "README.txt",
    "run-adreno-gate.ps1",
    "run-firebase-adreno-gate.ps1",
    "THIRD_PARTY_NOTICES.txt",
}


def validate_contract(contract: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if contract.get("schemaVersion") != 1:
        errors.append("Adreno gate schema changed")
    if contract.get("planItems") != ["N3DS-06C", "N3DS-11D", "N3DS-13D"]:
        errors.append("Adreno gate plan identity changed")
    if contract.get("purpose") != "PORTABLE_PHYSICAL_ADRENO_ACCEPTANCE":
        errors.append("Adreno gate purpose changed")
    if contract.get("requiresPhysicalDevice") is not True:
        errors.append("Adreno gate must require physical hardware")
    if contract.get("commercialContentAllowed") is not False:
        errors.append("commercial content must remain forbidden")
    if contract.get("paidServiceActivationAllowed") is not False:
        errors.append("paid service activation must remain forbidden")
    if contract.get("minimumSdk") != 26 or contract.get("requiredAbi") != "arm64-v8a":
        errors.append("Adreno Android floor changed")
    if contract.get("acceptanceGpuRegex") != r"(?i)\bAdreno(?:\s|$)":
        errors.append("Adreno gate no longer requires an Adreno GPU")
    if contract.get("testPackage") != "com.mateussouza.emuorbit.n3ds.core.test":
        errors.append("private test package changed")
    if contract.get("cloudTargetPackage") != (
            "com.mateussouza.emuorbit.n3ds.adreno.target"):
        errors.append("private cloud target package changed")

    core = contract.get("core", {})
    if core.get("bundlePath") != "azahar_libretro.so" \
            or core.get("bytes") != 23157736 \
            or core.get("sha256") != (
                "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c") \
            or core.get("licenseSpdx") != "GPL-3.0-or-later" \
            or core.get("correspondingSourceCommit") != (
                "85ff5ce78e439e5d9fae84dd45165f061a9f18ed"):
        errors.append("pinned hardened core identity changed")

    content = contract.get("content", {})
    if content.get("slotId") != "OPEN_HOMEBREW_7" \
            or content.get("bundlePath") != "open-homebrew.3dsx" \
            or content.get("bytes") != 713384 \
            or content.get("sha256") != (
                "00fb87d97ecb866a99902740ab67e38e05f81d74295e0c3774eb62b90b0a335b") \
            or content.get("licenseSpdx") != "MIT" \
            or content.get("licenseBytes") != 1071 \
            or content.get("licenseSha256") != (
                "e7263faf3265216f672f54cedc9f8e112182c80c8b97031b53379fefc0b42f32"):
        errors.append("redistributable homebrew identity or license changed")
    for key in ("assetUrl", "licenseUrl"):
        value = content.get(key)
        if not isinstance(value, str) or not value.startswith(
                ("https://github.com/", "https://raw.githubusercontent.com/")):
            errors.append(f"homebrew {key} is not an official GitHub URL")

    focal_tests = contract.get("focalTests", [])
    if [test.get("id") for test in focal_tests if isinstance(test, dict)] != EXPECTED_TEST_IDS:
        errors.append("Adreno focal test list changed")
    if len(focal_tests) != len(EXPECTED_TEST_IDS):
        errors.append("Adreno gate must retain six focal tests")
    for test in focal_tests:
        if not isinstance(test, dict) or "#" not in str(test.get("classMethod", "")):
            errors.append("Adreno focal test selector is invalid")

    long_run = contract.get("longRun", {})
    if long_run.get("requiredForAcceptance") is not True \
            or long_run.get("profile") != "balanced" \
            or long_run.get("minutes") != 20 \
            or long_run.get("minimumSpeedPercent") != 95.0 \
            or long_run.get("maximumPssGrowthKilobytes") != 524288 \
            or long_run.get("galaxyProfilesReused") != [
                "conservative", "balanced", "performance"] \
            or long_run.get("evidenceToken") != "N3DS_LONG_RUN":
        errors.append("Adreno long-run acceptance floor changed")

    lifecycle = contract.get("localLifecycleGate", {})
    expected_lifecycle_methods = [
        "com.mateussouza.emuorbit.n3ds.core."
        "Nintendo3DsCoreLifecycleInstrumentedTest#"
        "closesAndRecreatesOwnerThreadSessionAcrossAndroidLifecycle",
        "com.mateussouza.emuorbit.n3ds.core."
        "Nintendo3DsCoreLifecycleInstrumentedTest#"
        "survivesRealLauncherSwitchesAndScreenOffOn",
        "com.mateussouza.emuorbit.n3ds.core."
        "Nintendo3DsCoreSessionInstrumentedTest#"
        "restoresTransientLifecycleStateAcrossFreshController",
    ]
    expected_physical_devices = [
        ("Samsung Galaxy A37 5G", "PASSED"),
        ("Samsung Galaxy A34 5G", "PASSED"),
        ("Poco X6 Pro", "PASSED"),
    ]
    observed_physical_devices = [
        (device.get("name"), device.get("status"))
        for device in lifecycle.get("requiredPhysicalDevices", [])
        if isinstance(device, dict)
    ]
    if lifecycle.get("requiredForN3ds06") is not True \
            or lifecycle.get("classMethods") != expected_lifecycle_methods \
            or lifecycle.get("evidenceTokens") != [
                "N3DS_ANDROID_LIFECYCLE", "N3DS_EXTERNAL_LIFECYCLE"] \
            or lifecycle.get("framesPerTransition") != 2 \
            or lifecycle.get("externalLifecycleCycles") != 3 \
            or lifecycle.get("remoteSubstitutionAllowed") is not False \
            or lifecycle.get("executionPolicy") != (
                "RUN_ONCE_PER_FINAL_LOCAL_DEVICE_GATE") \
            or observed_physical_devices != expected_physical_devices:
        errors.append("N3DS-06C local physical lifecycle contract changed")

    cloud = contract.get("firebaseTestLab", {})
    if cloud.get("projectId") != "emuorbitadvance" \
            or cloud.get("requiredBillingPlan") != "SPARK" \
            or cloud.get("billingMustBeDisabled") is not True \
            or cloud.get("noCostOnly") is not True \
            or cloud.get("deviceForm") != "PHYSICAL" \
            or cloud.get("recommendedDeviceCatalogId") != "redfin-30" \
            or cloud.get("recommendedDeviceName") != "Google Pixel 5" \
            or cloud.get("recommendedModelId") != "redfin" \
            or cloud.get("recommendedAndroidVersionId") != "30" \
            or cloud.get("recommendedSoc") != "Qualcomm Snapdragon 765G" \
            or cloud.get("recommendedGpu") != "Qualcomm Adreno 620" \
            or cloud.get("matrixTimeoutMinutes") != 45 \
            or cloud.get("targetApk") != "emuorbit-n3ds-firebase-target.apk" \
            or cloud.get("testApk") != "emuorbit-n3ds-firebase-test.apk" \
            or cloud.get("expectedGpuArgument") != "n3dsExpectedGpuRegex":
        errors.append("Firebase Test Lab no-cost physical gate changed")

    required_files = contract.get("requiredBundleFiles", [])
    if set(required_files) != EXPECTED_BUNDLE_FILES \
            or len(required_files) != len(EXPECTED_BUNDLE_FILES):
        errors.append("portable bundle file allowlist changed")
    return errors


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_bundle(contract: dict[str, Any], bundle: Path) -> list[str]:
    errors: list[str] = []
    if not bundle.is_dir():
        return ["portable Adreno bundle directory is missing"]
    observed = {path.name for path in bundle.iterdir() if path.is_file()}
    expected = set(contract.get("requiredBundleFiles", []))
    if observed != expected:
        errors.append("portable Adreno bundle contains missing or unexpected files")
        return errors
    manifest_path = bundle / "bundle-manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError):
        return errors + ["portable Adreno bundle manifest is unreadable"]
    entries = manifest.get("files")
    expected_hashed = expected - {"bundle-manifest.json"}
    if manifest.get("schemaVersion") != 1 \
            or not isinstance(entries, list) \
            or {entry.get("path") for entry in entries if isinstance(entry, dict)} \
            != expected_hashed:
        errors.append("portable Adreno bundle manifest inventory changed")
        return errors
    for entry in entries:
        path = bundle / entry["path"]
        digest = entry.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest) \
                or path.stat().st_size != entry.get("bytes") \
                or _sha256(path) != digest:
            errors.append(f"portable Adreno bundle identity mismatch: {entry['path']}")
    if _sha256(bundle / "azahar_libretro.so") != contract["core"]["sha256"]:
        errors.append("portable Adreno bundle core differs from contract")
    if _sha256(bundle / "open-homebrew.3dsx") != contract["content"]["sha256"]:
        errors.append("portable Adreno bundle homebrew differs from contract")
    if _sha256(bundle / "MARS3DS_LICENSE.txt") != contract["content"]["licenseSha256"]:
        errors.append("portable Adreno bundle homebrew license differs from contract")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--bundle", type=Path)
    args = parser.parse_args()
    contract = json.loads(args.contract.read_text(encoding="utf-8"))
    errors = validate_contract(contract)
    if args.bundle is not None:
        errors.extend(validate_bundle(contract, args.bundle.resolve()))
    print(json.dumps({"status": "PASSED" if not errors else "FAILED", "errors": errors}, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
