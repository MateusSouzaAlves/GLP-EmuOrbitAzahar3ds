import re
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = PROJECT_ROOT / "scripts" / "test-nintendo3ds-expanded-corpus.ps1"
BUILD_PATH = PROJECT_ROOT / "nintendo3dscore" / "build.gradle.kts"


class Nintendo3DsExpandedCorpusScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT_PATH.read_text(encoding="utf-8")
        cls.build_source = BUILD_PATH.read_text(encoding="utf-8")

    def test_uses_pinned_open_corpus_and_sanitized_baseline(self):
        self.assertIn("nintendo3ds-open-homebrew-corpus.json", self.source)
        self.assertIn("validate-nintendo3ds-open-homebrew-corpus.py", self.source)
        self.assertIn("config/nintendo3ds-regression-report.json", self.source)
        self.assertIn("validate-nintendo3ds-regression-matrix.py", self.source)

    def test_runs_only_two_combined_focal_instrumentations_per_new_content(self):
        self.assertEqual(2, len(re.findall(r"Invoke-IsolatedInstrumentation `", self.source)))
        self.assertIn("validatesAudioFrontendForSilentOrPcmHomebrew", self.source)
        self.assertIn("forwardsDigitalAnalogAndTouchThroughAzaharLibretroInput", self.source)
        self.assertNotIn("routesPermissionAwareAndroidMicrophoneThroughAzahar", self.source)
        self.assertNotIn("sustainsSelectedPerformanceProfileForLongRun", self.source)
        self.assertNotIn("preservesExactSnapshotsAcrossAzaharCoreUpdateAndRollback", self.source)
        self.assertIn("'n3dsCorpusFrameCount', '12'", self.source)

    def test_verifies_download_identity_archive_safety_and_3dsx_header(self):
        self.assertIn("Assert-FileIdentity", self.source)
        self.assertIn("Get-FileHash", self.source)
        self.assertIn("contains an unsafe path", self.source)
        self.assertIn("valid 3DSX header", self.source)

    def test_cleans_only_dedicated_pc_device_and_test_scratch_paths(self):
        self.assertIn("EmuOrbit-N3DS-13D-", self.source)
        self.assertIn("/data/local/tmp/emuorbit-n3ds13d-", self.source)
        self.assertIn("open-regression-", self.source)
        self.assertIn("Remove-ExactDeviceArtifacts", self.source)
        self.assertIn("Remove-ExactLocalStage", self.source)
        self.assertIn("stagedContentsRemaining=0", self.source)

    def test_preserves_main_branch_and_both_protected_packages(self):
        self.assertIn("git rev-parse main", self.source)
        self.assertIn("git rev-parse origin/main", self.source)
        self.assertIn("com.mateussouza.emuorbit.advance", self.source)
        self.assertIn("com.mateussouza.emuorbit.n3ds.core.test", self.source)

    def test_private_instrumentation_apk_is_self_contained(self):
        self.assertIn('getByName("androidTest")', self.build_source)
        self.assertIn('file("src/debug/java")', self.build_source)
        self.assertIn('it.name == "processDebugAndroidTestManifest"', self.build_source)
        self.assertIn("nintendo3DsSelfContainedTestRuntime", self.build_source)
        self.assertIn("TargetJvmEnvironment.ANDROID", self.build_source)
        self.assertIn(
            "add(nintendo3DsSelfContainedTestRuntime.name, libs.androidx.activity)",
            self.build_source,
        )
        self.assertIn("nintendo3DsDebugRuntimeClasses", self.build_source)
        self.assertIn("prepareNintendo3DsSelfContainedTestJni", self.build_source)
        self.assertNotIn('configurations.getByName("debugRuntimeClasspath")', self.build_source)

    def test_report_block_contains_no_open_project_identity_or_device_paths(self):
        report_block = self.source.split("$report = [ordered]@{", 1)[1]
        report_block = report_block.split("$resolvedReport =", 1)[0]
        for forbidden in (
            "repositoryUrl",
            "assetUrl",
            "contentSha256",
            "DevicePath",
            "privateRoot",
            ".3dsx",
        ):
            self.assertNotIn(forbidden, report_block)


if __name__ == "__main__":
    unittest.main()
