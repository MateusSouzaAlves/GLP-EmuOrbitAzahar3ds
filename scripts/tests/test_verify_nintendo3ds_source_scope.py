from copy import deepcopy
import importlib.util
from pathlib import Path
import tempfile
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = PROJECT_ROOT / "scripts" / "verify-nintendo3ds-source-scope.py"
SPEC = importlib.util.spec_from_file_location("nintendo3ds_source_scope", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class Nintendo3dsSourceScopeTest(unittest.TestCase):
    def setUp(self):
        self.manifest = MODULE.load_manifest()

    def test_repository_changes_remain_covered_without_reopening_frozen_public_source(self):
        errors, candidates = MODULE.audit_repository(PROJECT_ROOT, self.manifest)
        self.assertEqual([], errors)
        self.assertIn("config/nintendo3ds-source-scope.json", candidates)
        self.assertIn("scripts/verify-nintendo3ds-source-scope.py", candidates)
        self.assertFalse(
            self.manifest["publication"]["publicSourceDiscrepancyBlocksImplementation"]
        )

    def test_rejects_narrow_scope_or_unpinned_upstream(self):
        invalid = deepcopy(self.manifest)
        invalid["scopePolicy"] = "CORE_ONLY"
        invalid["distributedUpstreams"][0]["commit"] = "floating-main"
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("all code linked" in error for error in errors))
        self.assertTrue(any("full commit" in error for error in errors))

    def test_rejects_progress_that_does_not_match_verified_acceptance_units(self):
        invalid = deepcopy(self.manifest)
        invalid["progressAccounting"]["verifiedExecutionPercent"] = 89.9
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("does not match its formula" in error for error in errors))

        invalid = deepcopy(self.manifest)
        invalid["progressAccounting"]["openMilestoneAcceptanceUnits"]["N3DS-10"] = {
            "accepted": 15,
            "total": 15,
        }
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("acceptance units changed" in error for error in errors))

    def test_rejects_virtual_controls_contract_or_evidence_regression(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds10Decision"]["virtualControlsDigitalButtons"] = 14
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("virtual controls contract" in error for error in errors))

        invalid = deepcopy(self.manifest)
        invalid["n3ds10Decision"]["validation"][
            "virtualControlsInstrumentedTestsPassed"
        ] = 1
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("virtual controls evidence" in error for error in errors))

    def test_rejects_delivery_that_leaks_core_into_base_or_skips_source_gate(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds12Decision"]["deliveryDecision"] = "BASE_APK"
        invalid["n3ds12Decision"]["coreInBaseAllowed"] = True
        invalid["n3ds12Decision"][
            "publicCorrespondingSourceRequiredBeforeDistribution"
        ] = False
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("wrapper/core hardening contract" in error for error in errors))

    def test_rejects_unverified_or_unsafe_performance_profile_contract(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds11Decision"]["profilePolicyStatus"] = "PENDING"
        invalid["n3ds11Decision"]["profileResolutionFactors"]["PERFORMANCE"] = 10
        invalid["n3ds11Decision"]["existingSystemsModified"] = True
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("safe performance profile contract changed" in error
                            for error in errors))

    def test_rejects_unverified_or_unsafe_performance_measurement_contract(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds11Decision"]["measurementPolicyStatus"] = "PENDING"
        invalid["n3ds11Decision"]["regenerableCacheRoots"].append("NAND")
        invalid["n3ds11Decision"]["durableRootsExcludedFromCacheClear"] = []
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("aggregate measurement or safe cache contract changed" in error
                            for error in errors))

    def test_rejects_long_run_below_the_effective_speed_floor(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds11Decision"]["validation"]["n3ds11cAcceptedLongRuns"][
            "PERFORMANCE"
        ]["effectiveSpeedPercent"] = 94.9
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("PERFORMANCE long-run evidence changed" in error
                            for error in errors))

    def test_rejects_distribution_go_or_unqualified_renderer_fallback(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds00Decision"]["publicDistribution"] = "GO"
        invalid["rendererPolicy"]["openGlesFallback"] = "AUTOMATIC"
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("must not authorize public distribution" in error for error in errors))
        self.assertTrue(any("exact device/GPU/driver" in error for error in errors))

    def test_unlicensed_diagnostic_input_cannot_be_redistributed(self):
        invalid = deepcopy(self.manifest)
        diagnostic = next(
            item
            for item in invalid["referenceTestInputs"]
            if item["license"] == "NOASSERTION"
        )
        diagnostic["usage"] = "PUBLIC_TEST_ASSET"
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("must stay private and undistributed" in error for error in errors))

    def test_rejects_binary_acquisition_or_distribution_ready_notices(self):
        invalid = deepcopy(self.manifest)
        invalid["sourceAcquisition"]["binaryDownloadAllowed"] = True
        invalid["complianceArtifacts"]["distributionAuthority"] = True
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("reject downloaded binaries" in error for error in errors))
        self.assertTrue(any("must not authorize distribution" in error for error in errors))

    def test_rejects_premature_nintendo3ds_exposure_or_features(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds02Decision"]["userVisible"] = True
        invalid["n3ds02Decision"]["disabledUntilMeasured"] = ["REWIND"]
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("must remain internal" in error for error in errors))
        self.assertTrue(any("must remain disabled" in error for error in errors))

    def test_rejects_lost_real_core_frame_or_frontend_provenance(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds05Decision"]["coreSoftwareRendererUsed"] = True
        invalid["n3ds05Decision"]["validation"]["consecutiveRealCoreFrames"] = 0
        invalid["referenceImplementations"][0]["incorporatedSourcePaths"] = []
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("must not use software rendering" in error for error in errors))
        self.assertTrue(any("consecutive real-core frame" in error for error in errors))
        self.assertTrue(any("missing provenance" in error for error in errors))

    def test_rejects_unsafe_or_unverified_lifecycle_controller(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds06Decision"]["nativeSessionSingleThreaded"] = False
        invalid["n3ds06Decision"]["nativeSessionClosedBeforeSurfaceRelease"] = False
        invalid["n3ds06Decision"]["lateDriverCallbackUnmappedCodeProtected"] = False
        invalid["n3ds06Decision"]["validation"]["portraitToLandscapeRotationPassed"] = False
        invalid["n3ds06Decision"]["validation"]["pocoFatalSignalsObserved"] = 1
        invalid["n3ds06Decision"]["validation"]["a34FatalSignalsObserved"] = 1
        invalid["n3ds06Decision"]["validation"]["secureKeyguardCredentialChanged"] = True
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("one owner thread" in error for error in errors))
        self.assertTrue(any("before releasing its Surface" in error for error in errors))
        self.assertTrue(any("process-resident core mapping" in error for error in errors))
        self.assertTrue(any("portraitToLandscapeRotationPassed" in error for error in errors))
        self.assertTrue(any("Poco Mali lifecycle" in error for error in errors))
        self.assertTrue(any("Galaxy A34 lifecycle" in error for error in errors))
        self.assertTrue(any("keyguard credential" in error for error in errors))

    def test_shared_source_requires_an_explicit_scope_entry(self):
        patterns = ["app/src/**/Nintendo3Ds*.*"]
        shared_path = "app/src/main/cpp/bridge/frontend_callbacks.cpp"
        self.assertFalse(MODULE.matches_scope(shared_path, patterns))
        self.assertTrue(
            MODULE.matches_scope(shared_path, self.manifest["publicationPathPatterns"])
        )

    def test_rejects_cleanup_that_clears_private_nintendo3ds_data(self):
        cleanup_source = (
            PROJECT_ROOT / "scripts" / "clean-local-build-artifacts.ps1"
        ).read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_root = Path(temporary_directory)
            script_directory = temporary_root / "scripts"
            script_directory.mkdir()
            (script_directory / "clean-local-build-artifacts.ps1").write_text(
                cleanup_source + "\nadb shell pm clear com.mateussouza.emuorbit.n3ds.core.test\n",
                encoding="utf-8",
            )
            errors = MODULE.validate_stage_cleanup_boundary(temporary_root)

        self.assertTrue(any("forbidden device operation" in error for error in errors))

    def test_rejects_lost_rendered_accessibility_evidence(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds10Decision"]["accessibilityRenderedUiStatus"] = "PENDING"
        invalid["n3ds10Decision"]["accessibilityFontScale"] = 1.0
        invalid["n3ds10Decision"]["validation"][
            "accessibilityBengaliClippingRegressionPassed"
        ] = False

        errors = MODULE.validate_manifest(invalid)

        self.assertTrue(any("rendered accessibility gate" in error for error in errors))
        self.assertTrue(any("readiness test evidence" in error for error in errors))

    def test_rejects_unbounded_or_ds_filtered_nintendo3ds_audio(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds07Decision"]["nativeRingCapacityFrames"] = 0
        invalid["n3ds07Decision"]["nintendoDsFilterReused"] = True
        invalid["n3ds07Decision"]["audioTrackPlaybackStatus"] = "PENDING"
        invalid["n3ds07Decision"]["validation"]["streamingDroppedFrames"] = 1
        invalid["n3ds07Decision"]["validation"]["normalPlaybackDroppedFrames"] = 1
        invalid["n3ds07Decision"]["validation"]["audioTracksBalancedAfterControllerClose"] = False
        invalid["n3ds07Decision"]["validation"]["fastForward"]["allInputFramesAccounted"] = False
        invalid["n3ds07Decision"]["validation"]["prolongedRegression"]["privateOwnedContentCount"] = 2
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("fixed capacity" in error for error in errors))
        self.assertTrue(any("Nintendo DS audio filter" in error for error in errors))
        self.assertTrue(any("must not drop audio" in error for error in errors))
        self.assertTrue(any("AudioTrack playback" in error for error in errors))
        self.assertTrue(any("must not drop PCM" in error for error in errors))
        self.assertTrue(any("release every AudioTrack" in error for error in errors))
        self.assertTrue(any("every input PCM frame" in error for error in errors))
        self.assertTrue(any("three private contents" in error for error in errors))

    def test_rejects_unverified_or_leaking_nintendo3ds_input(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds08Decision"]["circlePadStatus"] = "PENDING"
        invalid["n3ds08Decision"]["cStickStatus"] = "PENDING"
        invalid["n3ds08Decision"]["touchStatus"] = "PENDING"
        invalid["n3ds08Decision"]["sensorStatus"] = "PENDING_REAL_ANDROID_SOURCE"
        invalid["n3ds08Decision"]["microphoneStatus"] = "PENDING"
        invalid["n3ds08Decision"]["touchSurfaceMappingStatus"] = "PENDING"
        invalid["n3ds08Decision"]["physicalControllerStatus"] = "PASSED"
        invalid["n3ds08Decision"]["lifecycleNeutralReset"] = False
        invalid["n3ds08Decision"]["existingSystemControlsModified"] = True
        invalid["n3ds08Decision"]["validation"]["analogStateCallbacks"] = 0
        invalid["n3ds08Decision"]["validation"]["rightAnalogStateCallbacks"] = 0
        invalid["n3ds08Decision"]["validation"]["pointerStateCallbacks"] = 0
        invalid["n3ds08Decision"]["validation"]["cStickX"] = 1
        invalid["n3ds08Decision"]["validation"]["pointerPressed"] = False
        invalid["n3ds08Decision"]["validation"]["sensorInputCallbacks"] = 0
        invalid["n3ds08Decision"]["validation"]["androidGyroscopeEvents"] = 0
        invalid["n3ds08Decision"]["validation"]["motionStoppedInBackground"] = False
        invalid["n3ds08Decision"]["validation"]["microphonePermissionDeniedPassed"] = False
        invalid["n3ds08Decision"]["validation"]["microphoneRealAudioRecordPassed"] = False
        invalid["n3ds08Decision"]["validation"]["microphoneReadCallbacks"] = 0
        invalid["n3ds08Decision"]["validation"]["microphoneNativeQueuedSamples"] = 48001
        invalid["n3ds08Decision"]["validation"]["microphoneStoppedInBackground"] = False
        invalid["n3ds08Decision"]["validation"]["lifecycleNeutralButtonMask"] = 1
        invalid["n3ds08Decision"]["validation"]["lifecycleNeutralPointerPressed"] = True
        invalid["n3ds08Decision"]["validation"]["validatedLayouts"] = ["DEFAULT"]
        invalid["n3ds08Decision"]["validation"]["letterboxOutsideTouchReleased"] = False
        invalid["n3ds08Decision"]["validation"]["syntheticGamepadButtonMask"] = 0
        invalid["n3ds08Decision"]["validation"]["moduleInstrumentedTestsPassed"] = 13
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("Circle Pad device evidence" in error for error in errors))
        self.assertTrue(any("C-stick and touch device evidence" in error for error in errors))
        self.assertTrue(any("reset controls to neutral" in error for error in errors))
        self.assertTrue(any("existing emulator systems" in error for error in errors))
        self.assertTrue(any("analogStateCallbacks" in error for error in errors))
        self.assertTrue(any("right-analog or pointer callback" in error for error in errors))
        self.assertTrue(any("C-stick axis evidence" in error for error in errors))
        self.assertTrue(any("absolute pointer evidence" in error for error in errors))
        self.assertTrue(any("real Android sensor evidence" in error for error in errors))
        self.assertTrue(any("60 Hz sensor callback" in error for error in errors))
        self.assertTrue(any("physical Android motion events" in error for error in errors))
        self.assertTrue(any("motion lifecycle" in error for error in errors))
        self.assertTrue(any("Android microphone evidence" in error for error in errors))
        self.assertTrue(any("silent working frontend" in error for error in errors))
        self.assertTrue(any("real Android PCM delivery" in error for error in errors))
        self.assertTrue(any("microphone-v1 callback" in error for error in errors))
        self.assertTrue(any("queues must remain bounded" in error for error in errors))
        self.assertTrue(any("microphone lifecycle" in error for error in errors))
        self.assertTrue(any("neutral-state evidence" in error for error in errors))
        self.assertTrue(any("multi-layout and orientation touch" in error for error in errors))
        self.assertTrue(any("physical controller hardware" in error for error in errors))
        self.assertTrue(any("multi-layout geometry" in error for error in errors))
        self.assertTrue(any("aspect-fit touch" in error for error in errors))
        self.assertTrue(any("synthetic Android gamepad" in error for error in errors))
        self.assertTrue(any("final Android instrumented regression" in error for error in errors))

    def test_rejects_lost_interactive_or_process_isolation_evidence(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds09Decision"]["baseSuiteProcessIsolationPolicy"] = "ONE_LONG_PID"
        validation = invalid["n3ds09Decision"]["validation"]
        validation["isolatedBaseSuitePassed"] = 15
        validation["interactiveBootAndInputVerifiedCount"] = 2
        validation["interactiveContentResults"][0]["inputEvents"] = 0
        errors = MODULE.validate_manifest(invalid)
        self.assertTrue(any("process isolation policy" in error for error in errors))
        self.assertTrue(any("interactive physical evidence" in error for error in errors))
        self.assertTrue(any("frame/input evidence" in error for error in errors))

    def test_rejects_inflated_or_non_strict_playable_save_progress(self):
        invalid = deepcopy(self.manifest)
        validation = invalid["n3ds09Decision"]["validation"]
        validation["playableProgressSaveVerifiedCount"] = 5
        validation["strictPlayableSaveRegressions"][0][
            "existingTitleRootMutated"
        ] = False
        validation["strictPlayableSaveRegressions"][2][
            "visualPlayableRaceCompletionObserved"
        ] = False
        validation["strictPlayableSaveRegressions"][3][
            "visualPlayableProgressObserved"
        ] = False
        validation["latestPrivateContentBatch"]["nonCreditedContentSaveCredit"] = 1
        validation["privatePlayableSaveOwnerAcceptance"][
            "claimsFiveStrictSaveTestsPassed"
        ] = True
        validation["interactiveDeadlineBoundaryCounterexample"]["saveCredit"] = 1

        errors = MODULE.validate_manifest(invalid)

        self.assertTrue(any("playable-save progress" in error for error in errors))
        self.assertTrue(any("strict, isolated and crash-free" in error for error in errors))
        self.assertTrue(any("visual playable-progress" in error for error in errors))
        self.assertTrue(any("latest private-content batch" in error for error in errors))
        self.assertTrue(any("absolute-deadline regression" in error for error in errors))

    def test_rejects_closed_persistence_gate_in_external_queue(self):
        invalid = deepcopy(self.manifest)
        invalid["executionOptimization"]["externalGateQueue"].append(
            "N3DS-09_FIVE_PRIVATE_CONTENT_SAVES"
        )

        errors = MODULE.validate_manifest(invalid)

        self.assertTrue(any("external gate queue" in error for error in errors))

    def test_rejects_guessed_mii_requirement_or_lost_readiness_provenance(self):
        invalid = deepcopy(self.manifest)
        invalid["n3ds10Decision"]["miiRequirementPolicy"] = "GUESS_FROM_TITLE"
        invalid["n3ds10Decision"]["miiPickerCreatedOnlyAfterNativeSessionClose"] = False
        invalid["n3ds10Decision"]["localizedReadinessPresentationStatus"] = "PENDING"
        invalid["n3ds10Decision"]["readinessPresentationModelConsumedByHost"] = False
        invalid["n3ds10Decision"]["readinessLocaleTags"] = ["pt-BR"]
        invalid["n3ds10Decision"]["isolatedReadinessDialogStatus"] = "PENDING"
        invalid["n3ds10Decision"]["readinessDialogPolicy"] = "MULTIPLE_ACTIVE_DIALOGS"
        invalid["n3ds10Decision"]["isolatedReadinessActionCoordinatorStatus"] = "PENDING"
        invalid["n3ds10Decision"]["readinessExternalIntentActions"] = []
        invalid["n3ds10Decision"]["asyncMiiImportWorkflowStatus"] = "PENDING"
        invalid["n3ds10Decision"]["miiImportRunsOffUiThread"] = False
        invalid["n3ds10Decision"]["miiRecoveryRepreflightStatus"] = "PENDING"
        invalid["n3ds10Decision"]["miiOptionalPausedControllerReused"] = False
        invalid["n3ds10Decision"]["activityResultReadinessHostStatus"] = "PENDING"
        invalid["n3ds10Decision"]["activityResultRegisteredBeforeStarted"] = False
        invalid["n3ds10Decision"]["experienceSettingsFoundationStatus"] = "PENDING"
        invalid["n3ds10Decision"]["experienceSettingsDefaultMicrophoneEnabled"] = True
        invalid["n3ds10Decision"]["experienceSettingsDialogStatus"] = "PENDING"
        invalid["n3ds10Decision"]["experienceSettingsDialogControlsLabelled"] = False
        invalid["n3ds10Decision"]["isolatedProductHostActivityStatus"] = "PENDING"
        invalid["n3ds10Decision"]["productActivityExported"] = True
        invalid["n3ds10Decision"]["validation"]["readinessJvmTests"] = 0
        reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-launch-readiness-policy"
        )
        reference["source"] = "unknown"
        dialog_reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-readiness-dialog-lifecycle"
        )
        dialog_reference["sourcePaths"] = ["unknown"]
        action_reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-readiness-action-effects"
        )
        action_reference["source"] = "unknown"
        async_mii_reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-async-saf-workflow"
        )
        async_mii_reference["sourcePaths"] = ["unknown"]
        post_import_reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-post-import-refresh"
        )
        post_import_reference["source"] = "unknown"
        activity_result_reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-activity-result-saf-host"
        )
        activity_result_reference["sourcePaths"] = ["unknown"]
        settings_reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-global-sparse-game-settings"
        )
        settings_reference["sourcePaths"] = ["unknown"]
        settings_dialog_reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-settings-dialog-lifecycle"
        )
        settings_dialog_reference["sourcePaths"] = ["unknown"]
        product_host_reference = next(
            item
            for item in invalid["referenceImplementations"]
            if item["id"] == "emuorbit-existing-product-host-lifecycle-and-dialog-style"
        )
        product_host_reference["sourcePaths"] = ["unknown"]

        errors = MODULE.validate_manifest(invalid)

        self.assertTrue(any("must not guess" in error for error in errors))
        self.assertTrue(any("host/readiness/Mii ordering" in error for error in errors))
        self.assertTrue(any("localized readiness presentation" in error for error in errors))
        self.assertTrue(any("isolated readiness dialog" in error for error in errors))
        self.assertTrue(any("isolated readiness action coordinator" in error for error in errors))
        self.assertTrue(any("asynchronous Mii import workflow" in error for error in errors))
        self.assertTrue(any("Mii recovery re-preflight" in error for error in errors))
        self.assertTrue(any("Activity Result host and style boundary" in error for error in errors))
        self.assertTrue(any("experience settings foundation" in error for error in errors))
        self.assertTrue(any("experience settings dialog" in error for error in errors))
        self.assertTrue(any("hidden product host integration" in error for error in errors))
        self.assertTrue(any("host presentation contract" in error for error in errors))
        self.assertTrue(any("localized readiness resource coverage" in error for error in errors))
        self.assertTrue(any("readiness dialog lifecycle" in error for error in errors))
        self.assertTrue(any("readiness action effects" in error for error in errors))
        self.assertTrue(any("asynchronous Mii import safety" in error for error in errors))
        self.assertTrue(any("Mii recovery ordering" in error for error in errors))
        self.assertTrue(any("Activity Result routing" in error for error in errors))
        self.assertTrue(any("settings layering, privacy" in error for error in errors))
        self.assertTrue(any("settings dialog scope, localization" in error for error in errors))
        self.assertTrue(any("hidden entry or deferred delivery boundary" in error for error in errors))
        self.assertTrue(any("readiness test evidence" in error for error in errors))
        self.assertTrue(any("readiness provenance" in error for error in errors))
        self.assertTrue(any("readiness dialog provenance" in error for error in errors))
        self.assertTrue(any("readiness action provenance" in error for error in errors))
        self.assertTrue(any("asynchronous SAF provenance" in error for error in errors))
        self.assertTrue(any("post-import refresh provenance" in error for error in errors))
        self.assertTrue(any("Activity Result SAF host provenance" in error for error in errors))
        self.assertTrue(any("internal settings provenance" in error for error in errors))
        self.assertTrue(any("internal settings dialog provenance" in error for error in errors))
        self.assertTrue(any("internal product host provenance" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
