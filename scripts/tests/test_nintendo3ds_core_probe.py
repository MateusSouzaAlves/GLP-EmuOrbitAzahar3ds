import json
from pathlib import Path
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class Nintendo3dsCoreProbeTest(unittest.TestCase):
    def test_probe_covers_minimum_lifecycle_contract(self):
        source = (
            PROJECT_ROOT / "nintendo3dscore" / "harness" / "n3ds_core_probe.cpp"
        ).read_text(encoding="utf-8")
        for symbol in (
            "retro_api_version",
            "retro_get_system_info",
            "retro_set_environment",
            "retro_init",
            "retro_get_system_av_info",
            "retro_deinit",
            "retro_load_game",
            "retro_run",
            "retro_unload_game",
            "retro_serialize_size",
            "retro_serialize",
            "retro_unserialize",
        ):
            self.assertIn(f'"{symbol}"', source)
        self.assertIn("PROBE_RESULT=PASS", source)
        self.assertIn('"citra_graphics_api"', source)
        self.assertIn("variable->value = g_renderer.c_str()", source)
        self.assertIn("RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER", source)
        self.assertIn("RETRO_ENVIRONMENT_SET_HW_RENDER", source)
        self.assertIn("RETRO_HW_CONTEXT_OPENGLES3", source)
        self.assertIn("RETRO_HW_CONTEXT_VULKAN", source)
        self.assertIn("RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE", source)
        self.assertIn("RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE", source)
        self.assertIn("RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION", source)
        self.assertIn("PROBE_VULKAN_IMAGE_UPDATES", source)
        self.assertIn("g_hw_render.context_reset()", source)
        self.assertIn("g_hw_render.context_destroy()", source)
        self.assertIn("data == RETRO_HW_FRAME_BUFFER_VALID", source)
        self.assertIn("data == nullptr || width == 0 || height == 0", source)
        self.assertIn("g_hardware_context_destroyed", source)
        self.assertIn("!g_swap_failed", source)
        self.assertIn("RETRO_ENVIRONMENT_GET_VFS_INTERFACE", source)
        self.assertIn("info->required_interface_version = 2", source)
        self.assertIn("RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE", source)
        self.assertIn("RETRO_ENVIRONMENT_GET_MICROPHONE_INTERFACE", source)
        self.assertIn("g_input_state_calls", source)
        self.assertIn("std::setvbuf(stdout, nullptr, _IONBF, 0)", source)
        self.assertIn("PROBE_FRAME_CHECKPOINT", source)
        self.assertIn("PROBE_STATE_SIZE", source)
        self.assertIn("PROBE_STATE_PREPARE_MICROS", source)
        self.assertIn("PROBE_STATE_SERIALIZE_MICROS", source)
        self.assertIn("PROBE_STATE_UNSERIALIZE_MICROS", source)
        self.assertIn("PROBE_STATE_POST_RESTORE_FRAME", source)

    def test_probe_build_is_android_arm64_and_16k_aligned(self):
        build_script = (
            PROJECT_ROOT / "scripts" / "build-nintendo3ds-core-probe.ps1"
        ).read_text(encoding="utf-8")
        self.assertIn("--target=aarch64-linux-android21", build_script)
        self.assertIn("'-lEGL'", build_script)
        self.assertIn("'-lGLESv3'", build_script)
        self.assertIn("-Wl,-z,max-page-size=16384", build_script)
        self.assertIn("-Wl,-z,common-page-size=16384", build_script)
        self.assertIn("externals\\libretro-common", build_script)
        run_script = (
            PROJECT_ROOT / "scripts" / "run-nintendo3ds-core-probe.ps1"
        ).read_text(encoding="utf-8")
        self.assertIn("'Software', 'OpenGLES', 'Vulkan'", run_script)
        self.assertIn("$MeasureState", run_script)
        self.assertIn("$StateMaximumBytes", run_script)
        self.assertIn("PROBE_STATE_SERIALIZE_OK=1", run_script)

    def test_reference_build_excludes_legacy_tls_and_fixes_timestamp(self):
        build_script = (
            PROJECT_ROOT / "scripts" / "build-nintendo3ds-reference-core.ps1"
        ).read_text(encoding="utf-8")
        patch = (
            PROJECT_ROOT
            / "nintendo3dscore"
            / "patches"
            / "azahar-2126.0-offline-no-legacy-tls.patch"
        ).read_text(encoding="utf-8")
        vfs_patch = (
            PROJECT_ROOT
            / "nintendo3dscore"
            / "patches"
            / "azahar-2126.0-libretro-vfs-seek-semantics.patch"
        ).read_text(encoding="utf-8")
        worktree_patch = (
            PROJECT_ROOT
            / "nintendo3dscore"
            / "patches"
            / "azahar-build-worktree-hooks.patch"
        ).read_text(encoding="utf-8")
        self.assertIn("SOURCE_DATE_EPOCH", build_script)
        self.assertIn("$manifest.referenceBuild", build_script)
        self.assertIn("$expectedRevision.commit", build_script)
        self.assertIn("$expectedRevision.recursiveSubmoduleCount", build_script)
        self.assertIn("function Test-GitPatch", build_script)
        self.assertIn("$ErrorActionPreference = 'SilentlyContinue'", build_script)
        self.assertIn("Pkg\\.Revision", build_script)
        self.assertIn("cmake version $($buildSpec.cmake)", build_script)
        self.assertIn("$buildSpec.ninja", build_script)
        self.assertIn("-DENABLE_SOFTWARE_RENDERER=ON", build_script)
        self.assertIn("-DENABLE_HTTPS=OFF", build_script)
        self.assertIn("libressl|libssl\\.a|libcrypto\\.a", build_script)
        self.assertIn("generate-nintendo3ds-compliance.py", build_script)
        self.assertIn("$buildSpec.strippedSha256", build_script)
        self.assertIn("$UpdateCompatibilityCandidate", build_script)
        self.assertIn("--candidate", build_script)
        self.assertIn("CryptoPP::AutoSeededRandomPool", patch)
        self.assertIn("HTTPS is unavailable in this build", patch)
        self.assertIn("FSEEK_FAILED(result)", vfs_patch)
        self.assertIn("FSEEK_FAILED(FSEEK(m_file, off, origin))", vfs_patch)
        self.assertIn("IS_DIRECTORY ${PROJECT_SOURCE_DIR}/.git", worktree_patch)
        self.assertIn('EXISTS "${CMAKE_SOURCE_DIR}/.git"', worktree_patch)
        self.assertIn('-    elseif (EXISTS "${CMAKE_SOURCE_DIR}/.git/objects")', worktree_patch)
        self.assertIn('+    elseif (EXISTS "${CMAKE_SOURCE_DIR}/.git")', worktree_patch)
        self.assertIn('"${GIT_EXECUTABLE}" rev-parse HEAD', worktree_patch)
        self.assertIn("OUTPUT_STRIP_TRAILING_WHITESPACE", worktree_patch)

    def test_update_candidate_is_pinned_without_promoting_the_baseline(self):
        manifest = json.loads(
            (PROJECT_ROOT / "config" / "nintendo3ds-source-scope.json").read_text(
                encoding="utf-8"
            )
        )
        candidate = manifest["n3ds09Decision"]["updateCompatibilityCandidate"]
        baseline = next(
            item for item in manifest["distributedUpstreams"] if item["id"] == "azahar"
        )
        self.assertEqual("2126.0", baseline["tag"])
        self.assertEqual("2126.1", candidate["tag"])
        self.assertEqual(40, len(candidate["commit"]))
        self.assertEqual(52, candidate["recursiveSubmoduleCount"])
        self.assertEqual("PASSED", candidate["status"])
        self.assertEqual(35543960, candidate["strippedBytes"])
        self.assertEqual(
            "bcfcb267ae42890f8d5e3a7414fc935c63576fb60dbde9ec1e3fb4dca2d799cf",
            candidate["strippedSha256"],
        )
        self.assertTrue(candidate["buildRevisionEmbedded"])
        self.assertEqual("26e608f", candidate["reportedCoreVersion"])
        self.assertTrue(candidate["worktreeRevisionFallbackApplied"])
        self.assertEqual(16384, candidate["linkerPageSizeBytes"])
        self.assertFalse(candidate["legacyTlsLinked"])
        self.assertFalse(candidate["binaryCommitted"])
        self.assertFalse(candidate["baselinePromoted"])
        self.assertFalse(candidate["saveStateCrossVersionSupported"])

    def test_update_gate_requires_a_fresh_process_and_exact_revision(self):
        script = (
            PROJECT_ROOT / "scripts" / "test-nintendo3ds-core-update.ps1"
        ).read_text(encoding="utf-8")
        self.assertIn("shell am force-stop", script)
        self.assertIn("preservesExactSnapshotsAcrossAzaharCoreUpdateAndRollback", script)
        self.assertIn("n3dsUpdateCoreRevision", script)
        self.assertIn("N3DS_CORE_UPDATE_GATE=PASS", script)
        self.assertIn("FATAL EXCEPTION|Fatal signal|VK_ERROR_DEVICE_LOST", script)

    def test_state_measurement_is_recorded_without_enabling_the_feature(self):
        manifest = json.loads(
            (PROJECT_ROOT / "config" / "nintendo3ds-source-scope.json").read_text(
                encoding="utf-8"
            )
        )
        decision = manifest["n3ds09Decision"]
        measurement = decision["stateMeasurement"]
        self.assertEqual("MEASURED_DISABLED", measurement["status"])
        self.assertEqual(6, measurement["roundTripsPassed"])
        self.assertEqual(6, measurement["postRestoreFramesPassed"])
        self.assertLessEqual(measurement["maximumStateBytes"], 64 * 1024 * 1024)
        self.assertEqual(
            "UNSUPPORTED_VERSION_LOCKED_BY_UPSTREAM",
            measurement["crossRevisionCompatibility"],
        )
        self.assertFalse(measurement["crossRevisionLoadAttemptedWithValidBuilds"])
        self.assertFalse(measurement["saveStatesEnabled"])
        self.assertFalse(measurement["rewindEnabled"])
        self.assertFalse(decision["saveStatesEnabled"])
        self.assertFalse(decision["rewindEnabled"])

    def test_private_3ds_content_is_ignored_and_rejected_from_artifacts(self):
        ignore = (PROJECT_ROOT / ".gitignore").read_text(encoding="utf-8")
        verify_ci = (PROJECT_ROOT / "scripts" / "verify-ci.ps1").read_text(
            encoding="utf-8"
        )
        for extension in ("3ds", "3dsx", "z3dsx", "cci", "zcci", "cxi", "zcxi"):
            self.assertIn(f"*.{extension}", ignore)
            self.assertIn(extension, verify_ci)

    def test_reference_build_and_probe_evidence_are_recorded(self):
        manifest = json.loads(
            (PROJECT_ROOT / "config" / "nintendo3ds-source-scope.json").read_text(
                encoding="utf-8"
            )
        )
        build = manifest["referenceBuild"]
        probe = manifest["referenceProbe"]
        self.assertEqual("PASSED", build["status"])
        self.assertEqual(16384, build["linkerPageSizeBytes"])
        self.assertFalse(build["binaryCommitted"])
        self.assertFalse(build["legacyTlsLinked"])
        self.assertEqual(
            "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c",
            build["strippedSha256"],
        )
        self.assertEqual("PASSED", probe["status"])
        self.assertEqual(83752, probe["probeBytes"])
        self.assertEqual(
            "1178032494dd2f462ebf0924b2a56f9969e1e84e949e33eab70c3e5ef742ca67",
            probe["probeSha256"],
        )
        self.assertEqual(
            "ABI_INIT_LOAD_RUN_UNLOAD_DEINIT_SOFTWARE_OPENGLES_VULKAN_VFS_INPUT_SENSOR_MICROPHONE_AUDIO_PRIVATE_CONTENT_LONG_RUN_STATE_MEASUREMENT",
            probe["scope"],
        )
        self.assertTrue(probe["contentLoaded"])
        self.assertEqual(60, probe["executedVideoFrames"])
        self.assertEqual("OpenGL ES 3.2", probe["openGlesVersion"])
        self.assertTrue(probe["openGlesContextReset"])
        self.assertEqual(60, probe["openGlesHardwareVideoFrames"])
        self.assertTrue(probe["openGlesContextDestroy"])
        self.assertEqual(2, probe["vfsInterfaceVersion"])
        self.assertTrue(probe["vfsContentOpened"])
        self.assertGreater(probe["vfsContentBytesRead"], 0)
        self.assertEqual(2, probe["validatedOpenHomebrewExecutables"])
        self.assertGreater(probe["inputStateCalls"], 0)
        self.assertEqual(4, probe["sensorStateCallsIncludingShutdown"])
        self.assertEqual(3, probe["validatedPrivateCommercialDumps"])
        self.assertEqual(3, probe["privateCommercialOpenGlesSmokeCount"])
        self.assertEqual(600, probe["softwareLongRunVideoFrames"])
        self.assertGreater(probe["softwareLongRunAudioFrames"], 0)
        self.assertGreater(probe["softwareLongRunSensorInputCalls"], 0)
        self.assertEqual("FAILED_VENDOR_DRIVER_SIGSEGV", probe["openGlesExtendedRunStatus"])
        self.assertEqual("PASSED", probe["vulkanStatus"])
        self.assertEqual(2, probe["vulkanNegotiationInterfaceVersion"])
        self.assertEqual(5, probe["vulkanRenderInterfaceVersion"])
        self.assertEqual(600, probe["vulkanLongRunVideoFrames"])
        self.assertEqual(600, probe["vulkanLongRunImageUpdates"])
        self.assertGreater(probe["vulkanLongRunAudioFrames"], 0)
        self.assertGreater(probe["vulkanLongRunSensorInputCalls"], 0)
        self.assertGreater(probe["sensorInputCalls"], 0)
        self.assertTrue(probe["microphoneInterfaceImplemented"])
        self.assertTrue(probe["microphoneInterfaceRequested"])
        self.assertEqual(1, probe["microphoneInterfaceVersion"])
        self.assertEqual(1, probe["microphoneOpenCalls"])
        self.assertGreater(probe["microphoneReadCalls"], 0)
        self.assertEqual(48000, probe["microphoneFrontendSampleRateHz"])
        self.assertEqual(32728, probe["microphoneEmulatedSampleRateHz"])
        self.assertEqual("VULKAN", probe["microphoneRenderer"])
        self.assertEqual(240, probe["microphoneVideoFrames"])
        self.assertEqual(240, probe["microphoneVulkanImageUpdates"])
        self.assertTrue(probe["microphoneTeardownClean"])
        self.assertEqual(
            "NONE_FOR_N3DS_00_IMPLEMENTATION_ENTRY",
            probe["remainingGate"],
        )
        self.assertEqual("GO", manifest["n3ds00Decision"]["conformity"])
        self.assertEqual("GO", manifest["n3ds00Decision"]["technical"])
        self.assertEqual("VULKAN", manifest["rendererPolicy"]["primary"])
        self.assertEqual("DIAGNOSTICS_ONLY", manifest["rendererPolicy"]["software"])
        self.assertEqual(3, len(manifest["referenceTestInputs"]))


if __name__ == "__main__":
    unittest.main()
