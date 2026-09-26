from pathlib import Path
import re
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = PROJECT_ROOT / "scripts" / "test-nintendo3ds-state-regression.ps1"


class Nintendo3dsStateRegressionScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT_PATH.read_text(encoding="utf-8")

    def test_runs_only_the_n3ds13c_state_and_lifecycle_gates(self):
        expected = (
            "preservesNativeTitleDataAcrossPrivateContentMatrix",
            "preservesExactSnapshotsAcrossAzaharCoreUpdateAndRollback",
            "recoversFromRejectedContentAndRecreatesSurfaceSessions",
            "closesAndRecreatesOwnerThreadSessionAcrossAndroidLifecycle",
        )
        for method in expected:
            self.assertIn(method, self.source)
        self.assertNotIn("capturesBoundedStereoPcmWithoutDroppingAtFrameCadence", self.source)
        self.assertNotIn("forwardsDigitalAnalogAndTouchThroughAzaharLibretroInput", self.source)

    def test_uses_hardened_base_and_pinned_update_candidate(self):
        self.assertIn("azahar_libretro.n3ds12b.so", self.source)
        self.assertIn("azahar_libretro_2126_1.so", self.source)
        self.assertIn("26e608f6fa292b27cda0ae8c84e148d17600a5e6", self.source)
        self.assertIn("n3dsExpectedContentCount', '3'", self.source)

    def test_runs_lifecycle_for_all_four_opaque_slots(self):
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
        self.assertIn("foreach ($slot in $slots)", self.source)
        self.assertIn("n3dsLifecycleCycles', '3'", self.source)

    def test_reuses_complete_long_run_and_exact_candidate_sentinel(self):
        self.assertIn("Assert-ProlongedRunBaseline", self.source)
        self.assertIn("n3ds11cAcceptedLongRuns", self.source)
        self.assertIn("n3ds11cLongRunDurationMinutesPerProfile", self.source)
        self.assertIn("elapsedMillis -lt 1200000", self.source)
        self.assertIn("measuredFrames -lt 70000", self.source)
        self.assertIn("exact hardened-candidate sentinel", self.source)
        self.assertNotIn("sustainsSelectedPerformanceProfileForLongRun", self.source)

    def test_cleanup_is_exact_and_preserves_preexisting_directories(self):
        self.assertIn("$beforeFilesScratch", self.source)
        self.assertIn("$beforeCacheScratch", self.source)
        self.assertIn("Where-Object { $_ -notin $Before }", self.source)
        self.assertIn("storage-matrix|core-update|long-run-balanced", self.source)
        self.assertNotIn("pm clear", self.source)
        self.assertNotIn("uninstall", self.source)

    def test_updates_only_sanitized_report_metrics_and_revalidates(self):
        self.assertIn("config/nintendo3ds-regression-report.json", self.source)
        self.assertIn("validate-nintendo3ds-regression-matrix.py", self.source)
        self.assertIn("SAVE_ROUNDTRIP", self.source)
        self.assertIn("UPDATE_WITHOUT_DATA_CLEAR", self.source)
        self.assertIn("PROLONGED_RUN", self.source)
        self.assertNotIn("Write-Host $_", self.source)

    def test_keeps_main_invariant(self):
        self.assertIn("git rev-parse main", self.source)
        self.assertIn("git rev-parse origin/main", self.source)
        self.assertIn("$baselineMain", self.source)


if __name__ == "__main__":
    unittest.main()
