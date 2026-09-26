from pathlib import Path
import re
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = PROJECT_ROOT / "scripts" / "test-nintendo3ds-core-suite.ps1"
BUILD_SCRIPT_PATH = PROJECT_ROOT / "nintendo3dscore" / "build.gradle.kts"
TEST_MANIFEST_PATH = (
    PROJECT_ROOT / "nintendo3dscore" / "src" / "androidTest" / "AndroidManifest.xml"
)
LOCAL_TEST_APPLICATION_PATH = (
    PROJECT_ROOT
    / "nintendo3dscore"
    / "src"
    / "androidTest"
    / "java"
    / "com"
    / "mateussouza"
    / "emuorbit"
    / "n3ds"
    / "core"
    / "Nintendo3DsLocalTestApplication.java"
)
CORE_GAMEPLAY_SOURCE_PATH = (
    PROJECT_ROOT / "nintendo3dscore" / "src" / "main" / "cpp" / "core_gameplay_session.cpp"
)
CORE_GAMEPLAY_HEADER_PATH = (
    PROJECT_ROOT / "nintendo3dscore" / "src" / "main" / "cpp" / "core_gameplay_session.h"
)


class Nintendo3dsCoreSuiteScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT_PATH.read_text(encoding="utf-8")
        cls.test_block = cls.source.split("$tests = @(", 1)[1].split("\n)", 1)[0]

    def test_runs_all_twenty_six_non_optional_device_gates_once(self):
        tests = re.findall(r'"([^"\n]+#[^"\n]+)"', self.test_block)
        self.assertEqual(26, len(tests))
        self.assertEqual(26, len(set(tests)))
        self.assertIn("Nintendo3DsMiiDataManagerInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsMiiImportWorkflowInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsMiiRecoveryCoordinatorInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsActivityResultHostInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsExperienceSettingsInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsProductActivityInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsLaunchReadinessInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsReadinessLocalizationInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsReadinessDialogInstrumentedTest", self.test_block)
        self.assertIn("Nintendo3DsExperimentalHostInstrumentedTest", self.test_block)
        self.assertNotIn("recordsInteractiveSessionSaveMutation", self.test_block)
        self.assertNotIn("preservesExactSnapshotsAcrossAzaharCoreUpdateAndRollback", self.test_block)

    def test_isolates_each_native_test_and_rejects_crash_signatures(self):
        loop = self.source.split("foreach ($test in $tests)", 1)[1]
        force_stop = loop.index('"am", "force-stop"')
        instrumentation = loop.index('"am", "instrument"')
        self.assertLess(force_stop, instrumentation)
        self.assertIn('"logcat", "-b", "crash", "-c"', loop)
        self.assertIn("Fatal signal|FATAL EXCEPTION|VK_ERROR_DEVICE_LOST", loop)
        self.assertIn("processPolicy=FRESH_PER_TEST", loop)

    def test_only_retries_the_unchanged_fast_forward_performance_gate(self):
        self.assertIn(
            '*#keepsFastForwardAudioNonBlockingAndAccountsForEveryFrame',
            self.source,
        )
        self.assertIn("$maximumAttempts", self.source)
        self.assertIn("retries=$retries", self.source)

    def test_interactive_mode_is_single_content_strict_by_default(self):
        self.assertIn('DefaultParameterSetName = "Suite"', self.source)
        self.assertIn('ParameterSetName = "Interactive"', self.source)
        self.assertIn("[ValidateRange(30, 1800)]", self.source)
        self.assertIn("[ValidateLength(0, 16384)]", self.source)
        self.assertIn("$strictInteractive = -not $InteractiveSmokeOnly.IsPresent", self.source)
        self.assertIn('"n3dsInteractiveRequireSave", $strictValue', self.source)
        self.assertIn(
            '"n3dsInteractiveRequireExistingTitleRoot", $strictValue',
            self.source,
        )
        self.assertIn("INTERACTIVE_STRICT_SAVE", self.source)
        self.assertIn("INTERACTIVE_SMOKE", self.source)
        self.assertIn("\\A[A-Za-z0-9_.:+,\\-]+\\z", self.source)

    def test_self_targeted_qa_packages_feature_resources_and_private_hosts(self):
        build_source = BUILD_SCRIPT_PATH.read_text(encoding="utf-8")
        manifest_source = TEST_MANIFEST_PATH.read_text(encoding="utf-8")
        application_source = LOCAL_TEST_APPLICATION_PATH.read_text(encoding="utf-8")

        self.assertIn(
            'res.directories.add(file("src/main/res").absolutePath)',
            build_source,
        )
        self.assertIn("Nintendo3DsLocalTestApplication", manifest_source)
        for component in (
            "Nintendo3DsReadinessTestActivity",
            "Nintendo3DsSettingsTestActivity",
            "Nintendo3DsActivityResultTestActivity",
            "Nintendo3DsProductActivity",
            "Nintendo3DsMiiTestProvider",
        ):
            self.assertIn(component, manifest_source)
        self.assertIn('android:authorities="com.mateussouza.emuorbit.n3ds.core.test.mii"',
                      manifest_source)
        self.assertIn('android:exported="false"', manifest_source)
        self.assertIn("LOCAL_TEST_PACKAGE", application_source)
        self.assertIn('remapResourceType(R.animator.class, "animator")', application_source)
        self.assertIn('remapResourceType(R.drawable.class, "drawable")', application_source)
        self.assertIn('remapResourceType(R.string.class, "string")', application_source)

    def test_keeps_one_validated_core_mapping_resident_across_surface_lifecycle(self):
        source = CORE_GAMEPLAY_SOURCE_PATH.read_text(encoding="utf-8")
        header = CORE_GAMEPLAY_HEADER_PATH.read_text(encoding="utf-8")

        self.assertIn("g_residentCoreLibrary", source)
        self.assertIn("g_residentCoreLibraryPath", source)
        self.assertIn("openCoreLibrary(", source)
        self.assertIn("retainCoreLibraryForProcess(", source)
        self.assertIn("libraryProcessResident_", header)
        self.assertIn("if (!libraryProcessResident_)", source)
        self.assertIn("dlclose(library_)", source)
        self.assertLess(
            source.index("The native library is not the expected Nintendo 3DS core"),
            source.index("retainCoreLibraryForProcess(session->library_"),
        )


if __name__ == "__main__":
    unittest.main()
