from pathlib import Path
import re
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = PROJECT_ROOT / "scripts" / "test-nintendo3ds-regression-slice.ps1"


class Nintendo3dsRegressionSliceScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT_PATH.read_text(encoding="utf-8")

    def test_uses_exactly_four_opaque_slots_without_content_identity_in_report(self):
        slots = re.findall(r"SlotId = '([^']+)'", self.source)
        self.assertEqual(
            [
                "PRIVATE_OWNED_DUMP_1",
                "PRIVATE_OWNED_DUMP_2",
                "PRIVATE_OWNED_DUMP_3",
                "OPEN_HOMEBREW_1",
            ],
            slots,
        )
        report_block = self.source.split("$report = [ordered]@{", 1)[1]
        for forbidden in ("DevicePath", "corePath", "private-regression", ".3dsx"):
            self.assertNotIn(forbidden, report_block)

    def test_reuses_focal_video_input_and_audio_instrumentation(self):
        self.assertIn("measuresFramesAndClearsOnlyRegenerablePrivateCaches", self.source)
        self.assertIn("forwardsDigitalAnalogAndTouchThroughAzaharLibretroInput", self.source)
        self.assertIn("capturesBoundedStereoPcmWithoutDroppingAtFrameCadence", self.source)
        self.assertIn("routesPermissionAwareAndroidMicrophoneThroughAzahar", self.source)
        self.assertNotIn("sustainsSelectedPerformanceProfileForLongRun", self.source)
        self.assertNotIn("preservesExactSnapshotsAcrossAzaharCoreUpdateAndRollback", self.source)

    def test_selects_hardened_candidate_and_requires_explicit_baseline_reuse(self):
        self.assertIn("azahar_libretro.n3ds12b.so", self.source)
        self.assertIn("$ReuseExistingSystemsBaseline", self.source)
        self.assertIn("config/nintendo3ds-regression-report.json", self.source)
        self.assertIn("existing-systems baseline is incomplete", self.source)

    def test_covers_each_existing_system_once(self):
        existing_block = self.source.split("$existingTests = [ordered]@{", 1)[1]
        existing_block = existing_block.split("\n    }", 1)[0]
        systems = re.findall(r"^\s*(GB|GBC|GBA|NDS) =", existing_block, re.MULTILINE)
        self.assertEqual(["GB", "GBC", "GBA", "NDS"], systems)
        self.assertIn("HomebrewCoreSmokeInstrumentedTest", self.source)
        self.assertIn("scenarios = 1", self.source)

    def test_rejects_crashes_and_validates_sanitized_report(self):
        self.assertIn("Fatal signal|FATAL EXCEPTION|VK_ERROR_DEVICE_LOST", self.source)
        self.assertIn("validate-nintendo3ds-regression-matrix.py", self.source)
        self.assertIn("reportPrivacy=OPAQUE_SLOT_IDS_ONLY", self.source)
        self.assertNotIn("Write-Host $_", self.source)

    def test_keeps_main_invariant_and_report_inside_workspace(self):
        self.assertIn("git rev-parse main", self.source)
        self.assertIn("git rev-parse origin/main", self.source)
        self.assertIn("$baselineMain", self.source)
        self.assertIn("must remain inside the workspace", self.source)


if __name__ == "__main__":
    unittest.main()
