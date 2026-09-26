import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR_PATH = PROJECT_ROOT / "scripts" / "validate-nintendo3ds-adreno-gate.py"
CONTRACT_PATH = PROJECT_ROOT / "config" / "nintendo3ds-adreno-gate.json"
PREPARE_PATH = PROJECT_ROOT / "scripts" / "prepare-nintendo3ds-adreno-gate.ps1"
RUNNER_PATH = PROJECT_ROOT / "scripts" / "run-nintendo3ds-adreno-gate.ps1"
FIREBASE_RUNNER_PATH = PROJECT_ROOT / "scripts" / "run-firebase-adreno-gate.ps1"
SPEC = importlib.util.spec_from_file_location("validate_n3ds_adreno_gate", VALIDATOR_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)
CONTRACT = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


class Nintendo3DsAdrenoGateTest(unittest.TestCase):
    def test_accepts_physical_fail_closed_contract(self):
        self.assertEqual([], MODULE.validate_contract(CONTRACT))

    def test_rejects_paid_commercial_emulated_or_shortened_acceptance(self):
        changed = deepcopy(CONTRACT)
        changed["requiresPhysicalDevice"] = False
        changed["commercialContentAllowed"] = True
        changed["paidServiceActivationAllowed"] = True
        changed["acceptanceGpuRegex"] = "Xclipse|Adreno"
        changed["longRun"]["minutes"] = 5
        changed["firebaseTestLab"]["billingMustBeDisabled"] = False
        errors = MODULE.validate_contract(changed)
        self.assertTrue(any("physical" in error for error in errors))
        self.assertTrue(any("commercial" in error for error in errors))
        self.assertTrue(any("paid" in error for error in errors))
        self.assertTrue(any("Adreno GPU" in error for error in errors))
        self.assertTrue(any("long-run" in error for error in errors))
        self.assertTrue(any("Firebase Test Lab" in error for error in errors))

    def test_validates_bundle_allowlist_and_every_file_hash(self):
        changed = deepcopy(CONTRACT)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entries = []
            for name in changed["requiredBundleFiles"]:
                if name == "bundle-manifest.json":
                    continue
                payload = ("fixture:" + name).encode("utf-8")
                (root / name).write_bytes(payload)
                digest = hashlib.sha256(payload).hexdigest()
                entries.append({"path": name, "bytes": len(payload), "sha256": digest})
                if name == "azahar_libretro.so":
                    changed["core"]["sha256"] = digest
                elif name == "open-homebrew.3dsx":
                    changed["content"]["sha256"] = digest
                elif name == "MARS3DS_LICENSE.txt":
                    changed["content"]["licenseSha256"] = digest
            manifest = {
                "schemaVersion": 1,
                "planItems": ["N3DS-06C", "N3DS-11D", "N3DS-13D"],
                "files": sorted(entries, key=lambda entry: entry["path"]),
            }
            (root / "bundle-manifest.json").write_text(
                json.dumps(manifest), encoding="utf-8"
            )
            self.assertEqual([], MODULE.validate_bundle(changed, root))
            (root / "README.txt").write_text("tampered", encoding="utf-8")
            self.assertTrue(MODULE.validate_bundle(changed, root))

    def test_prepare_script_builds_separate_offline_licensed_payload(self):
        source = PREPARE_PATH.read_text(encoding="utf-8")
        self.assertIn(":nintendo3dscore:assembleDebugAndroidTest", source)
        self.assertIn(":nintendo3dsadrenotarget:assembleDebug", source)
        self.assertIn("EMUORBIT_N3DS_ADRENO_CLOUD_TEST=true", source)
        self.assertIn("libemuorbit_n3ds_bootstrap.so", source)
        self.assertIn("test APK must not embed the core", source)
        self.assertIn("emuorbit-n3ds-firebase-target.apk", source)
        self.assertIn("emuorbit-n3ds-firebase-test.apk", source)
        self.assertIn("MARS3DS_LICENSE.txt", source)
        self.assertIn("THIRD_PARTY_NOTICES.txt", source)
        self.assertIn("$ContentPath", source)
        self.assertIn("$ContentLicensePath", source)
        self.assertIn("Cached content and its license must be supplied together", source)
        self.assertIn("paidServiceUsed=false", source)

    def test_runner_requires_real_adreno_and_full_acceptance_long_run(self):
        source = RUNNER_PATH.read_text(encoding="utf-8")
        self.assertIn("ro.kernel.qemu", source)
        self.assertIn("acceptanceGpuRegex", source)
        self.assertIn("n3dsLongRunEnabled", source)
        self.assertIn("$SkipLongRun -and -not $CalibrationOnly", source)
        self.assertIn("$LifecycleOnly", source)
        self.assertIn("N3DS_LIFECYCLE_GATE", source)
        self.assertIn("PASSED_LIFECYCLE", source)
        self.assertIn("'mkdir', '-p', $privateRoot", source)
        self.assertIn("n3dsLifecycleHostFrames", source)
        self.assertIn("RUN_ONCE_PER_FINAL_LOCAL_DEVICE_GATE", CONTRACT_PATH.read_text(encoding="utf-8"))
        self.assertIn("n3dsExternalLifecycleCycles", source)
        self.assertIn("PASSED_ADRENO", source)
        self.assertIn("CALIBRATION_ONLY_INELIGIBLE", source)
        self.assertIn("exactStageCleanupComplete", source)
        self.assertNotIn("gcloud", source.lower())
        self.assertNotIn("firebase", source.lower())

    def test_firebase_runner_refuses_billing_and_requires_physical_catalog_entry(self):
        source = FIREBASE_RUNNER_PATH.read_text(encoding="utf-8")
        self.assertIn("billing projects describe", source)
        self.assertIn("$billing.billingEnabled -ne $false", source)
        self.assertIn("$Model = 'redfin'", source)
        self.assertIn("$AndroidVersion = '30'", source)
        self.assertIn("firebase test android models describe $Model", source)
        self.assertIn("deviceForm", source)
        self.assertIn("--type=instrumentation", source)
        self.assertIn("--app=$targetApk", source)
        self.assertIn("--test=$testApk", source)
        self.assertIn("n3dsExpectedGpuRegex", source)
        self.assertIn("n3dsLongRunEnabled=true", source)
        self.assertIn("[ValidateSet('Focal', 'LongRun', 'All')]", source)
        self.assertIn("[string] $Phase = 'Focal'", source)
        self.assertIn("$Phase -eq 'Focal'", source)
        self.assertIn("paidServiceUsed=false", source)


if __name__ == "__main__":
    unittest.main()
