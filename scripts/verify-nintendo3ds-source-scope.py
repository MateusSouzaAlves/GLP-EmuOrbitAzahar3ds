#!/usr/bin/env python3
"""Fail when Nintendo 3DS implementation sources escape the public-source scope."""

from __future__ import annotations

import argparse
from decimal import Decimal, ROUND_HALF_UP
import fnmatch
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = PROJECT_ROOT / "config" / "nintendo3ds-source-scope.json"
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SOURCE_SUFFIXES = {
    ".aidl",
    ".c",
    ".cc",
    ".cmake",
    ".cpp",
    ".frag",
    ".glsl",
    ".gradle",
    ".h",
    ".hpp",
    ".java",
    ".json",
    ".kt",
    ".kts",
    ".map",
    ".properties",
    ".png",
    ".ps1",
    ".py",
    ".sh",
    ".toml",
    ".txt",
    ".vert",
    ".webp",
    ".xml",
}


def load_manifest(path: Path = DEFAULT_MANIFEST) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_manifest(manifest: dict) -> list[str]:
    errors: list[str] = []
    if manifest.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    if manifest.get("scopePolicy") != "ALL_CODE_LINKED_TO_NINTENDO_3DS":
        errors.append("scopePolicy must cover all code linked to Nintendo 3DS")

    progress = manifest.get("progressAccounting", {})
    if progress.get("policy") != (
        "EQUAL_MILESTONE_WEIGHT_WITH_VERIFIED_ACCEPTANCE_UNITS_FOR_OPEN_MILESTONES"
    ):
        errors.append("Nintendo 3DS progress must use equal milestone weights")
    milestone_count = progress.get("milestoneCount")
    completed_milestones = progress.get("completedMilestones")
    if milestone_count != 15 or completed_milestones != 13:
        errors.append("strict Nintendo 3DS progress must record the physical lifecycle closure")
    if progress.get("strictCompletedPercent") != 86.7:
        errors.append("strict Nintendo 3DS completion must remain 86.7 percent")
    expected_units = {
        "N3DS-08": (5, 6),
    }
    units = progress.get("openMilestoneAcceptanceUnits", {})
    observed_units = {
        name: (value.get("accepted"), value.get("total"))
        for name, value in units.items()
        if isinstance(value, dict)
    }
    if observed_units != expected_units:
        errors.append("Nintendo 3DS verified progress acceptance units changed")
    else:
        weighted = Decimal(completed_milestones)
        for accepted, total in expected_units.values():
            weighted += Decimal(accepted) / Decimal(total)
        verified_percent = (Decimal(100) * weighted / Decimal(milestone_count)).quantize(
            Decimal("0.1"), rounding=ROUND_HALF_UP
        )
        if Decimal(str(progress.get("verifiedExecutionPercent"))) != verified_percent:
            errors.append("Nintendo 3DS verified execution percent does not match its formula")
    if progress.get("releaseGatePolicy") != (
        "ONLY_FULLY_ACCEPTED_MILESTONES_COUNT_TOWARD_STRICT_COMPLETION"
    ):
        errors.append("partial Nintendo 3DS work must not close strict release gates")

    optimization = manifest.get("executionOptimization", {})
    if optimization.get("mode") != "DEPENDENCY_AWARE_VERTICAL_SLICES":
        errors.append("Nintendo 3DS execution must remain dependency-aware")
    if optimization.get("protectedExistingSystems") != ["GB", "GBC", "GBA", "NDS"]:
        errors.append("existing emulator systems must remain protected during Nintendo 3DS work")
    if optimization.get("commitPolicy") != (
        "ONE_COHERENT_VERIFIED_COMMIT_PER_SUBSTEP_THEN_PUSH_REMOTE"
    ):
        errors.append("Nintendo 3DS commits must remain coherent substep checkpoints")
    critical_path = optimization.get("criticalPath", [])
    if critical_path != [
        "N3DS-08_PHYSICAL_GAMEPAD_GATE",
        "N3DS-14_CONTROLLED_PUBLICATION_AFTER_OWNER_AUTHORIZATION",
    ]:
        errors.append("Nintendo 3DS final hardware and release critical path changed")
    if optimization.get("externalGatePolicy") != (
            "DO_NOT_BLOCK_INDEPENDENT_LOCAL_WORK_BUT_DO_NOT_CLAIM_STABLE_SUPPORT_"
            "WITHOUT_REQUIRED_PHYSICAL_EVIDENCE"):
        errors.append("Nintendo 3DS external gates must not block independent local work")
    if optimization.get("externalGateQueue") != [
            "N3DS-08_PHYSICAL_GAMEPAD"]:
        errors.append("Nintendo 3DS external gate queue must contain only unresolved inputs")

    publication = manifest.get("publication", {})
    if publication.get("createRepositoryNow") is not True:
        errors.append("the authorized Nintendo 3DS source publication must remain recorded")
    if publication.get("requiredBeforeDistribution") is not True:
        errors.append("public source must be required before distribution")
    if publication.get("publicRepository") != (
            "https://github.com/MateusSouzaAlves/GLP-EmuOrbitAzahar3ds"):
        errors.append("the public Nintendo 3DS corresponding-source URL changed")
    if publication.get("publicSourceCommit") != (
            "85ff5ce78e439e5d9fae84dd45165f061a9f18ed"):
        errors.append("the public Nintendo 3DS source package must remain pinned")
    if publication.get("publicSourceStatus") != "COMPLETE_FROZEN_BY_OWNER" \
            or publication.get("publicSourceFrozenOn") != "2026-09-11" \
            or publication.get("publicSourceChangesAllowed") is not False \
            or publication.get("publicSourceDiscrepancyBlocksImplementation") is not False:
        errors.append("public source must remain complete and frozen by owner decision")
    if publication.get("localCorrespondingSourceRepository") is not None:
        errors.append("the removed local Nintendo 3DS source copy must not be referenced")
    if publication.get("localCopyRemovedAfterRemoteVerification") is not True:
        errors.append("local source removal must remain backed by remote verification")
    if publication.get("runtimeBundlePolicy") != (
            "COMPILED_FEATURE_PLUS_MINIMUM_LICENSE_NOTICE_NO_SOURCE_ARCHIVE_OR_SBOM"):
        errors.append("the app bundle must retain only the minimum runtime license notice")

    decision = manifest.get("n3ds00Decision", {})
    if decision.get("conformity") != "GO" or decision.get("technical") != "GO":
        errors.append("N3DS-00 requires separate conformity and technical GO decisions")
    if decision.get("scope") != "IMPLEMENTATION_ENTRY_ONLY":
        errors.append("N3DS-00 GO must be limited to implementation entry")
    if not str(decision.get("publicDistribution", "")).startswith("BLOCKED_"):
        errors.append("N3DS-00 must not authorize public distribution")

    acquisition = manifest.get("sourceAcquisition", {})
    if acquisition.get("sourceOnly") is not True:
        errors.append("Nintendo 3DS acquisition must be source-only")
    if acquisition.get("binaryDownloadAllowed") is not False:
        errors.append("Nintendo 3DS acquisition must reject downloaded binaries")
    if acquisition.get("existingCheckoutPolicy") != (
        "REJECT_URL_COMMIT_OR_DIRTY_TREE_MISMATCH_WITHOUT_OVERWRITE"
    ):
        errors.append("Nintendo 3DS acquisition must not overwrite divergent checkouts")
    if acquisition.get("status") != "PASSED":
        errors.append("Nintendo 3DS source acquisition must remain verified")

    compliance = manifest.get("complianceArtifacts", {})
    if compliance.get("sbomFormat") != "CycloneDX 1.6":
        errors.append("Nintendo 3DS SBOM must use CycloneDX 1.6")
    if compliance.get("distributionAuthority") is not False:
        errors.append("baseline compliance artifacts must not authorize distribution")
    if compliance.get("finalLicenseTextsRequiredBeforeDistribution") is not True:
        errors.append("complete license texts must remain a distribution gate")
    if compliance.get("status") != "PASSED":
        errors.append("Nintendo 3DS compliance artifacts must remain verified")

    n3ds01 = manifest.get("n3ds01Decision", {})
    if n3ds01.get("status") != "DONE":
        errors.append("N3DS-01 reproducible acquisition must remain complete")
    if n3ds01.get("downloadedCoreBinary") is not False:
        errors.append("N3DS-01 must not depend on a downloaded core binary")

    n3ds02 = manifest.get("n3ds02Decision", {})
    if n3ds02.get("status") != "DONE":
        errors.append("N3DS-02 additive system registry must remain complete")
    if n3ds02.get("persistentKey") != "N3DS":
        errors.append("Nintendo 3DS must retain the stable N3DS persistence key")
    if n3ds02.get("userVisible") is not False:
        errors.append("Nintendo 3DS must remain internal until later release gates")
    if n3ds02.get("roomSchemaChanged") is not False:
        errors.append("N3DS-02 must not claim a Room schema change")
    disabled = set(n3ds02.get("disabledUntilMeasured", []))
    if disabled != {"SAVE_STATE", "REWIND", "CHEATS"}:
        errors.append("unverified Nintendo 3DS features must remain disabled")

    n3ds05 = manifest.get("n3ds05Decision", {})
    if n3ds05.get("status") != "DONE" \
            or n3ds05.get("completedOn") != "2026-09-11" \
            or n3ds05.get("remainingGate") != "NONE":
        errors.append("N3DS-05 must be closed after physical Mali acceptance")
    if n3ds05.get("foundationStatus") != "PASSED":
        errors.append("N3DS-05 Vulkan host foundation must remain verified")
    if n3ds05.get("firstCoreFrameStatus") != "PASSED":
        errors.append("N3DS-05 must retain verified real-core frame evidence")
    if n3ds05.get("coreContentLoaded") is not True:
        errors.append("N3DS-05 real-core evidence must include content loading")
    if n3ds05.get("coreSoftwareRendererUsed") is not False:
        errors.append("N3DS-05 real-core evidence must not use software rendering")
    n3ds05_validation = n3ds05.get("validation", {})
    if n3ds05_validation.get("vulkanNegotiationInterfaceVersion") != 2:
        errors.append("N3DS-05 must retain Vulkan negotiation interface v2")
    if n3ds05_validation.get("vulkanRenderInterfaceVersion") != 5:
        errors.append("N3DS-05 must retain Vulkan render interface v5")
    if n3ds05_validation.get("consecutiveRealCoreFrames", 0) < 2:
        errors.append("N3DS-05 must retain consecutive real-core frame evidence")
    if n3ds05_validation.get("vfsContentOpened") is not True:
        errors.append("N3DS-05 real content must remain verified through VFS")
    if n3ds05_validation.get("maliFirebaseMatrixId") != "matrix-3uf2qqsudx2rn" \
            or n3ds05_validation.get("maliDevice") != (
                "GALAXY_A34_5G_A34X_ANDROID_16_PHYSICAL") \
            or n3ds05_validation.get("maliVulkanDevice") != "Mali-G68 MC4" \
            or n3ds05_validation.get("maliVulkanApiVersion") != "1.3.278" \
            or n3ds05_validation.get("maliSurfaceFormat") != 37 \
            or n3ds05_validation.get("maliSwapchainImageCount") != 7 \
            or n3ds05_validation.get("maliConsecutiveDiagnosticFrames") != 2 \
            or n3ds05_validation.get("maliRepeatedCreatePresentDestroyCycles") != 3 \
            or n3ds05_validation.get("maliConsecutiveRealCoreFrames") != 60 \
            or n3ds05_validation.get("maliReportedCoreFrame") != "800x960" \
            or n3ds05_validation.get("maliVfsBytesReadAfter60Frames") != 773762 \
            or n3ds05_validation.get("maliFocalTestsPassed") != 3 \
            or n3ds05_validation.get("maliFocalTestsTotal") != 3 \
            or n3ds05_validation.get("maliTestElapsedSeconds") != 9 \
            or n3ds05_validation.get("maliPaidServiceUsed") is not False:
        errors.append("N3DS-05 physical Mali evidence changed")

    n3ds06 = manifest.get("n3ds06Decision", {})
    if n3ds06.get("status") != "DONE" \
            or n3ds06.get("completedOn") != "2026-09-12" \
            or n3ds06.get("remainingGates") != []:
        errors.append("N3DS-06 lifecycle matrix must remain closed")
    if n3ds06.get("ownerThreadControllerStatus") != "PASSED":
        errors.append("N3DS-06 owner-thread controller must remain verified")
    if n3ds06.get("nativeSessionSingleThreaded") is not True:
        errors.append("N3DS-06 must keep every native session operation on one owner thread")
    if n3ds06.get("nativeSessionClosedBeforeSurfaceRelease") is not True:
        errors.append("N3DS-06 must close the native session before releasing its Surface")
    if n3ds06.get("staleSurfaceCallbackProtected") is not True:
        errors.append("N3DS-06 must protect a new Surface from stale destroy callbacks")
    if n3ds06.get("coreLibraryMappingPolicy") != (
            "VALIDATED_SINGLE_PATH_PROCESS_RESIDENT_NO_DLOPEN_REFCOUNT_GROWTH") \
            or n3ds06.get("lateDriverCallbackUnmappedCodeProtected") is not True \
            or n3ds06.get("residentCorePathReplacementAllowed") is not False:
        errors.append("N3DS-06 process-resident core mapping contract changed")
    if n3ds06.get("productUiExposed") is not False:
        errors.append("N3DS-06 must not expose the product UI before its remaining gates")
    if n3ds06.get("privateQaLifecycleHostStatus") != "PASSED" \
            or n3ds06.get("privateQaTargetPackage") != (
                "com.mateussouza.emuorbit.n3ds.adreno.target") \
            or n3ds06.get("privateQaTestPackage") != (
                "com.mateussouza.emuorbit.n3ds.core.test") \
            or n3ds06.get("privateQaActivityDeclaredInTarget") is not True \
            or n3ds06.get("privateQaActivityDeclaredInSelfTarget") is not True \
            or n3ds06.get("privateQaLifecycleRuntimeBundled") != (
                "androidx.lifecycle:lifecycle-common:2.11.0"):
        errors.append("N3DS-06C private lifecycle QA host contract changed")
    expected_lifecycle_matrix = [
        {"name": "Samsung Galaxy A37 5G", "status": "PASSED"},
        {"name": "Samsung Galaxy A34 5G", "status": "PASSED"},
        {"name": "Poco X6 Pro", "status": "PASSED"},
    ]
    if n3ds06.get("physicalDeviceMatrixPolicy") != (
            "LOCAL_FINAL_DEVICE_GATES_NO_REMOTE_SUBSTITUTION") \
            or n3ds06.get("physicalDeviceMatrix") != expected_lifecycle_matrix:
        errors.append("N3DS-06 physical lifecycle matrix changed")
    n3ds06_validation = n3ds06.get("validation", {})
    for required_evidence in (
        "explicitRecreatePassed",
        "portraitToLandscapeRotationPassed",
        "surfaceLossRecreationPassed",
        "ownerThreadSessionReopenPassed",
    ):
        if n3ds06_validation.get(required_evidence) is not True:
            errors.append(f"N3DS-06 lifecycle evidence is missing: {required_evidence}")
    if n3ds06_validation.get("backgroundForegroundCycles", 0) < 1:
        errors.append("N3DS-06 must retain background/foreground evidence")
    if n3ds06_validation.get("realLauncherSwitchCycles", 0) < 1:
        errors.append("N3DS-06 must retain a real launcher-switch lifecycle test")
    if n3ds06_validation.get("screenOffOnCycles", 0) < 1:
        errors.append("N3DS-06 must retain a screen off/on lifecycle test")
    if n3ds06_validation.get("secureKeyguardCredentialChanged") is not False:
        errors.append("N3DS-06 tests must not change the owner's keyguard credential")
    if n3ds06_validation.get("privateQaTargetDevice") != "SM_A376B_ANDROID_16" \
            or n3ds06_validation.get("privateQaTargetLifecycleTestsPassed") != 2 \
            or n3ds06_validation.get("privateQaTargetLifecycleTestsTotal") != 2 \
            or n3ds06_validation.get(
                "privateQaRecreateRotationElapsedMilliseconds") != 9241 \
            or n3ds06_validation.get(
                "privateQaLauncherScreenElapsedMilliseconds") != 9086:
        errors.append("N3DS-06C private target Galaxy evidence changed")
    if n3ds06_validation.get("localLifecycleRunner") != (
            "scripts/run-nintendo3ds-adreno-gate.ps1 -LifecycleOnly") \
            or n3ds06_validation.get("localLifecycleRunnerStatus") != (
                "PASSED_LIFECYCLE") \
            or n3ds06_validation.get("localLifecycleRunnerTestsPassed") != 2 \
            or n3ds06_validation.get("localLifecycleRunnerTestsTotal") != 2 \
            or n3ds06_validation.get("localLifecycleRunnerElapsedMilliseconds") != 21875 \
            or n3ds06_validation.get("localLifecycleRunnerExactCleanupComplete") is not True \
            or n3ds06_validation.get("localLifecycleRunnerTestPackageRemovedWhenNew") is not True \
            or n3ds06_validation.get("localLifecycleRunnerBundleSha256") != (
                "31874e66476b32f8e3b904a359296b186a125d1270c4805d69b22a84c693c24f") \
            or n3ds06_validation.get("localLifecycleRunnerPcCleanupRecoveredGigabytes") != 2.08 \
            or n3ds06_validation.get("localLifecycleRunnerBuildDirectoriesRemoved") is not True \
            or n3ds06_validation.get("localLifecycleRunnerDeviceTemporaryFilesRemaining") != 0 \
            or n3ds06_validation.get("localLifecycleRunnerMainAppPreserved") is not True \
            or n3ds06_validation.get("localLifecycleRunnerUserDataTouched") is not False:
        errors.append("N3DS-06C local lifecycle runner evidence changed")
    if n3ds06_validation.get("pocoLifecycleStatus") != "PASSED" \
            or n3ds06_validation.get("pocoDevice") != (
                "POCO_X6_PRO_2311DRK48G_ANDROID_16_PHYSICAL") \
            or n3ds06_validation.get("pocoSoc") != (
                "MEDIATEK_MT6897_DIMENSITY_8300_ULTRA") \
            or n3ds06_validation.get("pocoVulkanDevice") != "Mali-G615 MC6" \
            or n3ds06_validation.get("pocoLifecycleTestsPassed") != 2 \
            or n3ds06_validation.get("pocoLifecycleTestsTotal") != 2 \
            or n3ds06_validation.get("pocoRecreateRotationElapsedMilliseconds") != 8530 \
            or n3ds06_validation.get("pocoLauncherScreenElapsedMilliseconds") != 11673 \
            or n3ds06_validation.get("pocoDelayedCrashObservationSecondsPerTest") != 5 \
            or n3ds06_validation.get("pocoFatalSignalsObserved") != 0 \
            or n3ds06_validation.get("pocoOwnedContentFrames") != 60 \
            or n3ds06_validation.get("pocoOwnedContentReopenFrames") != 2 \
            or n3ds06_validation.get("pocoOwnedContentFatalSignalsObserved") != 0 \
            or n3ds06_validation.get("pocoArchiveSha256") != (
                "3bcca3942baa3a1615b6b505e7adaac0f8ea9ad65123d01ffa6a02563cecf5c2") \
            or n3ds06_validation.get("pocoArchiveContainedSingleNintendo3DsImage") is not True \
            or n3ds06_validation.get("pocoQaStorageRecoveredKilobytes") != 5495992 \
            or n3ds06_validation.get("pocoQaPackageRemoved") is not True \
            or n3ds06_validation.get("pocoDeviceTemporaryFilesRemaining") != 0 \
            or n3ds06_validation.get("pocoAdbInstallVerificationRestored") is not True \
            or n3ds06_validation.get("pocoPcBuildLogicalBytesBeforeCleanup") != 383812595 \
            or n3ds06_validation.get("pocoPcCleanupRecoveredGigabytes") != 0.73 \
            or n3ds06_validation.get("pocoPcBuildDirectoriesRemoved") is not True \
            or n3ds06_validation.get("pocoPrivateRomTransferPreservedForGalaxyA34") is not True:
        errors.append("N3DS-06D Poco Mali lifecycle or cleanup evidence changed")
    if n3ds06_validation.get("a34LifecycleStatus") != "PASSED" \
            or n3ds06_validation.get("a34Device") != (
                "GALAXY_A34_SM_A346M_A34X_ANDROID_16_PHYSICAL") \
            or n3ds06_validation.get("a34Soc") != "MEDIATEK_MT6877" \
            or n3ds06_validation.get("a34VulkanDevice") != "Mali-G68 MC4" \
            or n3ds06_validation.get("a34LifecycleTestsPassed") != 2 \
            or n3ds06_validation.get("a34LifecycleTestsTotal") != 2 \
            or n3ds06_validation.get("a34RecreateRotationElapsedMilliseconds") != 7827 \
            or n3ds06_validation.get("a34LauncherScreenElapsedMilliseconds") != 8938 \
            or n3ds06_validation.get("a34DelayedCrashObservationSecondsPerTest") != 5 \
            or n3ds06_validation.get("a34FatalSignalsObserved") != 0 \
            or n3ds06_validation.get("a34OwnedContentFrames") != 60 \
            or n3ds06_validation.get("a34OwnedContentReopenFrames") != 2 \
            or n3ds06_validation.get("a34OwnedContentFatalSignalsObserved") != 0 \
            or n3ds06_validation.get("a34ArchiveSha256") != (
                "3bcca3942baa3a1615b6b505e7adaac0f8ea9ad65123d01ffa6a02563cecf5c2") \
            or n3ds06_validation.get("a34ArchiveContainedSingleNintendo3DsImage") is not True \
            or n3ds06_validation.get("a34QaStorageRecoveredKilobytes") != 1625236 \
            or n3ds06_validation.get("a34QaPackageRemoved") is not True \
            or n3ds06_validation.get("a34DeviceTemporaryFilesRemaining") != 0 \
            or n3ds06_validation.get("a34MainAppPreserved") is not True \
            or n3ds06_validation.get("a34UserDataTouched") is not False \
            or n3ds06_validation.get("a34PcBuildLogicalBytesBeforeCleanup") != 383812578 \
            or n3ds06_validation.get("a34PcCleanupRecoveredGigabytes") != 0.72 \
            or n3ds06_validation.get("a34PcTemporaryBytesRemoved") != 60628839 \
            or n3ds06_validation.get("a34PcBuildDirectoriesRemoved") is not True \
            or n3ds06_validation.get("privateRomTransferPreservedOutsideGit") is not True:
        errors.append("N3DS-06D Galaxy A34 lifecycle or cleanup evidence changed")
    if n3ds06_validation.get("fatalSignalsObserved") != 0:
        errors.append("N3DS-06 lifecycle validation must remain free of fatal signals")

    n3ds07 = manifest.get("n3ds07Decision", {})
    if n3ds07.get("status") not in {"IN_PROGRESS", "DONE"}:
        errors.append("N3DS-07 audio work must remain tracked")
    if n3ds07.get("pcmQueueStatus") != "PASSED":
        errors.append("N3DS-07 bounded PCM queue must remain verified")
    ring_capacity = n3ds07.get("nativeRingCapacityFrames", 0)
    if ring_capacity != 65536:
        errors.append("N3DS-07 PCM ring must retain its measured fixed capacity")
    if n3ds07.get("overflowPolicy") != "DROP_OLDEST_KEEP_LATEST":
        errors.append("N3DS-07 must preserve its low-latency audio overflow policy")
    if n3ds07.get("nintendoDsFilterReused") is not False:
        errors.append("N3DS-07 must not silently reuse the Nintendo DS audio filter")
    if n3ds07.get("audioTrackPlaybackStatus") != "PASSED":
        errors.append("N3DS-07 Android AudioTrack playback must remain verified")
    if n3ds07.get("audioTrackLifecycleStatus") != "PASSED":
        errors.append("N3DS-07 AudioTrack lifecycle must remain verified")
    if n3ds07.get("status") == "DONE" and n3ds07.get("pacingStatus") \
            != "PASSED_NORMAL_AND_FAST_FORWARD_REFERENCE_DEVICE":
        errors.append("N3DS-07 normal and fast-forward audio pacing must remain verified")
    if n3ds07.get("status") == "DONE" and n3ds07.get("remainingGates") != []:
        errors.append("N3DS-07 DONE status cannot retain unresolved audio gates")
    transfer_capacity = n3ds07.get("audioTrackTransferCapacityFrames", 0)
    if transfer_capacity != 2048:
        errors.append("N3DS-07 AudioTrack transfer must retain its measured bound")
    if n3ds07.get("audioTrackPrerollMillis") != 60:
        errors.append("N3DS-07 AudioTrack must retain its measured preroll")
    if n3ds07.get("audioEnabledByDefault") is not False:
        errors.append("N3DS-07 audio must remain opt-in until the product host is ready")
    n3ds07_validation = n3ds07.get("validation", {})
    if n3ds07_validation.get("streamedProducedFrames") != n3ds07_validation.get(
        "streamedDrainedFrames"
    ):
        errors.append("N3DS-07 frame-cadence PCM transfer must remain lossless")
    if n3ds07_validation.get("streamingDroppedFrames") != 0:
        errors.append("N3DS-07 frame-cadence PCM transfer must not drop audio")
    if n3ds07_validation.get("delayedConsumerQueuedFrames", 0) > ring_capacity:
        errors.append("N3DS-07 delayed audio consumer exceeded the fixed ring capacity")
    if n3ds07_validation.get("delayedConsumerDroppedFrames", 0) <= 0:
        errors.append("N3DS-07 delayed-consumer overflow behavior is unverified")
    if n3ds07_validation.get("normalPlaybackWrittenFrames", 0) <= 0:
        errors.append("N3DS-07 AudioTrack must retain real PCM write evidence")
    if n3ds07_validation.get("normalPlaybackDroppedFrames") != 0:
        errors.append("N3DS-07 normal-speed AudioTrack must not drop PCM")
    if n3ds07_validation.get("normalPlaybackShortWrites") != 0:
        errors.append("N3DS-07 normal-speed AudioTrack must not retain short writes")
    if n3ds07_validation.get("normalPlaybackOutputFailures") != 0:
        errors.append("N3DS-07 normal-speed AudioTrack must remain failure-free")
    if n3ds07_validation.get("normalPlaybackUnderruns", 1) > n3ds07_validation.get(
        "normalPlaybackUnderrunBudget", 0
    ):
        errors.append("N3DS-07 AudioTrack underruns exceeded the measured budget")
    if n3ds07_validation.get("normalPlaybackBufferCapacityFrames", 0) <= transfer_capacity:
        errors.append("N3DS-07 AudioTrack buffer must exceed one bounded transfer")
    if n3ds07_validation.get("audioTracksBalancedAfterControllerClose") is not True:
        errors.append("N3DS-07 must release every AudioTrack on controller close")
    if n3ds07_validation.get("inFlightPauseMillis", 5001) > n3ds07_validation.get(
        "inFlightPauseTimeoutMillis", 5000
    ):
        errors.append("N3DS-07 in-flight audio pause exceeded its bounded timeout")
    if n3ds07_validation.get("inFlightTransitionDroppedFrames", 2049) > transfer_capacity:
        errors.append("N3DS-07 lifecycle transition dropped more than one transfer block")
    if n3ds07_validation.get("inFlightOutputFailures") != 0:
        errors.append("N3DS-07 lifecycle cancellation must not count as an output failure")
    fast_forward = n3ds07_validation.get("fastForward", {})
    if fast_forward.get("allInputFramesAccounted") is not True:
        errors.append("N3DS-07 fast-forward must account for every input PCM frame")
    fast_forward_drop_budget = fast_forward.get(
        "maximumOutputDropBudgetFramesPerSpeed", 0
    )
    for speed_key in ("twoTimes", "fourTimes"):
        speed_validation = fast_forward.get(speed_key, {})
        if speed_validation.get("writtenFrames", 0) <= 0:
            errors.append(f"N3DS-07 {speed_key} must retain real AudioTrack writes")
        if speed_validation.get("intentionallyDecimatedFrames", 0) <= 0:
            errors.append(f"N3DS-07 {speed_key} must retain intentional PCM decimation")
        if speed_validation.get("outputDroppedFrames", fast_forward_drop_budget + 1) \
                > fast_forward_drop_budget:
            errors.append(f"N3DS-07 {speed_key} exceeded its bounded output-drop budget")
        if speed_validation.get("nonBlockingWriteCalls", 0) <= 0:
            errors.append(f"N3DS-07 {speed_key} must retain non-blocking output evidence")
    if fast_forward.get("outputFailures") != 0:
        errors.append("N3DS-07 fast-forward output must remain failure-free")
    prolonged = n3ds07_validation.get("prolongedRegression", {})
    if prolonged.get("privateOwnedContentCount") != 3:
        errors.append("N3DS-07 prolonged regression must retain three private contents")
    if prolonged.get("totalVideoFrames", 0) < 5400:
        errors.append("N3DS-07 prolonged regression must retain at least 5400 video frames")
    if prolonged.get("totalPcmFramesWritten", 0) <= 0:
        errors.append("N3DS-07 prolonged regression must retain real PCM output")
    if prolonged.get("outputDroppedFrames") != 0 \
            or prolonged.get("shortWrites") != 0 \
            or prolonged.get("outputFailures") != 0:
        errors.append("N3DS-07 prolonged regression must remain lossless and failure-free")
    if prolonged.get("pauseResumePassed") is not True \
            or prolonged.get("audioTracksBalancedAfterClose") is not True:
        errors.append("N3DS-07 prolonged lifecycle validation must remain verified")

    n3ds08 = manifest.get("n3ds08Decision", {})
    if n3ds08.get("status") != "IN_PROGRESS":
        errors.append("N3DS-08 input work must remain tracked until every capability is verified")
    if "N3DS-08A" not in n3ds08.get("completedSubsteps", []):
        errors.append("N3DS-08A digital and Circle Pad milestone must remain complete")
    if "N3DS-08B" not in n3ds08.get("completedSubsteps", []):
        errors.append("N3DS-08B C-stick and touch milestone must remain complete")
    if "N3DS-08C1" not in n3ds08.get("completedSubsteps", []):
        errors.append("N3DS-08C1 Android motion milestone must remain complete")
    if "N3DS-08C2" not in n3ds08.get("completedSubsteps", []):
        errors.append("N3DS-08C2 Android microphone milestone must remain complete")
    if "N3DS-08D1" not in n3ds08.get("completedSubsteps", []):
        errors.append("N3DS-08D1 multi-layout touch and synthetic gamepad milestone must remain complete")
    if n3ds08.get("digitalControlsStatus") != "PASSED_REFERENCE_DEVICE" \
            or n3ds08.get("circlePadStatus") != "PASSED_REFERENCE_DEVICE":
        errors.append("N3DS-08A digital and Circle Pad device evidence must remain verified")
    if n3ds08.get("inputPublication") \
            != "ATOMIC_SNAPSHOT_BEFORE_EACH_OWNER_THREAD_CORE_FRAME":
        errors.append("N3DS-08A input must retain atomic per-frame publication")
    if n3ds08.get("lifecycleNeutralReset") is not True:
        errors.append("N3DS-08A lifecycle transitions must reset controls to neutral")
    if n3ds08.get("existingSystemControlsModified") is not False:
        errors.append("N3DS-08A must not alter controls for existing emulator systems")
    if n3ds08.get("cStickStatus") != "PASSED_REFERENCE_DEVICE" \
            or n3ds08.get("touchStatus") \
            != "PASSED_REFERENCE_DEVICE_COMPOSITE_FRAME_MAPPING":
        errors.append("N3DS-08B C-stick and touch device evidence must remain verified")
    remaining_input_gates = set(n3ds08.get("remainingGates", []))
    if "C_STICK" in remaining_input_gates or "TOUCH_LAYOUT_MAPPING" in remaining_input_gates:
        errors.append("N3DS-08B completed capabilities cannot remain listed as pending gates")
    if n3ds08.get("sensorStatus") \
            != "PASSED_REFERENCE_DEVICE_ANDROID_SENSOR_MANAGER":
        errors.append("N3DS-08C1 real Android sensor evidence must remain verified")
    if "REAL_ANDROID_SENSORS" in remaining_input_gates:
        errors.append("N3DS-08C1 Android sensors cannot remain listed as a pending gate")
    if n3ds08.get("microphoneStatus") \
            != "PASSED_REFERENCE_DEVICE_PERMISSION_DENIAL_AND_ANDROID_AUDIO_RECORD":
        errors.append("N3DS-08C2 permission-aware Android microphone evidence must remain verified")
    if "MICROPHONE_PERMISSION_DENIAL_AND_REAL_INPUT" in remaining_input_gates:
        errors.append("N3DS-08C2 microphone cannot remain listed as a pending gate")
    if n3ds08.get("touchSurfaceMappingStatus") \
            != "PASSED_REFERENCE_DEVICE_DEFAULT_SIDE_BY_SIDE_SINGLE_BOTTOM_PORTRAIT_LANDSCAPE" \
            or n3ds08.get("screenLayoutStatus") \
            != "PASSED_REFERENCE_DEVICE_AZAHAR_CORE_OPTIONS":
        errors.append("N3DS-08D1 lacks real multi-layout and orientation touch evidence")
    if n3ds08.get("physicalControllerStatus") \
            != "PASSED_SYNTHETIC_ANDROID_DEVICE_EVENTS_PHYSICAL_HARDWARE_PENDING" \
            or "PHYSICAL_CONTROLLER_HARDWARE_REGRESSION" not in remaining_input_gates:
        errors.append("N3DS-08D1 must keep physical controller hardware explicitly pending")
    n3ds08_validation = n3ds08.get("validation", {})
    if n3ds08_validation.get("moduleInstrumentedTests") != 14 \
            or n3ds08_validation.get("moduleInstrumentedTestsPassed") != 14 \
            or n3ds08_validation.get("moduleInstrumentedTestsElapsedMillis", 0) <= 0 \
            or n3ds08_validation.get("sessionInstrumentedTestsInFinalRun") != 10 \
            or n3ds08_validation.get("sessionInstrumentedTestsElapsedMillis", 0) <= 0:
        errors.append("N3DS-08D1 final Android instrumented regression evidence changed")
    for callback in (
        "inputPollCallbacks",
        "inputStateCallbacks",
        "joypadStateCallbacks",
        "analogStateCallbacks",
    ):
        if n3ds08_validation.get(callback, 0) <= 0:
            errors.append(f"N3DS-08A lacks real Azahar {callback} evidence")
    if n3ds08_validation.get("activeButtonMask") != 4353:
        errors.append("N3DS-08A combined digital mask evidence changed")
    if n3ds08_validation.get("circlePadX") != 16384 \
            or n3ds08_validation.get("circlePadY") != -8192:
        errors.append("N3DS-08A Circle Pad axis evidence changed")
    if n3ds08_validation.get("lifecycleNeutralButtonMask") != 0 \
            or n3ds08_validation.get("lifecycleNeutralCirclePadX") != 0 \
            or n3ds08_validation.get("lifecycleNeutralCirclePadY") != 0:
        errors.append("N3DS-08A lifecycle neutral-state evidence changed")
    if n3ds08_validation.get("rightAnalogStateCallbacks", 0) <= 0 \
            or n3ds08_validation.get("pointerStateCallbacks", 0) <= 0:
        errors.append("N3DS-08B lacks real Azahar right-analog or pointer callback evidence")
    if n3ds08_validation.get("cStickX") != -24575 \
            or n3ds08_validation.get("cStickY") != 8192 \
            or n3ds08_validation.get("changedCStickX") != 32767 \
            or n3ds08_validation.get("changedCStickY") != -32767:
        errors.append("N3DS-08B C-stick axis evidence changed")
    if n3ds08_validation.get("pointerX") != 82 \
            or n3ds08_validation.get("pointerY") != 16486 \
            or n3ds08_validation.get("pointerPressed") is not True \
            or n3ds08_validation.get("changedPointerX") != -26197 \
            or n3ds08_validation.get("changedPointerY") != 68 \
            or n3ds08_validation.get("changedPointerPressed") is not False:
        errors.append("N3DS-08B absolute pointer evidence changed")
    if n3ds08_validation.get("lifecycleNeutralCStickX") != 0 \
            or n3ds08_validation.get("lifecycleNeutralCStickY") != 0 \
            or n3ds08_validation.get("lifecycleNeutralPointerX") != 0 \
            or n3ds08_validation.get("lifecycleNeutralPointerY") != 0 \
            or n3ds08_validation.get("lifecycleNeutralPointerPressed") is not False:
        errors.append("N3DS-08B lifecycle neutral-state evidence changed")
    if n3ds08_validation.get("sensorStateCallbacks", 0) < 2 \
            or n3ds08_validation.get("sensorInputCallbacks", 0) <= 0 \
            or n3ds08_validation.get("sensorSamplingRateHz") != 60:
        errors.append("N3DS-08C1 lacks real Azahar 60 Hz sensor callback evidence")
    if n3ds08_validation.get("androidAccelerometerEvents", 0) <= 0 \
            or n3ds08_validation.get("androidGyroscopeEvents", 0) <= 0:
        errors.append("N3DS-08C1 lacks physical Android motion events")
    if n3ds08_validation.get("motionStoppedInBackground") is not True \
            or n3ds08_validation.get("motionStartStopBalancedAfterClose") is not True:
        errors.append("N3DS-08C1 motion lifecycle must stop and balance its source")
    if n3ds08_validation.get("microphonePermissionDeniedPassed") is not True \
            or n3ds08_validation.get("microphonePermissionDeniedCapturedSamples") != 0 \
            or n3ds08_validation.get("microphonePermissionDeniedSilentSamples", 0) <= 0:
        errors.append("N3DS-08C2 permission denial must preserve a silent working frontend")
    if n3ds08_validation.get("microphoneRealAudioRecordPassed") is not True \
            or n3ds08_validation.get("microphoneAndroidCapturedSamples", 0) <= 0 \
            or n3ds08_validation.get("microphoneNativeCapturedSamples", 0) <= 0 \
            or n3ds08_validation.get("microphoneNativeDeliveredSamples", 0) <= 0:
        errors.append("N3DS-08C2 lacks real Android PCM delivery through Azahar")
    if n3ds08_validation.get("microphoneFrontendSelected") is not True \
            or n3ds08_validation.get("microphoneOpenCallbacks", 0) <= 0 \
            or n3ds08_validation.get("microphoneStateCallbacks", 0) <= 0 \
            or n3ds08_validation.get("microphoneReadCallbacks", 0) <= 0 \
            or n3ds08_validation.get("microphoneSamplingRateHz") != 48000:
        errors.append("N3DS-08C2 lacks Azahar LibRetro microphone-v1 callback evidence")
    if n3ds08_validation.get("microphoneJavaQueueCapacitySamples") != 48000 \
            or n3ds08_validation.get("microphoneNativeQueueCapacitySamples") != 48000 \
            or n3ds08_validation.get("microphoneNativeQueuedSamples", 48001) > 48000:
        errors.append("N3DS-08C2 microphone queues must remain bounded to one second")
    if n3ds08_validation.get("microphoneStoppedInBackground") is not True \
            or n3ds08_validation.get("microphoneStartStopBalancedAfterClose") is not True \
            or n3ds08_validation.get("microphoneCaptureFailures") != 0:
        errors.append("N3DS-08C2 microphone lifecycle must stop cleanly without capture failure")
    if n3ds08_validation.get("validatedLayouts") \
            != ["DEFAULT", "SIDE_BY_SIDE", "SINGLE_BOTTOM"] \
            or n3ds08_validation.get("validatedSurfaceOrientations") \
            != ["PORTRAIT", "LANDSCAPE"] \
            or n3ds08_validation.get("defaultLayoutGeometry") != "400x480" \
            or n3ds08_validation.get("sideBySideLayoutGeometry") != "720x240" \
            or n3ds08_validation.get("singleBottomLayoutGeometry") != "320x240":
        errors.append("N3DS-08D1 multi-layout geometry evidence changed")
    if n3ds08_validation.get("sideBySideMappedPointerX") != 18275 \
            or n3ds08_validation.get("sideBySideMappedPointerY") != 137 \
            or n3ds08_validation.get("letterboxOutsideTouchReleased") is not True:
        errors.append("N3DS-08D1 aspect-fit touch evidence changed")
    if n3ds08_validation.get("syntheticAndroidGamepadEventsPassed") is not True \
            or n3ds08_validation.get("physicalGamepadConnected") is not False \
            or n3ds08_validation.get("syntheticGamepadButtonMask") != 12608 \
            or n3ds08_validation.get("syntheticGamepadCirclePadX") != 16384 \
            or n3ds08_validation.get("syntheticGamepadCirclePadY") != -16384 \
            or n3ds08_validation.get("syntheticGamepadCStickX") != -16384 \
            or n3ds08_validation.get("syntheticGamepadCStickY") != 16384:
        errors.append("N3DS-08D1 synthetic Android gamepad evidence changed")
    if n3ds08_validation.get("fatalSignalsObserved") != 0:
        errors.append("N3DS-08 validation must remain free of fatal signals")

    n3ds09 = manifest.get("n3ds09Decision", {})
    if n3ds09.get("status") != "DONE" \
            or n3ds09.get("completedOn") != "2026-09-12":
        errors.append("N3DS-09 persistence owner-accepted closure changed")
    if n3ds09.get("completedSubsteps") \
            != ["N3DS-09A", "N3DS-09B", "N3DS-09C", "N3DS-09D1", "N3DS-09D2", "N3DS-09D3", "N3DS-09E1"]:
        errors.append("N3DS-09A/B/C/D1/D2/D3/E1 persistence milestones must remain complete")
    if n3ds09.get("storageSchemaVersion") != 1 \
            or n3ds09.get("coreRevisionToken") \
            != "azahar-2126.0-fbd3fb02f71e5f9ed5134037fd59bad96c7d2b8a":
        errors.append("N3DS-09A must retain its versioned storage and pinned core token")
    if n3ds09.get("coreDirectoryContract") \
            != "AZAHAR_APPENDS_OWN_USER_ROOT_TO_LIBRETRO_SAVE_DIRECTORY":
        errors.append("N3DS-09A must retain the measured Azahar directory contract")
    if n3ds09.get("checkpointPolicy") \
            != "CORE_OWNER_THREAD_SHUTDOWN_THEN_SHA256_CONTENT_ADDRESSED_OBJECTS_AND_ATOMIC_MANIFEST":
        errors.append("N3DS-09A checkpoint ordering or integrity policy changed")
    if n3ds09.get("restorePolicy") \
            != "VERIFY_ALL_OBJECTS_THEN_PREPARE_ALL_TREES_AND_ATOMICALLY_SWAP_WITH_REVERSE_ORDER_ROLLBACK" \
            or n3ds09.get("incompatibleCorePolicy") \
            != "RESTORE_NEWEST_EXACT_REVISION_SNAPSHOT_OR_REJECT_BEFORE_MUTATION":
        errors.append("N3DS-09B transactional restore or compatibility policy changed")
    if n3ds09.get("fallbackPolicy") \
            != "LATEST_THEN_NEWEST_CORE_COMPATIBLE_SHA256_VERIFIED_IMMUTABLE_SNAPSHOT_AND_REPUBLISH_LATEST":
        errors.append("N3DS-09C verified snapshot fallback policy changed")
    if n3ds09.get("persistentTrees") \
            != ["nand", "sdmc", "sysdata", "config", "cheats"]:
        errors.append("N3DS-09A durable-tree allowlist changed")
    if n3ds09.get("excludedTrees") \
            != ["cache", "shaders", "states", "log", "dump"]:
        errors.append("N3DS-09A regenerable or unqualified-tree exclusions changed")
    if n3ds09.get("virtualSdEnabled") is not True \
            or n3ds09.get("saveStatesEnabled") is not False \
            or n3ds09.get("rewindEnabled") is not False:
        errors.append("N3DS-09A must keep virtual SD durable and state/rewind disabled")
    if n3ds09.get("lowDiskPreflight") is not True \
            or n3ds09.get("atomicMoveRequired") is not True \
            or n3ds09.get("existingSystemPersistenceModified") is not False:
        errors.append("N3DS-09A atomic failure isolation policy changed")
    if n3ds09.get("interactiveValidationStatus") \
            != "DONE_DEBUG_ONLY_OPT_IN_WITH_OWNER_EVIDENCE_WAIVER" \
            or n3ds09.get("interactiveInputPlanStatus") \
            != "PASSED_DEBUG_ONLY_BOUNDED_ROM_AGNOSTIC_TOUCH_AND_COMBINED_INPUT" \
            or n3ds09.get("interactiveTouchPlanStatus") \
            != "PASSED_DEBUG_ONLY_NORMALIZED_SURFACE_MAPPING" \
            or n3ds09.get("interactiveCombinedInputStatus") \
            != "PASSED_DEBUG_ONLY_CIRCLE_PAD_PLUS_UP_TO_TWO_BUTTONS" \
            or n3ds09.get("interactiveDeadlinePolicy") \
            != "CAP_FINAL_STEP_AND_STOP_AT_ABSOLUTE_SESSION_DEADLINE" \
            or n3ds09.get("baseSuiteProcessIsolationPolicy") \
            != "FRESH_APP_PROCESS_PER_NATIVE_INSTRUMENTED_TEST" \
            or n3ds09.get("sameProcessBaseStressStatus") \
            != "UNSUPPORTED_UPSTREAM_GLOBAL_STATE_STACK_PROTECTOR_ABORT_AFTER_REPEATED_SESSIONS":
        errors.append("N3DS-09D3 interactive or native-process isolation policy changed")
    n3ds09_gates = set(n3ds09.get("remainingGates", []))
    expected_n3ds09_gates = set()
    if n3ds09_gates != expected_n3ds09_gates:
        errors.append("N3DS-09 pending gates changed")
    update_candidate = n3ds09.get("updateCompatibilityCandidate", {})
    if update_candidate.get("status") != "PASSED" \
            or update_candidate.get("repository") \
            != "https://github.com/azahar-emu/azahar.git" \
            or update_candidate.get("releaseStatus") != "STABLE" \
            or update_candidate.get("tag") != "2126.1" \
            or update_candidate.get("commit") \
            != "26e608f6fa292b27cda0ae8c84e148d17600a5e6" \
            or update_candidate.get("recursiveSubmoduleCount") != 52 \
            or update_candidate.get("recursiveSubmoduleStatusSha256") \
            != "dfd886dd0b03bb63930de92a0451a3a3f9111d280461db626aee82e89c19075e" \
            or update_candidate.get("linkedDependencyCount") != 19 \
            or update_candidate.get("patchesApplied") != 4 \
            or update_candidate.get("buildSteps") != 1155 \
            or update_candidate.get("strippedBytes") != 35543960 \
            or update_candidate.get("strippedSha256") \
            != "bcfcb267ae42890f8d5e3a7414fc935c63576fb60dbde9ec1e3fb4dca2d799cf" \
            or update_candidate.get("buildRevisionEmbedded") is not True \
            or update_candidate.get("reportedCoreVersion") != "26e608f" \
            or update_candidate.get("worktreeRevisionFallbackApplied") is not True \
            or update_candidate.get("linkerPageSizeBytes") != 16384 \
            or update_candidate.get("legacyTlsLinked") is not False \
            or update_candidate.get("binaryCommitted") is not False \
            or update_candidate.get("baselinePromoted") is not False \
            or update_candidate.get("saveStateCrossVersionSupported") is not False \
            or update_candidate.get("processIsolationPolicy") \
            != "FRESH_APP_PROCESS_PER_INSTALLED_CORE_REVISION" \
            or update_candidate.get("sameProcessMixedRevisionStressStatus") \
            != "UNSUPPORTED_UPSTREAM_GLOBAL_STATE_SIGSEGV_AFTER_PRIOR_SESSIONS":
        errors.append("N3DS-09 update candidate pin or isolation policy changed")
    update_device = update_candidate.get("deviceValidation", {})
    if update_device.get("device") != "SM-A376B_ANDROID_16" \
            or update_device.get("privateOwnedContentCount") != 3 \
            or update_device.get("framesPerRevisionPerContent") != 120 \
            or update_device.get("baseGeneration") != 2 \
            or update_device.get("updateGeneration") != 3 \
            or update_device.get("baseNativeTitleDataRoots") != 2 \
            or update_device.get("updateCheckpointEntries") != 15 \
            or update_device.get("restoredUpdateBytes") != 608487 \
            or update_device.get("rollbackGeneration") != 2 \
            or update_device.get("forwardGeneration") != 3 \
            or update_device.get("reopenedContents") != 3 \
            or update_device.get("sessionsOpened") != 9 \
            or update_device.get("sessionsClosed") != 9 \
            or update_device.get("isolatedTestElapsedMillis") != 29513 \
            or update_device.get("fatalSignalsObserved") != 0:
        errors.append("N3DS-09D2 physical update/rollback evidence changed")
    state_measurement = n3ds09.get("stateMeasurement", {})
    if state_measurement.get("status") != "MEASURED_DISABLED" \
            or state_measurement.get("device") != "SM-A376B_ANDROID_16" \
            or state_measurement.get("renderer") != "VULKAN" \
            or state_measurement.get("framesBeforeMeasurement") != 120 \
            or state_measurement.get("maximumAllowedBytes") != 67108864 \
            or state_measurement.get("contentCountPerCore") != 3 \
            or state_measurement.get("roundTripsPassed") != 6 \
            or state_measurement.get("postRestoreFramesPassed") != 6 \
            or state_measurement.get("minimumStateBytes") != 9815503 \
            or state_measurement.get("maximumStateBytes") != 16904046 \
            or state_measurement.get("maximumPrepareMicros") != 1281712 \
            or state_measurement.get("maximumSerializeMicros") != 2785 \
            or state_measurement.get("maximumUnserializeMicros") != 1253833:
        errors.append("N3DS-09E1 state size, latency or roundtrip evidence changed")
    if state_measurement.get("crossRevisionCompatibility") \
            != "UNSUPPORTED_VERSION_LOCKED_BY_UPSTREAM" \
            or state_measurement.get("crossRevisionLoadAttemptedWithValidBuilds") is not False \
            or state_measurement.get("invalidWorktreeRevisionBuildDetected") is not True \
            or state_measurement.get("productDecision") \
            != "DISABLED_UNTIL_SAME_REVISION_STORAGE_AND_UPGRADE_UX_ARE_QUALIFIED" \
            or state_measurement.get("saveStatesEnabled") is not False \
            or state_measurement.get("rewindEnabled") is not False:
        errors.append("N3DS-09E1 state compatibility safety decision changed")
    baseline_states = state_measurement.get("baseline", {})
    candidate_states = state_measurement.get("candidate", {})
    if baseline_states.get("tag") != "2126.0" \
            or baseline_states.get("reportedCoreVersion") != "fbd3fb0" \
            or baseline_states.get("stateBytes") != [16904046, 9825881, 15357978] \
            or baseline_states.get("prepareMicros") != [1281712, 939858, 1070261] \
            or baseline_states.get("serializeMicros") != [2785, 1736, 2627] \
            or baseline_states.get("unserializeMicros") != [1253833, 1100408, 1217687] \
            or candidate_states.get("tag") != "2126.1" \
            or candidate_states.get("reportedCoreVersion") != "26e608f" \
            or candidate_states.get("stateBytes") != [16890683, 9815503, 15368420] \
            or candidate_states.get("prepareMicros") != [1123364, 928798, 1074533] \
            or candidate_states.get("serializeMicros") != [2580, 1469, 2479] \
            or candidate_states.get("unserializeMicros") != [1095752, 1173448, 1130537]:
        errors.append("N3DS-09E1 per-core state measurements changed")
    n3ds09_validation = n3ds09.get("validation", {})
    if n3ds09_validation.get("storageJvmTests") != 16 \
            or n3ds09_validation.get("storageInstrumentedTests") != 4 \
            or n3ds09_validation.get("storageInstrumentedTestsPassed") != 4:
        errors.append("N3DS-09A automated storage test evidence changed")
    if n3ds09_validation.get("moduleInstrumentedTestsAvailable") != 26 \
            or n3ds09_validation.get("moduleInstrumentedTestsWithoutFailure") != 26 \
            or n3ds09_validation.get("moduleInstrumentedTestsElapsedMillis") != 192142 \
            or n3ds09_validation.get("moduleInstrumentedBaseProcessTests") != 24 \
            or n3ds09_validation.get("moduleInstrumentedUpdateProcessTests") != 1 \
            or n3ds09_validation.get("moduleInstrumentedInteractiveTests") != 1 \
            or n3ds09_validation.get("moduleInstrumentedTestsSkippedBySecureKeyguard") != 0:
        errors.append("N3DS-09A final module regression evidence changed")
    if n3ds09_validation.get("device") != "SM-A376B_ANDROID_16" \
            or n3ds09_validation.get("privateOwnedContentCount") != 3 \
            or n3ds09_validation.get("realCoreSessionsOpened") != 3 \
            or n3ds09_validation.get("realCoreSessionsClosed") != 3:
        errors.append("N3DS-09A real Azahar lifecycle evidence changed")
    if n3ds09_validation.get("manifestGeneration", 0) < 2 \
            or n3ds09_validation.get("manifestFileCount") != 10 \
            or n3ds09_validation.get("manifestLogicalBytes") != 244504 \
            or n3ds09_validation.get("incrementalCopiedFiles") != 0 \
            or n3ds09_validation.get("incrementalCopiedBytes") != 0 \
            or n3ds09_validation.get("incrementalReusedFiles") != 10:
        errors.append("N3DS-09A incremental manifest evidence changed")
    if n3ds09_validation.get("atomicRestorePassed") is not True \
            or n3ds09_validation.get("restoredCoreReopenedFrames", 0) < 2 \
            or n3ds09_validation.get("midCommitRollbackPassed") is not True \
            or n3ds09_validation.get("corruptedObjectRejectedBeforeMutation") is not True \
            or n3ds09_validation.get("incompatibleCoreRejectedBeforeMutation") is not True \
            or n3ds09_validation.get("restoreLowDiskPreflightPassed") is not True:
        errors.append("N3DS-09B restore, rollback, integrity or low-disk evidence changed")
    if n3ds09_validation.get("fallbackToPreviousIntactSnapshotPassed") is not True \
            or n3ds09_validation.get("fallbackLatestManifestRebuilt") is not True \
            or n3ds09_validation.get("fallbackGenerationMonotonic") is not True \
            or n3ds09_validation.get("deviceLatestManifestCorruptionFallbackPassed") is not True:
        errors.append("N3DS-09C fallback or recovery evidence changed")
    if n3ds09_validation.get("privateContentMatrixPassed") is not True \
            or n3ds09_validation.get("privateContentMatrixFramesPerContent") != 120 \
            or n3ds09_validation.get("privateContentMatrixNativeTitleDataRoots") != 2 \
            or n3ds09_validation.get("privateContentMatrixCheckpointEntries") != 15 \
            or n3ds09_validation.get("privateContentMatrixRestoredBytes") != 608487 \
            or n3ds09_validation.get("privateContentMatrixReopenedContents") != 3 \
            or n3ds09_validation.get("privateContentMatrixSessionsOpened") != 6 \
            or n3ds09_validation.get("privateContentMatrixSessionsClosed") != 6:
        errors.append("N3DS-09D1 private content persistence evidence changed")
    strict_save_regressions = n3ds09_validation.get("strictPlayableSaveRegressions", [])
    expected_strict_save_contents = [
        "PRIVATE_CONTENT_01", "PRIVATE_CONTENT_02", "PRIVATE_CONTENT_03",
        "PRIVATE_CONTENT_05"
    ]
    owner_save_acceptance = n3ds09_validation.get(
        "privatePlayableSaveOwnerAcceptance", {})
    if n3ds09_validation.get("privatePlayableSaveRegressionsRequired") != 5 \
            or n3ds09_validation.get("privatePlayableSaveAcceptedCount") != 5 \
            or n3ds09_validation.get("playableProgressSaveVerifiedCount") != 4 \
            or n3ds09_validation.get("privatePlayableSaveOwnerAcceptedCount") != 1 \
            or n3ds09_validation.get("privatePlayableSaveRegressionsRemaining") != 0 \
            or n3ds09_validation.get("privatePlayableSaveGateStatus") \
            != "CLOSED_BY_OWNER_WITH_4_OF_5_TECHNICALLY_VERIFIED" \
            or len(strict_save_regressions) != 4 \
            or [item.get("content") for item in strict_save_regressions] \
            != expected_strict_save_contents \
            or owner_save_acceptance.get("acceptedOn") != "2026-09-12" \
            or owner_save_acceptance.get("scope") \
            != "CLOSE_ONLY_THE_UNVERIFIED_FIFTH_PRIVATE_SAVE_CASE" \
            or owner_save_acceptance.get("technicalEvidenceCount") != 4 \
            or owner_save_acceptance.get(
                "ownerAcceptedWithoutStrictSaveEvidenceCount") != 1 \
            or owner_save_acceptance.get("waivedCase") != "PRIVATE_CONTENT_04" \
            or owner_save_acceptance.get("waivedCaseTechnicalResult") \
            != "PLAYABLE_WITHOUT_NATIVE_SAVE_MUTATION" \
            or owner_save_acceptance.get("claimsFiveStrictSaveTestsPassed") is not False:
        errors.append("N3DS-09D3 strict playable-save progress changed")
    if any(
            item.get("existingTitleRootRequired") is not True
            or item.get("existingTitleRootMutated") is not True
            or item.get("runnerElapsedMillis", 0) <= 0
            or item.get("retries") != 0
            or item.get("fatalSignalsObserved") != 0
            or item.get("gateResult")
            != "PASSED_STRICT_EXISTING_TITLE_ROOT_MUTATED"
            for item in strict_save_regressions):
        errors.append("N3DS-09D3 accepted saves must remain strict, isolated and crash-free")
    if strict_save_regressions and (
            strict_save_regressions[0].get("visualSaveConfirmationObserved") is not True
            or strict_save_regressions[1].get("visualPlayableProgressObserved") is not True
            or strict_save_regressions[2].get("visualPlayableRaceCompletionObserved") is not True
            or strict_save_regressions[3].get("visualPlayableProgressObserved") is not True):
        errors.append("N3DS-09D3 visual playable-progress evidence changed")
    latest_private_batch = n3ds09_validation.get("latestPrivateContentBatch", {})
    if latest_private_batch.get("providedContents") != 2 \
            or latest_private_batch.get("copiedToDeviceDownloadQa") != 2 \
            or latest_private_batch.get("ncsdMagicValidated") is not True \
            or latest_private_batch.get("archiveContainedSingleNintendo3DsImage") is not True \
            or latest_private_batch.get("archivePolicy") \
            != "REQUIRES_EXPLICIT_EXTRACTION_BEFORE_NINTENDO3DS_IMPORT" \
            or latest_private_batch.get("acceptedContent") != "PRIVATE_CONTENT_05" \
            or latest_private_batch.get("acceptedContentSaveCredit") != 1 \
            or latest_private_batch.get("nonCreditedContent") != "PRIVATE_CONTENT_04" \
            or latest_private_batch.get("nonCreditedContentSmokePassed") is not True \
            or latest_private_batch.get("nonCreditedContentSmokeRunnerElapsedMillis") != 156644 \
            or latest_private_batch.get("nonCreditedContentStrictAttempts") != 5 \
            or latest_private_batch.get("nonCreditedContentFinalRunnerElapsedMillis") != 152291 \
            or latest_private_batch.get("nonCreditedContentPlayableProgressObserved") is not True \
            or latest_private_batch.get("nonCreditedContentSaveMutationObserved") is not False \
            or latest_private_batch.get("nonCreditedContentSaveCredit") != 0 \
            or latest_private_batch.get("fatalSignalsObserved") != 0 \
            or latest_private_batch.get("privateIdentityPathOrHashCommitted") is not False \
            or latest_private_batch.get("pcRegenerableBuildRecoveredGigabytes") != 1.24 \
            or latest_private_batch.get("privatePcStageRemovedBytes") != 1128014460 \
            or latest_private_batch.get("privatePcStageRemoved") is not True \
            or latest_private_batch.get("devicePrivateSandboxRecoveredKilobytes") != 1606368 \
            or latest_private_batch.get("instrumentationPackageRemoved") is not True \
            or latest_private_batch.get("mainAppPreserved") is not True \
            or latest_private_batch.get("visibleQaCopiesPreserved") != 2 \
            or latest_private_batch.get("userDownloadOriginalsTouched") is not False \
            or latest_private_batch.get("publicSourceTouched") is not False:
        errors.append("N3DS-09D3 latest private-content batch evidence changed")
    deadline_counterexample = n3ds09_validation.get(
        "interactiveDeadlineBoundaryCounterexample", {})
    if deadline_counterexample.get("status") \
            != "HARNESS_EDGE_FOUND_FIXED_NOT_COUNTED_AS_SAVE_EVIDENCE" \
            or deadline_counterexample.get("scriptedInputDurationSeconds") != 300 \
            or deadline_counterexample.get("scriptedInputSteps") != 132 \
            or deadline_counterexample.get("playableCompletionObserved") is not True \
            or deadline_counterexample.get("productCrashObserved") is not False \
            or deadline_counterexample.get("saveCredit") != 0 \
            or deadline_counterexample.get("fix") \
            != "CAP_FINAL_STEP_AND_STOP_PLAN_AT_ABSOLUTE_SESSION_DEADLINE" \
            or deadline_counterexample.get("fixedBoundarySmokeSeconds") != 30 \
            or deadline_counterexample.get("fixedBoundaryScriptedInputSteps") != 1 \
            or deadline_counterexample.get("fixedBoundaryRunnerElapsedMillis") != 34112 \
            or deadline_counterexample.get("fixedBoundaryPassed") is not True \
            or deadline_counterexample.get("fixedBoundaryRetries") != 0:
        errors.append("N3DS-09D3 absolute-deadline regression evidence changed")
    save_batch_cleanup = n3ds09_validation.get("currentPlayableSaveBatchCleanup", {})
    if save_batch_cleanup.get("stagePcRecoveredGigabytes") != 0.78 \
            or save_batch_cleanup.get("qaPackageLogicalKilobytesRemoved") != 1085332 \
            or save_batch_cleanup.get("qaPackageRemoved") is not True \
            or save_batch_cleanup.get("privateStageRemoved") is not True \
            or save_batch_cleanup.get("mainAppPreserved") is not True \
            or save_batch_cleanup.get("privateDownloadOriginalCountPreserved") != 3 \
            or save_batch_cleanup.get("userDownloadOriginalsTouched") is not False \
            or save_batch_cleanup.get("publicSourceTouched") is not False:
        errors.append("N3DS-09D3 playable-save batch cleanup evidence changed")
    interactive_results = n3ds09_validation.get("interactiveContentResults", [])
    expected_interactive_gates = [
        "TITLE_PROFILE_SETUP_NOT_COUNTED_AS_PLAYABLE_PROGRESS",
        "MII_USER_DATA_REQUIRED_NOT_COUNTED_AS_PLAYABLE_PROGRESS",
        "INITIAL_SETUP_NOT_COUNTED_AS_PLAYABLE_PROGRESS",
    ]
    if n3ds09_validation.get("isolatedBaseSuitePassed") != 25 \
            or n3ds09_validation.get("isolatedBaseSuiteTotal") != 25 \
            or n3ds09_validation.get("isolatedBaseSuiteRetries") != 0 \
            or n3ds09_validation.get("isolatedBaseSuiteElapsedMillis") != 129604 \
            or n3ds09_validation.get("isolatedBaseSuiteFatalSignalsObserved") != 0 \
            or n3ds09_validation.get("interactiveBootAndInputVerifiedCount") != 3 \
            or n3ds09_validation.get("interactiveSaveMutationVerifiedCount") != 2 \
            or n3ds09_validation.get("interactiveForegroundLossFails") is not True \
            or n3ds09_validation.get("interactiveArtifactsCommitted") is not False \
            or len(interactive_results) != 3 \
            or [item.get("gateResult") for item in interactive_results] \
            != expected_interactive_gates:
        errors.append("N3DS-09D3 isolated or interactive physical evidence changed")
    if any(item.get("frames", 0) <= 0 or item.get("inputEvents", 0) <= 0
           for item in interactive_results):
        errors.append("N3DS-09D3 interactive frame/input evidence must remain positive")
    final_interactive_smoke = n3ds09_validation.get("finalInteractiveHarnessSmoke", {})
    if final_interactive_smoke.get("frames", 0) <= 0 \
            or final_interactive_smoke.get("inputEvents", 0) <= 0 \
            or final_interactive_smoke.get("foregroundRetained") is not True \
            or final_interactive_smoke.get("fatalSignalsObserved") != 0:
        errors.append("N3DS-09D3 final interactive harness smoke changed")
    if n3ds09_validation.get("cacheExcluded") is not True \
            or n3ds09_validation.get("shadersExcluded") is not True \
            or n3ds09_validation.get("statesExcluded") is not True \
            or n3ds09_validation.get("fatalSignalsObserved") != 0:
        errors.append("N3DS-09A excluded-tree or fatal-signal evidence changed")

    n3ds10 = manifest.get("n3ds10Decision", {})
    if n3ds10.get("status") != "DONE":
        errors.append("N3DS-10 must retain its accepted standard-Mii fallback closure")
    if n3ds10.get("completedSubsteps") != [
            "N3DS-10A", "N3DS-10B", "N3DS-10C1", "N3DS-10C2", "N3DS-10C3",
            "N3DS-10C4", "N3DS-10C5", "N3DS-10C6", "N3DS-10C7",
            "N3DS-10C8", "N3DS-10C9", "N3DS-10C10", "N3DS-10C11",
            "N3DS-10C12", "N3DS-10C13", "N3DS-10C14"]:
        errors.append(
            "N3DS-10 must retain its verified Mii, readiness, host, presentation and control foundations")
    if n3ds10.get("miiReadinessFoundationStatus") != "PASSED":
        errors.append("N3DS-10A Mii readiness foundation must remain verified")
    if n3ds10.get("launchReadinessFoundationStatus") != "PASSED":
        errors.append("N3DS-10B launch readiness foundation must remain verified")
    if n3ds10.get("experimentalHostCoordinatorStatus") != "PASSED":
        errors.append("N3DS-10C1 experimental host coordinator must remain verified")
    if n3ds10.get("localizedReadinessPresentationStatus") != "PASSED":
        errors.append("N3DS-10C2 localized readiness presentation must remain verified")
    if n3ds10.get("isolatedReadinessDialogStatus") != "PASSED":
        errors.append("N3DS-10C3 isolated readiness dialog must remain verified")
    if n3ds10.get("isolatedReadinessActionCoordinatorStatus") != "PASSED":
        errors.append("N3DS-10C4 isolated readiness action coordinator must remain verified")
    if n3ds10.get("asyncMiiImportWorkflowStatus") != "PASSED":
        errors.append("N3DS-10C5 asynchronous Mii import workflow must remain verified")
    if n3ds10.get("miiRecoveryRepreflightStatus") != "PASSED":
        errors.append("N3DS-10C6 Mii recovery re-preflight must remain verified")
    if n3ds10.get("activityResultReadinessHostStatus") != "PASSED" \
            or n3ds10.get("readinessDialogStyleAdapterBoundaryStatus") != "PASSED":
        errors.append("N3DS-10C7 Activity Result host and style boundary must remain verified")
    if n3ds10.get("experienceSettingsFoundationStatus") != "PASSED":
        errors.append("N3DS-10C8 experience settings foundation must remain verified")
    if n3ds10.get("experienceSettingsDialogStatus") != "PASSED":
        errors.append("N3DS-10C9 experience settings dialog must remain verified")
    if n3ds10.get("isolatedProductHostActivityStatus") != "PASSED" \
            or n3ds10.get("productLaunchContractStatus") != "PASSED" \
            or n3ds10.get("productDialogStyleStatus") != "PASSED" \
            or n3ds10.get("productSettingsGameplayEntryStatus") != "PASSED" \
            or n3ds10.get("productSettingsDismissResumeStatus") != "PASSED":
        errors.append("N3DS-10C10 hidden product host integration must remain verified")
    if n3ds10.get("conditionalLibraryPresentationStatus") != "PASSED" \
            or n3ds10.get("libraryCardPresentationPrepared") is not True \
            or n3ds10.get("libraryFilterEntryPrepared") is not True \
            or n3ds10.get("libraryFilterVisibilityPolicy") != (
                "REGISTRY_USER_VISIBLE_ONLY_WITH_XML_FAIL_CLOSED") \
            or n3ds10.get("libraryN3dsCurrentlyVisible") is not False \
            or n3ds10.get("libraryExistingSystemFilterOrderPreserved") is not True \
            or n3ds10.get("libraryPresentationLocaleTags") != [
                "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"] \
            or n3ds10.get("libraryCardArtworkOrigin") != (
                "OPENAI_BUILT_IN_IMAGEGEN_ORIGINAL_PROJECT_ASSET_NO_LOGOS_OR_TEXT"):
        errors.append("N3DS-10C11 conditional library presentation must remain verified")
    if n3ds10.get("accessibilityRenderedUiStatus") != "PASSED" \
            or n3ds10.get("accessibilityTalkBackServiceStatus") != (
                "PASSED_TEMPORARILY_ENABLED_AND_RESTORED") \
            or n3ds10.get("accessibilityLocaleTags") != [
                "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"] \
            or n3ds10.get("accessibilityFontScale") != 1.3 \
            or n3ds10.get("accessibilityRenderedDialogSurfaces") != 20 \
            or n3ds10.get("accessibilityArabicRtlPassed") is not True \
            or n3ds10.get("accessibilityGameplaySurfaceExcludedFromTalkBack") is not True \
            or n3ds10.get("accessibilityActionMaximumLines") != 2:
        errors.append("N3DS-10C12 rendered accessibility gate must remain verified")
    if n3ds10.get("n3ds10c13CompletedOn") != "2026-09-12" \
            or n3ds10.get("virtualControlsProductStatus") != "PASSED" \
            or n3ds10.get("virtualControlsDigitalButtons") != 15 \
            or n3ds10.get("virtualControlsAnalogInputs") != ["CIRCLE_PAD", "C_STICK"] \
            or n3ds10.get("virtualControlsTouchPassThroughPolicy") != (
                "ONLY_BOUNDED_CHILD_HIT_TARGETS_OVERLAY_SURFACE_REMAINS_TOUCHABLE") \
            or n3ds10.get("virtualControlsResetBoundaries") != [
                "PAUSE", "DESTROY", "FAILURE", "DISABLE"] \
            or n3ds10.get("virtualControlsLocaleTags") != [
                "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"] \
            or n3ds10.get("virtualControlsAccessibilityLabels") != 17:
        errors.append("N3DS-10C13 virtual controls contract must remain verified")
    if n3ds10.get("userVisible") is not False:
        errors.append("N3DS-10 must remain hidden until the product gates pass")
    if n3ds10.get("n3ds10c14CompletedOn") != "2026-09-12" \
            or n3ds10.get("standardMiiFallbackStatus") != "PASSED" \
            or n3ds10.get("standardMiiFallbackPolicy") != (
                "PINNED_AZAHAR_DEFAULT_MII_SELECTOR_WHEN_PERSONAL_DATABASE_UNAVAILABLE") \
            or n3ds10.get("personalMiiDatabaseRequirement") != (
                "OPTIONAL_SAF_IMPORT_NOT_RELEASE_GATE") \
            or n3ds10.get("fallbackAllowsLaunch") is not True:
        errors.append("N3DS-10C14 standard-Mii fallback contract changed")
    if n3ds10.get("systemDataProvisionPolicy") != (
            "OPTIONAL_USER_OWNED_SAF_IMPORT_OR_PINNED_AZAHAR_STANDARD_MII_"
            "FALLBACK_NO_DOWNLOAD_GENERATION_OR_BUNDLING"):
        errors.append("N3DS-10 must not provide, generate or download Mii/system data")
    if n3ds10.get("miiImportMaximumBytes") != 1024 * 1024:
        errors.append("N3DS-10 Mii SAF import must retain its one MiB bound")
    if n3ds10.get("activeCoreImportAllowed") is not False:
        errors.append("N3DS-10 must forbid Mii import while a core session is active")
    if n3ds10.get("existingDatabasePreservedOnPrecommitFailure") is not True:
        errors.append("N3DS-10 Mii import must preserve the prior database on failure")
    if n3ds10.get("existingSystemsModified") is not False:
        errors.append("N3DS-10 foundation must not modify existing emulator systems")
    if n3ds10.get("baseAppStaticDependencyOnModule") is not False:
        errors.append("N3DS-10 must preserve the isolated base-to-feature boundary")
    if n3ds10.get("controllerCreatedOnlyWhenReadinessAllows") is not True \
            or n3ds10.get("miiPickerCreatedOnlyAfterNativeSessionClose") is not True \
            or n3ds10.get("debugAndroidHostUsesCoordinator") is not True:
        errors.append("N3DS-10C1 host/readiness/Mii ordering changed")
    if n3ds10.get("readinessPresentationModelConsumedByHost") is not True \
            or n3ds10.get("readinessPresentationPrimaryActionFromOrderedIssue") is not True \
            or n3ds10.get("readinessPresentationPreservesAllMessages") is not True:
        errors.append("N3DS-10C2 host presentation contract changed")
    if n3ds10.get("readinessLocaleTags") != [
            "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"] \
            or n3ds10.get("readinessIssueMessages") != 11 \
            or n3ds10.get("readinessActionLabels") != 6:
        errors.append("N3DS-10C2 localized readiness resource coverage changed")
    if n3ds10.get("readinessDialogPolicy") != (
            "SINGLE_ACTIVE_DIALOG_DISMISS_THEN_SINGLE_TYPED_CALLBACK") \
            or n3ds10.get("readinessReadyDialogSuppressed") is not True \
            or n3ds10.get("readinessWarningContinueAlternative") is not True \
            or n3ds10.get("readinessDialogProductStyleAdapterPending") is not False:
        errors.append("N3DS-10C3 readiness dialog lifecycle or action policy changed")
    if n3ds10.get("readinessExternalIntentActions") != ["OPEN_STORAGE", "IMPORT_MII"] \
            or n3ds10.get("readinessInternalCallbackActions") != [
                "UNDERSTOOD", "RETRY", "EXTRACT_CONTENT", "CONTINUE"] \
            or n3ds10.get("readinessIgnoredActions") != ["NONE"] \
            or n3ds10.get("miiPickerCreatedAfterRealSessionSuspend") is not True \
            or n3ds10.get("externalIntentLaunchFailureReported") is not True:
        errors.append("N3DS-10C4 readiness action effects changed")
    if n3ds10.get("miiImportRunsOffUiThread") is not True \
            or n3ds10.get("concurrentMiiImportsAllowed") is not False \
            or n3ds10.get("cancelledMiiSelectionIgnored") is not True \
            or n3ds10.get("callbacksAfterWorkflowCloseAllowed") is not False \
            or n3ds10.get("invalidSelectedMiiPreservesExistingDatabase") is not True \
            or n3ds10.get("syntheticSafProviderCommittedDebugOnly") is not True:
        errors.append("N3DS-10C5 asynchronous Mii import safety contract changed")
    if n3ds10.get("miiRecoveryRepreflightRunsOffUiThread") is not True \
            or n3ds10.get("miiFallbackCreatesControllerWithoutPersonalDatabase") is not True \
            or n3ds10.get("miiOptionalPausedControllerReused") is not True \
            or n3ds10.get("miiRecoveryAcceptsOnlyCurrentImportMiiAction") is not True:
        errors.append("N3DS-10C6 Mii recovery ordering or product boundary changed")
    if n3ds10.get("activityResultRegisteredBeforeStarted") is not True \
            or n3ds10.get("cancelledPickerRestoresReadiness") is not True \
            or n3ds10.get("selectedMiiResultImportsRepreflightsAndDeliversOnMain") is not True \
            or n3ds10.get("activityResultLauncherActions") != ["IMPORT_MII"] \
            or n3ds10.get("externalActivityActions") != ["OPEN_STORAGE"] \
            or n3ds10.get("productFeatureEntryWiringPending") is not False:
        errors.append("N3DS-10C7 Activity Result routing or hidden product boundary changed")
    if n3ds10.get("experienceSettingsNamespace") \
            != "nintendo_3ds_experience_settings_v1" \
            or n3ds10.get("experienceSettingsLayers") != ["GLOBAL", "SPARSE_PER_GAME"] \
            or n3ds10.get("experienceSettingsPersistentIdPolicy") != (
                "N3DS_V1_CANDIDATE_AND_CONFIRMATION_SHA256_WITH_OPTIONAL_COLLISION_SHA256") \
            or n3ds10.get("experienceSettingsFields") != [
                "SCREEN_LAYOUT", "PERFORMANCE_PROFILE", "AUDIO_ENABLED", "AUDIO_VOLUME",
                "MICROPHONE_ENABLED"] \
            or n3ds10.get("experienceSettingsDefaultAudioEnabled") is not True \
            or n3ds10.get("experienceSettingsDefaultMicrophoneEnabled") is not False \
            or n3ds10.get("experienceSettingsCorruptionFallback") is not True \
            or n3ds10.get("experienceSettingsApplyBeforeNativeSession") is not True \
            or n3ds10.get("experienceSettingsProductUiPending") is not False:
        errors.append("N3DS-10C8 settings layering, privacy or hidden UI boundary changed")
    if n3ds10.get("experienceSettingsDialogScopes") != ["GLOBAL", "GAME"] \
            or n3ds10.get("experienceSettingsDialogPolicy") != (
                "SINGLE_ACTIVE_SCROLLABLE_DIALOG_DISMISS_THEN_SINGLE_CALLBACK") \
            or n3ds10.get("experienceSettingsDialogControls") != [
                "SCREEN_LAYOUT", "PERFORMANCE_PROFILE", "AUDIO_ENABLED", "AUDIO_VOLUME",
                "MICROPHONE_ENABLED"] \
            or n3ds10.get("experienceSettingsDialogResourceLocaleTags") != [
                "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"] \
            or n3ds10.get("experienceSettingsDialogLocalizedStrings") != 18 \
            or n3ds10.get("experienceSettingsDialogControlsLabelled") is not True \
            or n3ds10.get("experienceSettingsDialogArabicRtlPassed") is not True \
            or n3ds10.get("experienceSettingsDialogProductStyleAdapterPending") is not False:
        errors.append("N3DS-10C9 settings dialog scope, localization or accessibility changed")
    if n3ds10.get("productActivityExported") is not False \
            or n3ds10.get("productEntryRegisteredInIsolatedManifest") is not True \
            or n3ds10.get("futureDeliveryModuleLinkPending") is not True:
        errors.append("N3DS-10C10 hidden entry or deferred delivery boundary changed")
    if n3ds10.get("remainingGates") != []:
        errors.append("N3DS-10 remaining product gates changed")
    if n3ds10.get("deviceRequirementPolicy") != (
            "ARM64_AND_VULKAN_HARD_BLOCK_DEVICE_QUALIFICATION_WARNING_UNTIL_N3DS_11"):
        errors.append("N3DS-10B device readiness policy changed")
    if n3ds10.get("contentReadinessStates") != [
            "READY", "UNAVAILABLE", "ARCHIVE_REQUIRES_EXTRACTION", "ENCRYPTED"]:
        errors.append("N3DS-10B content readiness states changed")
    if n3ds10.get("miiRequirementPolicy") != (
            "CALLER_EVIDENCE_ONLY_UNKNOWN_NEVER_GUESSED"):
        errors.append("N3DS-10B must not guess title-specific Mii requirements")
    if n3ds10.get("readinessOrderingPolicy") != (
            "CORE_DEVICE_CONTENT_STORAGE_MII_THEN_DEVICE_QUALIFICATION"):
        errors.append("N3DS-10B actionable issue ordering changed")
    n3ds10_validation = n3ds10.get("validation", {})
    if n3ds10_validation.get("miiJvmTests") != 7 \
            or n3ds10_validation.get("miiInstrumentedTests") != 1 \
            or n3ds10_validation.get("miiInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("readinessJvmTests") != 8 \
            or n3ds10_validation.get("readinessInstrumentedTests") != 1 \
            or n3ds10_validation.get("readinessInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("readinessPresentationJvmTests") != 5 \
            or n3ds10_validation.get("readinessLocalizationInstrumentedTests") != 1 \
            or n3ds10_validation.get("readinessLocalizationInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("readinessArabicRtlPassed") is not True \
            or n3ds10_validation.get("readinessDialogInstrumentedTests") != 1 \
            or n3ds10_validation.get("readinessDialogInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("readinessDialogScenariosPassed") != 7 \
            or n3ds10_validation.get("readinessDialogActionCallbacks") != 4 \
            or n3ds10_validation.get("readinessDialogExternalIntents") != 2 \
            or n3ds10_validation.get("readinessActionCoordinatorInstrumentedFlows") != 8 \
            or n3ds10_validation.get("readinessActionFailureCallbackPassed") is not True \
            or n3ds10_validation.get("miiImportWorkflowInstrumentedTests") != 1 \
            or n3ds10_validation.get("miiImportWorkflowInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("miiImportWorkflowScenariosPassed") != 7 \
            or n3ds10_validation.get("miiRecoveryCoordinatorInstrumentedTests") != 1 \
            or n3ds10_validation.get("miiRecoveryCoordinatorInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("miiRecoveryRequiredPathsPassed") != 1 \
            or n3ds10_validation.get("miiRecoveryOptionalPathsPassed") != 1 \
            or n3ds10_validation.get("miiRecoveryRetryPathsPassed") != 1 \
            or n3ds10_validation.get("activityResultHostInstrumentedTests") != 1 \
            or n3ds10_validation.get("activityResultHostInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("activityResultHostScenariosPassed") != 2 \
            or n3ds10_validation.get("activityResultCancellationPathsPassed") != 1 \
            or n3ds10_validation.get("activityResultImportPathsPassed") != 1 \
            or n3ds10_validation.get("activityResultStyleAdapterInvocations") != 2 \
            or n3ds10_validation.get("activityResultLaunchCallbackOnMainThread") is not True \
            or n3ds10_validation.get("activityResultSyntheticMiiOnly") is not True \
            or n3ds10_validation.get("experienceSettingsJvmTests") != 4 \
            or n3ds10_validation.get("experienceSettingsJvmTestsPassed") != 4 \
            or n3ds10_validation.get("experienceSettingsInstrumentedTests") != 1 \
            or n3ds10_validation.get("experienceSettingsInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("experienceSettingsPersistenceScenariosPassed") != 7 \
            or n3ds10_validation.get("experienceSettingsInvalidPersistentIdRejected") is not True \
            or n3ds10_validation.get("experienceSettingsCorruptValuesRecovered") is not True \
            or n3ds10_validation.get("experienceSettingsAppliedWithoutNativeSession") is not True \
            or n3ds10_validation.get("experienceSettingsUiScopesPassed") != 2 \
            or n3ds10_validation.get("experienceSettingsUiSavePathsPassed") != 2 \
            or n3ds10_validation.get("experienceSettingsUiResetPathsPassed") != 1 \
            or n3ds10_validation.get("experienceSettingsUiStyleAdapterInvocations") != 3 \
            or n3ds10_validation.get("experienceSettingsUiLocalizedLocalesPassed") != 10 \
            or n3ds10_validation.get("experienceSettingsUiAccessibleControlsPassed") != 5 \
            or n3ds10_validation.get("productLaunchJvmTests") != 1 \
            or n3ds10_validation.get("productLaunchJvmTestsPassed") != 1 \
            or n3ds10_validation.get("productActivityInstrumentedTests") != 1 \
            or n3ds10_validation.get("productActivityInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("productActivityScenariosPassed") != 7 \
            or n3ds10_validation.get("productActivitySettingsSavePassed") is not True \
            or n3ds10_validation.get("productActivitySettingsCancelResumePassed") is not True \
            or n3ds10_validation.get("productActivityRecreationPassed") is not True \
            or n3ds10_validation.get("productActivityPhysicalInputPassed") is not True \
            or n3ds10_validation.get("productActivityPrivateContentOnly") is not True \
            or n3ds10_validation.get("libraryPresentationJvmTests") != 1 \
            or n3ds10_validation.get("libraryPresentationJvmTestsPassed") != 1 \
            or n3ds10_validation.get("libraryFilterInstrumentedTests") != 1 \
            or n3ds10_validation.get("libraryFilterInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("libraryFilterExistingSystemsPassed") != 4 \
            or n3ds10_validation.get("libraryFilterHiddenN3dsPassed") is not True \
            or n3ds10_validation.get("libraryHiddenQueryLabelPassed") is not True \
            or n3ds10_validation.get("libraryArtworkAlphaPassed") is not True \
            or n3ds10_validation.get("libraryPresentationLocalizedLocalesPassed") != 10 \
            or n3ds10_validation.get("accessibilityInstrumentedTests") != 1 \
            or n3ds10_validation.get("accessibilityInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("accessibilityRenderedLocalesPassed") != 10 \
            or n3ds10_validation.get("accessibilityRenderedDialogSurfacesPassed") != 20 \
            or n3ds10_validation.get("accessibilityLargeFontScalePercent") != 130 \
            or n3ds10_validation.get("accessibilityTalkBackEnabledRunPassed") is not True \
            or n3ds10_validation.get("accessibilityTalkBackSettingsRestored") is not True \
            or n3ds10_validation.get("accessibilityArabicRtlPassed") is not True \
            or n3ds10_validation.get("accessibilityBengaliClippingRegressionPassed") is not True \
            or n3ds10_validation.get("accessibilityTalkBackElapsedMillis") != 21468 \
            or n3ds10_validation.get("accessibilityStageCleanupPcLogicalBytesBefore") \
            != 37411461 \
            or n3ds10_validation.get("accessibilityStageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds10_validation.get(
                "accessibilityStageCleanupProtectedPackagePreserved") is not True \
            or n3ds10_validation.get("accessibilityStageDeviceTemporaryBytesRemoved") != 0 \
            or n3ds10_validation.get("stageCleanupGuardTestsPassed") != 1 \
            or n3ds10_validation.get("stageCleanupPcLogicalBytesBefore") != 14422920786 \
            or n3ds10_validation.get("stageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds10_validation.get("stageCleanupDeviceAvailableBytesGained") != 3780608 \
            or n3ds10_validation.get("stageCleanupInstrumentationPackageRemoved") is not True \
            or n3ds10_validation.get("stageCleanupProtectedPackagesPreserved") != [
                "com.mateussouza.emuorbit.advance",
                "com.mateussouza.emuorbit.n3ds.core.test"] \
            or n3ds10_validation.get("experimentalHostInstrumentedTests") != 1 \
            or n3ds10_validation.get("experimentalHostInstrumentedTestsPassed") != 1 \
            or n3ds10_validation.get("hostMiiActiveSessionRejected") is not True \
            or n3ds10_validation.get("hostMiiSessionCloseAndReopenPassed") is not True \
            or n3ds10_validation.get("device") != "SM-A376B_ANDROID_16" \
            or n3ds10_validation.get("isolatedBaseSuitePassed") != 26 \
            or n3ds10_validation.get("isolatedBaseSuiteTotal") != 26 \
            or n3ds10_validation.get("isolatedBaseSuiteRetries") != 0 \
            or n3ds10_validation.get("isolatedBaseSuiteElapsedMillis") != 158117 \
            or n3ds10_validation.get("currentBuildUpdateGatePassed") is not True \
            or n3ds10_validation.get("currentBuildUpdateGateElapsedMillis") != 31010 \
            or n3ds10_validation.get("currentBuildInteractiveGateStatus") != "PASSED" \
            or n3ds10_validation.get("currentBuildInteractiveGateElapsedMillis") != 31074 \
            or n3ds10_validation.get("currentBuildInteractiveFrames") != 1934 \
            or n3ds10_validation.get("currentBuildInteractiveInputEvents") != 97 \
            or n3ds10_validation.get("currentBuildInteractiveFrameGatePassed") is not True \
            or n3ds10_validation.get("currentBuildInteractiveInputGatePassed") is not True \
            or n3ds10_validation.get("privateMiiDataCommitted") is not False \
            or n3ds10_validation.get("standardMiiFallbackFocalTests") != 5 \
            or n3ds10_validation.get("standardMiiFallbackFocalTestsPassed") != 5 \
            or n3ds10_validation.get("standardMiiFallbackElapsedMillis") != 19500 \
            or n3ds10_validation.get("standardMiiFallbackFreshProcessPerTest") is not True \
            or n3ds10_validation.get("standardMiiFallbackRealCoreFramesPassed") is not True \
            or n3ds10_validation.get("standardMiiFallbackPersonalDatabaseUsed") is not False \
            or n3ds10_validation.get("selfContainedFeatureResourcesPackaged") is not True \
            or n3ds10_validation.get(
                "selfContainedQaHostsAndSyntheticProviderDeclared") is not True \
            or n3ds10_validation.get("n3ds10c14StageCleanupPcLogicalBytesBefore") \
            != 2531405160 \
            or n3ds10_validation.get("n3ds10c14StageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds10_validation.get("n3ds10c14StageCleanupExternalTempBytesRemoved") \
            != 65377183 \
            or n3ds10_validation.get("n3ds10c14StageCleanupDeviceAvailableBytesGained") \
            != 50483200 \
            or n3ds10_validation.get("n3ds10c14InstrumentationPackageRemoved") is not True \
            or n3ds10_validation.get("n3ds10c14ProtectedMainPackagePreserved") is not True:
        errors.append("N3DS-10 readiness test evidence changed")
    if n3ds10_validation.get("virtualControlsJvmTests") != 4 \
            or n3ds10_validation.get("virtualControlsJvmTestsPassed") != 4 \
            or n3ds10_validation.get("virtualControlsInstrumentedTests") != 2 \
            or n3ds10_validation.get("virtualControlsInstrumentedTestsPassed") != 2 \
            or n3ds10_validation.get("virtualControlsGalaxyA37FatalSignals") != 0 \
            or n3ds10_validation.get("virtualControlsResourceLocales") != 10 \
            or n3ds10_validation.get("virtualControlsResourceStringsPerLocale") != 17 \
            or n3ds10_validation.get("virtualControlsResourceCompilationPassed") is not True \
            or n3ds10_validation.get("virtualControlsLintAnalyzeDebugPassed") is not True \
            or n3ds10_validation.get("virtualControlsScopeAuditorTests") != 21 \
            or n3ds10_validation.get("virtualControlsScopeAuditorTestsPassed") != 21 \
            or n3ds10_validation.get("virtualControlsStageCleanupPcRecoveredGiB") != 2.25 \
            or n3ds10_validation.get("virtualControlsStageCleanupDeviceBytesRemoved") != 0 \
            or n3ds10_validation.get(
                "virtualControlsStageCleanupProtectedPackagesPreserved") is not True:
        errors.append("N3DS-10C13 virtual controls evidence changed")

    n3ds11 = manifest.get("n3ds11Decision", {})
    if n3ds11.get("status") != "DONE" \
            or n3ds11.get("completedSubsteps") != [
                "N3DS-11A", "N3DS-11B", "N3DS-11C", "N3DS-11D"] \
            or n3ds11.get("plannedSubsteps") != [
                "N3DS-11A", "N3DS-11B", "N3DS-11C", "N3DS-11D"] \
            or n3ds11.get("n3ds11dCompletedOn") != "2026-09-11":
        errors.append("N3DS-11 performance work must retain its verified substep sequence")
    expected_performance_options = [
        "citra_use_cpu_jit",
        "citra_cpu_clock_percentage",
        "citra_use_hw_shader",
        "citra_use_shader_jit",
        "citra_shaders_accurate_mul",
        "citra_use_disk_shader_cache",
        "citra_resolution_factor",
        "citra_texture_filter",
        "citra_texture_sampling",
        "citra_custom_textures",
        "citra_dump_textures",
    ]
    if n3ds11.get("profilePolicyStatus") != "PASSED" \
            or n3ds11.get("performanceProfiles") != [
                "CONSERVATIVE", "BALANCED", "PERFORMANCE"] \
            or n3ds11.get("defaultPerformanceProfile") != "BALANCED" \
            or n3ds11.get("profileResolutionFactors") != {
                "CONSERVATIVE": 1, "BALANCED": 2, "PERFORMANCE": 2} \
            or n3ds11.get("initialProfileResolutionFactorsBeforeLongRunCalibration") != {
                "CONSERVATIVE": 1, "BALANCED": 2, "PERFORMANCE": 3} \
            or n3ds11.get("pinnedAzaharCoreOptionKeys") != expected_performance_options \
            or n3ds11.get("profileApplicationPolicy") != (
                "APPLY_BEFORE_LAZY_NATIVE_SESSION_AND_CLOSE_OLD_SESSION_BEFORE_PROFILE_REOPEN") \
            or n3ds11.get("profileChangePreservesAccuracyOptions") is not False \
            or n3ds11.get("accurateShaderMultiplicationProfiles") != [
                "CONSERVATIVE", "BALANCED"] \
            or n3ds11.get("reducedAccuracyProfiles") != ["PERFORMANCE"] \
            or n3ds11.get("performanceAccuracyTradeoffDisclosure") != (
                "LOCALIZED_PROFILE_LABEL_IN_ALL_SUPPORTED_LOCALES") \
            or n3ds11.get("diskShaderCacheEnabledInEveryProfile") is not True \
            or n3ds11.get("performanceProfileSettingsLayers") != ["GLOBAL", "SPARSE_PER_GAME"] \
            or n3ds11.get("performanceProfileUiLocaleTags") != [
                "pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"] \
            or n3ds11.get("existingSystemsModified") is not False:
        errors.append("N3DS-11 safe performance profile contract changed")
    if n3ds11.get("measurementPolicyStatus") != "PASSED" \
            or n3ds11.get("safeCachePolicyStatus") != "PASSED" \
            or n3ds11.get("measurementPolicy") != (
                "AGGREGATE_FRAME_TIME_PERCENTILES_EFFECTIVE_SPEED_AUDIO_DROPS_PSS_THERMAL_"
                "AND_POWER_SAVE_WITHOUT_GAME_IDENTITY_OR_PATH") \
            or n3ds11.get("measurementPercentileCapacityFrames") != 120000 \
            or n3ds11.get("measurementResetPolicy") != (
                "RESET_AFTER_A_COMPLETED_PROFILE_TRANSITION_TO_AVOID_MIXED_PROFILE_RESULTS") \
            or n3ds11.get("regenerableCacheRoots") != [
                "AZAHAR_USER_CACHE", "AZAHAR_USER_SHADERS", "APP_PRIVATE_TRANSIENT_CACHE"] \
            or n3ds11.get("durableRootsExcludedFromCacheClear") != [
                "NAND", "SDMC", "SYSDATA", "CONFIG", "CHEATS", "BACKUPS"] \
            or n3ds11.get("cacheClearPolicy") != (
                "PREFLIGHT_ALL_EXACT_PRIVATE_ROOTS_REJECT_LINKS_CLOSE_AND_CHECKPOINT_CORE_"
                "THEN_DELETE_AND_RECREATE_ONLY_REGENERABLE_ROOTS"):
        errors.append("N3DS-11B aggregate measurement or safe cache contract changed")
    if n3ds11.get("remainingGates") != []:
        errors.append("N3DS-11 remaining performance gates changed")
    if n3ds11.get("crossDeviceGateDisposition") != (
            "CLOSED_ON_FIREBASE_PIXEL_5_PHYSICAL_ADRENO_620") \
            or n3ds11.get("crossDeviceSupportFloorStatus") != "PASSED_PHYSICAL_ADRENO" \
            or n3ds11.get("adrenoValidationExecution") != (
                "REMOTE_PHYSICAL_DEVICE_WITHOUT_DEVICE_OWNERSHIP_REQUIREMENT") \
            or n3ds11.get("adrenoSimulationPolicy") != (
                "AVD_SWIFTSHADER_OR_HOST_GPU_IS_DIAGNOSTIC_ONLY_AND_NEVER_COUNTS_"
                "AS_ADRENO_CERTIFICATION") \
            or n3ds11.get("remoteTestContentPolicy") != (
                "SELF_CONTAINED_SYNTHETIC_WORKLOAD_OR_REDISTRIBUTABLE_HOMEBREW_ONLY_"
                "NO_COMMERCIAL_ROM") \
            or n3ds11.get("remoteTestBillingPolicy") != (
                "PREFER_NO_COST_QUOTA_AND_NEVER_ENABLE_BILLING_WITHOUT_EXPLICIT_"
                "OWNER_AUTHORIZATION") \
            or n3ds11.get("stableReleasePolicyWithoutAdrenoEvidence") != (
                "KEEP_N3DS_BETA_EXPERIMENTAL_AND_MARK_ADRENO_UNVALIDATED"):
        errors.append("N3DS-11 remote Adreno pre-release gate policy changed")
    n3ds11_validation = n3ds11.get("validation", {})
    if n3ds11_validation.get("focusedJvmTestClasses") != 5 \
            or n3ds11_validation.get("focusedJvmTestsPassed") != 11 \
            or n3ds11_validation.get("androidTestApkCompiled") is not True \
            or n3ds11_validation.get("focusedPhysicalTestsPassed") != 35 \
            or n3ds11_validation.get("device") != "SM-A376B_ANDROID_16" \
            or n3ds11_validation.get("vulkanDevice") != "Samsung Xclipse 530" \
            or n3ds11_validation.get("firstProfile") != "CONSERVATIVE" \
            or n3ds11_validation.get("firstResolutionFactor") != 1 \
            or n3ds11_validation.get("secondProfile") != "PERFORMANCE" \
            or n3ds11_validation.get("secondResolutionFactor") != 3 \
            or n3ds11_validation.get("azaharPerformanceOptionRequests") != 11 \
            or n3ds11_validation.get("nativeSessionsOpened") != 2 \
            or n3ds11_validation.get("priorSessionClosedBeforeReopen") is not True \
            or n3ds11_validation.get("diskShaderCacheRequestObserved") is not True \
            or n3ds11_validation.get("measurementAndCachePhysicalTestElapsedMillis") != 5518 \
            or n3ds11_validation.get("measurementFrames") != 60 \
            or n3ds11_validation.get("measurementAverageFrameMillis") \
            != 38.388930333333334 \
            or n3ds11_validation.get("measurementP95FrameMillis") != 94.673398 \
            or n3ds11_validation.get("measurementP99FrameMillis") != 1125.831445 \
            or n3ds11_validation.get("measurementEffectiveSpeedPercent") \
            != 43.41529321590631 \
            or n3ds11_validation.get("measurementProcessPssKilobytes") != 1211822 \
            or n3ds11_validation.get("measurementThermalStatus") != 0 \
            or n3ds11_validation.get("regenerableCacheFilesRemoved") != 8 \
            or n3ds11_validation.get("regenerableCacheBytesRemoved") != 32659 \
            or n3ds11_validation.get("durableSavePreservedAfterCacheClear") is not True \
            or n3ds11_validation.get("nativeSessionReopenedAfterCacheClear") is not True \
            or n3ds11_validation.get("n3ds11bFocusedJvmTestsPassed") != 3 \
            or n3ds11_validation.get("n3ds11bFocusedPhysicalTestsPassed") != 1 \
            or n3ds11_validation.get("n3ds11bSourceAuditTestsPassed") != 26 \
            or n3ds11_validation.get("n3ds11bMappedImplementationSourceFiles") != 212 \
            or n3ds11_validation.get("n3ds11bStageCleanupPcLogicalBytesBefore") \
            != 38211855 \
            or n3ds11_validation.get("n3ds11bStageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds11_validation.get("n3ds11bStageDeviceTemporaryBytesRemoved") != 0 \
            or n3ds11_validation.get("n3ds11bFocalTemporaryRootsRemaining") != 0 \
            or n3ds11_validation.get("n3ds11bProtectedPackagePreserved") is not True \
            or n3ds11_validation.get("n3ds11bPrivateCoreBytesPreserved") != 35628952 \
            or n3ds11_validation.get("n3ds11bPrivateContentBytesPreserved") != 536870912 \
            or n3ds11_validation.get("localizedProfileLocalesPassed") != 10 \
            or n3ds11_validation.get("largeFontScale") != 1.3 \
            or n3ds11_validation.get("fatalSignalsObserved") != 0 \
            or n3ds11_validation.get("stageCleanupPcLogicalBytesBefore") != 38123875 \
            or n3ds11_validation.get("stageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds11_validation.get("stageCleanupDeviceTemporaryBytesRemoved") != 0 \
            or n3ds11_validation.get("stageCleanupProtectedPackagePreserved") is not True \
            or n3ds11_validation.get("stageCleanupPrivateCoreAndContentPreserved") is not True:
        errors.append("N3DS-11 focused validation evidence changed")
    if n3ds11_validation.get("n3ds11dFirebaseMatrixId") != "matrix-1g38thbeljpgy" \
            or n3ds11_validation.get("n3ds11dFirebaseDevice") != (
                "GOOGLE_PIXEL_5_REDFIN_API_30_PHYSICAL_ADRENO_620") \
            or n3ds11_validation.get("n3ds11dLongRunStatus") != "PASSED" \
            or n3ds11_validation.get("n3ds11dLongRunJUnitSeconds") != 1204.977 \
            or n3ds11_validation.get("n3ds11dLongRunElapsedMillis") != 1201009 \
            or n3ds11_validation.get("n3ds11dLongRunMeasuredFrames") != 107760 \
            or n3ds11_validation.get("n3ds11dLongRunEffectiveSpeedPercent") \
            != 149.95365062829035 \
            or n3ds11_validation.get("n3ds11dLongRunAudioDroppedFrames") != 0 \
            or n3ds11_validation.get("n3ds11dLongRunAudioOutputDroppedFrames") != 0 \
            or n3ds11_validation.get("n3ds11dLongRunAudioOutputFailures") != 0 \
            or n3ds11_validation.get("n3ds11dLongRunPssGrowthKilobytes") != 173588 \
            or n3ds11_validation.get("n3ds11dLongRunPeakThermalStatus") != 1 \
            or n3ds11_validation.get("n3ds11dLongRunPowerSaveObserved") is not False:
        errors.append("N3DS-11D physical Adreno long-run evidence changed")
    accepted_long_runs = n3ds11_validation.get("n3ds11cAcceptedLongRuns", {})
    expected_long_run_profiles = {
        "CONSERVATIVE": (1, True, 1201604, 71880, 100.00346922243766, 42668),
        "BALANCED": (2, True, 1201373, 71880, 100.08538193530237, 41124),
        "PERFORMANCE": (2, False, 1201367, 71880, 100.09108058666966, 41802),
    }
    if n3ds11.get("availableDeviceLongRunStatus") != "PASSED" \
            or n3ds11_validation.get("n3ds11cLongRunDurationMinutesPerProfile") != 20 \
            or n3ds11_validation.get("n3ds11cEffectiveSpeedFloorPercent") != 95.0 \
            or set(accepted_long_runs) != set(expected_long_run_profiles):
        errors.append("N3DS-11C available-device long-run contract changed")
    else:
        for profile, expected in expected_long_run_profiles.items():
            run = accepted_long_runs[profile]
            resolution, accurate, elapsed, frames, speed, growth = expected
            if run.get("resolutionFactor") != resolution \
                    or run.get("accurateShaderMultiplication") is not accurate \
                    or run.get("elapsedMillis") != elapsed \
                    or run.get("measuredFrames") != frames \
                    or run.get("effectiveSpeedPercent") != speed \
                    or run.get("audioDroppedFrames") != 0 \
                    or run.get("audioOutputFailures") != 0 \
                    or run.get("pssGrowthKilobytes") != growth \
                    or run.get("peakThermalStatus") != 0 \
                    or run.get("powerSaveObserved") is not False:
                errors.append(f"N3DS-11C {profile} long-run evidence changed")
    rejected_calibration = n3ds11_validation.get("n3ds11cRejectedCalibration", {})
    if rejected_calibration.get("profile") != "PERFORMANCE" \
            or rejected_calibration.get("resolutionFactor") != 3 \
            or rejected_calibration.get("elapsedMillis") != 1201268 \
            or rejected_calibration.get("measuredFrames") != 45960 \
            or rejected_calibration.get("effectiveSpeedPercent") != 64.01165749821432 \
            or rejected_calibration.get("reason") != "BELOW_95_PERCENT_EFFECTIVE_SPEED_FLOOR" \
            or n3ds11_validation.get("n3ds11cRecalibration") != (
                "PERFORMANCE_3X_TO_2X_AND_DISABLE_ACCURATE_SHADER_MULTIPLICATION_"
                "USING_PINNED_AZAHAR_OPTION") \
            or n3ds11_validation.get("n3ds11cProfileSwitchPhysicalTestPassed") is not True \
            or n3ds11_validation.get("n3ds11cProfileSwitchPhysicalTestElapsedMillis") != 3747 \
            or n3ds11_validation.get("n3ds11cLocalizedDisclosurePhysicalTestPassed") \
            is not True \
            or n3ds11_validation.get("n3ds11cLocalizedDisclosureLocalesPassed") != 10 \
            or n3ds11_validation.get("n3ds11cLocalizedDisclosureElapsedMillis") != 19647 \
            or n3ds11_validation.get("n3ds11cIsolatedRegressionPassed") != 26 \
            or n3ds11_validation.get("n3ds11cIsolatedRegressionTotal") != 26 \
            or n3ds11_validation.get("n3ds11cIsolatedRegressionRetries") != 0 \
            or n3ds11_validation.get("n3ds11cIsolatedRegressionElapsedMillis") != 52656 \
            or n3ds11_validation.get("n3ds11cJvmTestsPassed") != 2 \
            or n3ds11_validation.get("n3ds11cSourceAuditTestsPassed") != 27 \
            or n3ds11_validation.get("n3ds11cMappedImplementationSourceFiles") != 212 \
            or n3ds11_validation.get("n3ds11cStageCleanupPcLogicalBytesBefore") \
            != 39186002 \
            or n3ds11_validation.get("n3ds11cStageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds11_validation.get("n3ds11cStageDeviceTemporaryBytesRemoved") != 0 \
            or n3ds11_validation.get("n3ds11cLongRunTemporaryRootsRemaining") != 0 \
            or n3ds11_validation.get("n3ds11cProtectedPackagePreserved") is not True \
            or n3ds11_validation.get("n3ds11cPrivateCoreBytesPreserved") != 35628952 \
            or n3ds11_validation.get("n3ds11cPrivateRegressionContentBytesPreserved") != [
                536870912, 1073741824, 536870912] \
            or n3ds11_validation.get("n3ds11cPrivatePrimaryContentBytesPreserved") \
            != 536870912:
        errors.append("N3DS-11C recalibration or focused validation evidence changed")

    n3ds12 = manifest.get("n3ds12Decision", {})
    if n3ds12.get("status") != "DONE" \
            or n3ds12.get("completedOn") != "2026-09-11" \
            or n3ds12.get("completedSubsteps") != [
                "N3DS-12A", "N3DS-12B", "N3DS-12C", "N3DS-12D1",
                "N3DS-12D2", "N3DS-12D3", "N3DS-12E"] \
            or n3ds12.get("plannedSubsteps") != [
                "N3DS-12A", "N3DS-12B", "N3DS-12C", "N3DS-12D", "N3DS-12E"] \
            or n3ds12.get("n3ds12aScope") != (
                "JNI_HOST_HARDENING_AND_PACKAGED_ELF_AUDIT") \
            or n3ds12.get("n3ds12bScope") != (
                "PINNED_AZAHAR_CORE_HARDENING_REPRODUCTION_AND_PACKAGED_ELF_AUDIT") \
            or n3ds12.get("n3ds12cScope") != (
                "BASE_VERSUS_ON_DEMAND_DELIVERY_DECISION") \
            or n3ds12.get("n3ds12d1Scope") != (
                "ON_DEMAND_DYNAMIC_FEATURE_AND_AAB_BOUNDARY") \
            or n3ds12.get("n3ds12d2Scope") != (
                "BASE_INSTALL_COORDINATOR_AND_SPLITCOMPAT") \
            or n3ds12.get("n3ds12d3Scope") != (
                "PHYSICAL_BUNDLETOOL_INSTALL_UPDATE_OFFLINE_AND_PACKAGED_CORE_PROOF") \
            or n3ds12.get("n3ds12eScope") != (
                "ARTIFACT_ONLY_AAB_APK_AND_SECURITY_LEDGER_FINAL_GATE") \
            or n3ds12.get("deliveryDecision") != (
                "PLAY_FEATURE_DELIVERY_ON_DEMAND_DYNAMIC_FEATURE") \
            or n3ds12.get("deliveryModule") != "nintendo3dscore" \
            or n3ds12.get("deliveryBaseModule") != "app" \
            or n3ds12.get("deliveryMinimumApi") != 26 \
            or n3ds12.get("deliveryAbi") != "arm64-v8a" \
            or n3ds12.get("deliveryFusingForUniversalAudit") is not True \
            or n3ds12.get("deliveryInstantEnabled") is not False \
            or n3ds12.get("baseStaticDependencyOnFeatureAllowed") is not False \
            or n3ds12.get("featureDependsOnBaseContract") is not True \
            or n3ds12.get("splitCompatRequired") is not True \
            or n3ds12.get("exportedFeatureComponentsAllowed") is not False \
            or n3ds12.get("coreInBaseAllowed") is not False \
            or n3ds12.get("remoteCoreDownloadAllowed") is not False \
            or n3ds12.get("offlineAfterInstallRequired") is not True \
            or n3ds12.get("playFeatureDataSafetyReviewRequired") is not True \
            or n3ds12.get("publicCorrespondingSourceRequiredBeforeDistribution") \
            is not True \
            or n3ds12.get("deliveryArchitectureRecord") != (
                "nintendo3dscore/DELIVERY_ARCHITECTURE.md") \
            or n3ds12.get("deliveryGradleStatus") != "DYNAMIC_FEATURE_ATTACHED" \
            or n3ds12.get("corePackagingInputRequired") is not True \
            or n3ds12.get("corePackagingIdentityFailClosed") is not True \
            or n3ds12.get("deliveryBundleAudit") != (
                "scripts/audit-nintendo3ds-bundle.py") \
            or n3ds12.get("deliveryArtifactOnlyAudit") != (
                "scripts/audit-nintendo3ds-packaged-artifacts.py") \
            or n3ds12.get("deliveryArtifactGate") != (
                "scripts/test-nintendo3ds-artifact-gate.ps1") \
            or n3ds12.get("completedDeliverySlices") != [
                "N3DS-12D1_DYNAMIC_FEATURE_AAB_BOUNDARY",
                "N3DS-12D2_BASE_INSTALL_COORDINATOR_AND_SPLITCOMPAT",
                "N3DS-12D3_BUNDLETOOL_INSTALL_UPDATE_AND_OFFLINE_PROOFS",
                "N3DS-12E_ARTIFACT_ONLY_AND_SECURITY_LEDGER_FINAL_GATE"] \
            or n3ds12.get("remainingSubsteps") != [] \
            or n3ds12.get("remainingDeliverySlices") != [] \
            or n3ds12.get("thinLtoEnabled") is not True \
            or n3ds12.get("hiddenVisibilityEnabled") is not True \
            or n3ds12.get("staticArchiveExportsExcluded") is not True \
            or n3ds12.get("sourcePrefixMapsEnabled") is not True \
            or n3ds12.get("compilerIdentitySectionRemoved") is not True \
            or n3ds12.get("legalNoticesOrAttributionRemoved") is not False \
            or n3ds12.get("corePayloadChanged") is not True \
            or n3ds12.get("existingSystemsModified") is not False:
        errors.append("N3DS-12 wrapper/core hardening contract changed")
    n3ds12_validation = n3ds12.get("validation", {})
    if n3ds12_validation.get("focusedPythonTestsPassed") != 3 \
            or n3ds12_validation.get("releaseAarCompiled") is not True \
            or n3ds12_validation.get("packagedBootstrapArtifactAuditStatus") != "PASSED" \
            or n3ds12_validation.get("packagedBootstrapBytes") != 299992 \
            or n3ds12_validation.get("packagedBootstrapSha256") != (
                "30af7b79f6db027f0ad57b601d30285f32ccd13084870551429c5f1633e678f9") \
            or n3ds12_validation.get("releaseAarBytes") != 306097 \
            or n3ds12_validation.get("releaseAarSha256") != (
                "9bb8810ba1b591e6c7ea7d8cb2623579562064192ce3dbc7833323719771f03a") \
            or n3ds12_validation.get("bootstrapSoname") != (
                "libemuorbit_n3ds_bootstrap.so") \
            or n3ds12_validation.get("bootstrapDefinedExports") != ["JNI_OnLoad"] \
            or n3ds12_validation.get("bootstrapRuntimeDependencies") != [
                "libandroid.so", "libc.so", "libdl.so", "liblog.so", "libm.so"] \
            or n3ds12_validation.get("bootstrapElfClass") != "ELF64_AARCH64" \
            or n3ds12_validation.get("bootstrapLoadAlignmentBytes") != 16384 \
            or n3ds12_validation.get("bootstrapRelroNowNx") is not True \
            or n3ds12_validation.get("bootstrapBuildIdPresent") is not True \
            or n3ds12_validation.get("bootstrapForbiddenPackagedSections") != [] \
            or n3ds12_validation.get("bootstrapCheckoutPathPresent") is not False \
            or n3ds12_validation.get("isolatedPhysicalRegressionPassed") != 26 \
            or n3ds12_validation.get("isolatedPhysicalRegressionTotal") != 26 \
            or n3ds12_validation.get("isolatedPhysicalRegressionRetries") != 0 \
            or n3ds12_validation.get("isolatedPhysicalRegressionElapsedMillis") != 52478 \
            or n3ds12_validation.get("mappedImplementationSourceFiles") != 282 \
            or n3ds12_validation.get("stageCleanupPcLogicalBytesBefore") != 49040177 \
            or n3ds12_validation.get("stageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds12_validation.get("stageCleanupDeviceTemporaryBytesRemoved") != 0 \
            or n3ds12_validation.get("device") != "SM-A376B_ANDROID_16" \
            or n3ds12_validation.get("vulkanDevice") != "Samsung Xclipse 530" \
            or n3ds12_validation.get("privateTestPackagePreserved") is not True \
            or n3ds12_validation.get("privateCoreBytesPreserved") != 35628952 \
            or n3ds12_validation.get("hardenedCoreCandidatePreserved") is not True \
            or n3ds12_validation.get("hardenedCoreBytes") != 23157736 \
            or n3ds12_validation.get("hardenedCoreSha256") != (
                "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c") \
            or n3ds12_validation.get("hardenedCoreUnstrippedBytes") != 405375968 \
            or n3ds12_validation.get("hardenedCoreUnstrippedSha256") != (
                "2ddb573c55eb21f049f2e7abc90bdf20bc541ce6c1b5f5230ccb89cde112a597") \
            or n3ds12_validation.get("hardenedCoreInitialDefinedExportCount") != 54610 \
            or n3ds12_validation.get("hardenedCoreDefinedExportCount") != 25 \
            or n3ds12_validation.get("hardenedCoreArtifactAuditStatus") != "PASSED" \
            or n3ds12_validation.get("hardenedCoreSoname") != "azahar_libretro.so" \
            or n3ds12_validation.get("hardenedCoreRuntimeDependencies") != [
                "libc.so", "libdl.so", "liblog.so", "libm.so"] \
            or n3ds12_validation.get("hardenedCoreElfClass") != "ELF64_AARCH64" \
            or n3ds12_validation.get("hardenedCoreLoadAlignmentBytes") != 16384 \
            or n3ds12_validation.get("hardenedCoreRelroNowNx") is not True \
            or n3ds12_validation.get("hardenedCoreBuildIdPresent") is not True \
            or n3ds12_validation.get("hardenedCoreForbiddenPackagedSections") != [] \
            or n3ds12_validation.get("hardenedCoreCheckoutPathPresent") is not False \
            or n3ds12_validation.get("hardenedCoreReproductionsPassed") != 2 \
            or n3ds12_validation.get("n3ds12bNativeArtifactAuditTestsPassed") != 4 \
            or n3ds12_validation.get("n3ds12bUpstreamAuditTestsPassed") != 5 \
            or n3ds12_validation.get("n3ds12bIsolatedPhysicalRegressionPassed") != 26 \
            or n3ds12_validation.get("n3ds12bIsolatedPhysicalRegressionTotal") != 26 \
            or n3ds12_validation.get("n3ds12bIsolatedPhysicalRegressionRetries") != 0 \
            or n3ds12_validation.get("n3ds12bIsolatedPhysicalRegressionElapsedMillis") \
            != 51020 \
            or n3ds12_validation.get("n3ds12bStageCleanupPcLogicalBytesBefore") \
            != 5710628432 \
            or n3ds12_validation.get("n3ds12bStageCleanupPcLogicalBytesAfter") \
            != 82485380 \
            or n3ds12_validation.get("n3ds12bStageCleanupPcLogicalBytesRemoved") \
            != 5628143052 \
            or n3ds12_validation.get("n3ds12bRetainedLocalEvidenceBytes") != 58786688 \
            or n3ds12_validation.get("n3ds12bStageCleanupDeviceTemporaryBytesRemoved") \
            != 81944424 \
            or n3ds12_validation.get("n3ds12bDeviceTemporaryArtifactsRemaining") != 0 \
            or n3ds12_validation.get("n3ds12cCoreBytesUsedForDecision") != 23157736 \
            or n3ds12_validation.get("n3ds12cOfficialAndroidReferencesVerified") != 4 \
            or n3ds12_validation.get("n3ds12cBaseHasStaticFeatureDependency") is not False \
            or n3ds12_validation.get("n3ds12cModuleStillLibraryUntilN3ds12d") is not True \
            or n3ds12_validation.get("n3ds12cAndroidBuildOrDeviceRegressionRequired") \
            is not False \
            or n3ds12_validation.get("n3ds12cStageBuildBytesGenerated") != 0 \
            or n3ds12_validation.get("n3ds12d1DebugAabCompiled") is not True \
            or n3ds12_validation.get("n3ds12d1BundleAuditStatus") != "PASSED" \
            or n3ds12_validation.get("n3ds12d1BundleBytes") != 41146026 \
            or n3ds12_validation.get("n3ds12d1BundleSha256") != (
                "e8c4ab0580189cae7602d85ab3562621ecbe92df5f9f759c0420ffdb794b8ae6") \
            or n3ds12_validation.get("n3ds12d1BaseUncompressedBytes") != 60824056 \
            or n3ds12_validation.get("n3ds12d1BaseCompressedBytes") != 32499891 \
            or n3ds12_validation.get("n3ds12d1BaseEntryCount") != 1442 \
            or n3ds12_validation.get("n3ds12d1FeatureUncompressedBytes") != 23908254 \
            or n3ds12_validation.get("n3ds12d1FeatureCompressedBytes") != 8248036 \
            or n3ds12_validation.get("n3ds12d1FeatureEntryCount") != 10 \
            or n3ds12_validation.get("n3ds12d1BaseDexEntries") != 21 \
            or n3ds12_validation.get("n3ds12d1FeatureDexEntries") != 2 \
            or n3ds12_validation.get("n3ds12d1FeatureNativeEntries") != [
                "nintendo3dscore/lib/arm64-v8a/libazahar_libretro.so",
                "nintendo3dscore/lib/arm64-v8a/libemuorbit_n3ds_bootstrap.so"] \
            or n3ds12_validation.get("n3ds12d1PackagedCoreBytes") != 23157736 \
            or n3ds12_validation.get("n3ds12d1PackagedCoreSha256") != (
                "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c") \
            or n3ds12_validation.get("n3ds12d1PackagedBootstrapBytes") != 420488 \
            or n3ds12_validation.get("n3ds12d1PackagedBootstrapSha256") != (
                "610b6e3ba996f1c3429570a0f5d323c11f61cf8f5ae1124aabd37a5cd68d1f6d") \
            or n3ds12_validation.get("n3ds12d1FeatureUnitTestsPassed") != 67 \
            or n3ds12_validation.get("n3ds12d1FeatureUnitTestClasses") != 17 \
            or n3ds12_validation.get("n3ds12d1BundleAuditTestsPassed") != 4 \
            or n3ds12_validation.get("n3ds12d1SourceScopeTestsPassed") != 20 \
            or n3ds12_validation.get("n3ds12d1ComplianceTestsPassed") != 4 \
            or n3ds12_validation.get("n3ds12d1DependencyVerificationTestsPassed") \
            != 8 \
            or n3ds12_validation.get("n3ds12d1MissingCoreInputRejected") is not True \
            or n3ds12_validation.get("n3ds12d1DependencyVerificationStrict") is not True \
            or n3ds12_validation.get("n3ds12d1DeviceInstallAttempted") is not False \
            or n3ds12_validation.get("n3ds12d1StageCleanupPcLogicalBytesBefore") \
            != 2872629052 \
            or n3ds12_validation.get("n3ds12d1StageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds12_validation.get("n3ds12d1StageCleanupPcLogicalBytesRemoved") \
            != 2872629052 \
            or n3ds12_validation.get("n3ds12d1PythonBytecodeCacheBytesRemoved") \
            != 766513 \
            or n3ds12_validation.get(
                "n3ds12d1AccidentalWorkspaceGradleCacheBytesRemoved") != 1110621898 \
            or n3ds12_validation.get("n3ds12d1NativeAuditTemporaryBytesRemoved") \
            != 23578224 \
            or n3ds12_validation.get("n3ds12d1StageCleanupDeviceTemporaryBytesRemoved") \
            != 0 \
            or n3ds12_validation.get("n3ds12d1DeviceTemporaryArtifactsCreated") != 0 \
            or n3ds12_validation.get("privatePrimaryContentBytesPreserved") != 536870912 \
            or n3ds12_validation.get("privateRegressionContentBytesPreserved") != [
                536870912, 1073741824, 536870912]:
        errors.append("N3DS-12 wrapper/core focused validation evidence changed")

    if n3ds12.get("n3ds12d2CompletedOn") != "2026-09-11" \
            or n3ds12_validation.get("n3ds12d2PlayFeatureDeliveryVersion") != "2.1.0" \
            or n3ds12_validation.get("n3ds12d2BaseDeliveryUnitTestsPassed") != 14 \
            or n3ds12_validation.get("n3ds12d2BaseDeliveryUnitTestClasses") != 3 \
            or n3ds12_validation.get("n3ds12d2SourceScopeTestsPassed") != 20 \
            or n3ds12_validation.get(
                "n3ds12d2DependencyVerificationTestsPassed") != 8 \
            or n3ds12_validation.get("n3ds12d2MarkdownGatePassed") is not True \
            or n3ds12_validation.get("n3ds12d2RepositoryPolicyGatePassed") is not True \
            or n3ds12_validation.get("n3ds12d2BaseJavaCompiled") is not True \
            or n3ds12_validation.get("n3ds12d2FeatureJavaCompiled") is not True \
            or n3ds12_validation.get("n3ds12d2SplitCompatBaseInstalled") is not True \
            or n3ds12_validation.get(
                "n3ds12d2SplitCompatFeatureActivityInstalled") is not True \
            or n3ds12_validation.get(
                "n3ds12d2RestoresPlaySessionsAfterProcessRecreation") is not True \
            or n3ds12_validation.get("n3ds12d2CancellationAndRetryCovered") is not True \
            or n3ds12_validation.get(
                "n3ds12d2BaseStaticFeatureClassDependency") is not False \
            or n3ds12_validation.get(
                "n3ds12d2DependencyArtifactsIndependentlyVerified") != 21 \
            or n3ds12_validation.get("n3ds12d2NativeCoreOrLoaderChanged") is not False \
            or n3ds12_validation.get("n3ds12d2PhysicalRegressionBaselineReused") \
            != "N3DS-12B_26_OF_26" \
            or n3ds12_validation.get("n3ds12d2DeviceInstallAttempted") is not False \
            or n3ds12_validation.get("n3ds12d2StageCleanupPcLogicalBytesBefore") \
            != 76468362 \
            or n3ds12_validation.get("n3ds12d2StageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds12_validation.get("n3ds12d2StageCleanupPcLogicalBytesRemoved") \
            != 76468362 \
            or n3ds12_validation.get("n3ds12d2PythonBytecodeCacheBytesRemoved") \
            != 255237 \
            or n3ds12_validation.get(
                "n3ds12d2StageCleanupDeviceTemporaryBytesRemoved") != 0 \
            or n3ds12_validation.get("n3ds12d2DeviceTemporaryArtifactsCreated") != 0:
        errors.append("N3DS-12D2 delivery coordinator validation evidence changed")

    if n3ds12.get("n3ds12d3CompletedOn") != "2026-09-11" \
            or n3ds12_validation.get("n3ds12d3BundletoolVersion") != "1.18.3" \
            or n3ds12_validation.get("n3ds12d3Package") != (
                "com.mateussouza.emuorbit.advance.n3ds.deliverytest") \
            or n3ds12_validation.get("n3ds12d3VersionCodes") != [900100, 900101] \
            or n3ds12_validation.get("n3ds12d3V1BundleBytes") != 41209398 \
            or n3ds12_validation.get("n3ds12d3V2BundleBytes") != 41209561 \
            or n3ds12_validation.get("n3ds12d3V2BundleSha256") != (
                "3420800a5cbb2cbd429a8bf9a036e07f76ea3874ee7edc2ad15c82eb6e6151ed") \
            or n3ds12_validation.get("n3ds12d3UniversalApkBytes") != 60198820 \
            or n3ds12_validation.get("n3ds12d3BaseUncompressedBytes") != 60993895 \
            or n3ds12_validation.get("n3ds12d3BaseCompressedBytes") != 32563344 \
            or n3ds12_validation.get("n3ds12d3BaseEntryCount") != 1443 \
            or n3ds12_validation.get("n3ds12d3FeatureUncompressedBytes") != 23897957 \
            or n3ds12_validation.get("n3ds12d3FeatureCompressedBytes") != 8248173 \
            or n3ds12_validation.get("n3ds12d3FeatureEntryCount") != 9 \
            or n3ds12_validation.get("n3ds12d3InitialFeatureAbsent") is not True \
            or n3ds12_validation.get("n3ds12d3CancellationPassed") is not True \
            or n3ds12_validation.get("n3ds12d3NetworkFailureErrorCode") != -6 \
            or n3ds12_validation.get("n3ds12d3RetryPassed") is not True \
            or n3ds12_validation.get("n3ds12d3ProcessRecreationPassed") is not True \
            or n3ds12_validation.get("n3ds12d3UpdateWithoutDataClearPassed") is not True \
            or n3ds12_validation.get("n3ds12d3OfflineExecutionPassed") is not True \
            or n3ds12_validation.get("n3ds12d3UniversalFusingPassed") is not True \
            or n3ds12_validation.get(
                "n3ds12d3PackagedCoreLoadedWithSplitInstallHelper") is not True \
            or n3ds12_validation.get("n3ds12d3PackagedCoreIdentity") != "Azahar:fbd3fb0" \
            or n3ds12_validation.get("n3ds12d3PresentedFrames") != 60 \
            or n3ds12_validation.get("n3ds12d3VideoFrames") != 60 \
            or n3ds12_validation.get("n3ds12d3Renderer") != "VULKAN" \
            or n3ds12_validation.get("n3ds12d3VulkanDevice") != "Samsung Xclipse 530" \
            or n3ds12_validation.get("n3ds12d3HomebrewBytes") != 713384 \
            or n3ds12_validation.get("n3ds12d3HomebrewSha256") != (
                "00fb87d97ecb866a99902740ab67e38e05f81d74295e0c3774eb62b90b0a335b") \
            or n3ds12_validation.get("n3ds12d3AppSourceArchivePresent") is not False \
            or n3ds12_validation.get("n3ds12d3AppSbomPresent") is not False \
            or n3ds12_validation.get("n3ds12d3MinimumLegalNoticePresent") is not True \
            or n3ds12_validation.get("n3ds12d3PublicSourceRepository") != (
                "https://github.com/MateusSouzaAlves/GLP-EmuOrbitAzahar3ds") \
            or n3ds12_validation.get("n3ds12d3PublicSourceCommit") != (
                "aa26f156fb4913836ba3821ba4cf22477eaa73d7") \
            or n3ds12_validation.get("n3ds12d3LocalGplCopiesRemoved") is not True \
            or n3ds12_validation.get("n3ds12d3BundleAuditStatus") != "PASSED" \
            or n3ds12_validation.get("n3ds12d3BundleAuditTestsPassed") != 6 \
            or n3ds12_validation.get("n3ds12d3SourceScopeTestsPassed") != 20 \
            or n3ds12_validation.get("n3ds12d3DependencyVerificationTestsPassed") != 8 \
            or n3ds12_validation.get("n3ds12d3MappedImplementationSourceFiles") != 240 \
            or n3ds12_validation.get("n3ds12d3IsolatedPhysicalRegressionPassed") != 26 \
            or n3ds12_validation.get("n3ds12d3IsolatedPhysicalRegressionTotal") != 26 \
            or n3ds12_validation.get("n3ds12d3IsolatedPhysicalRegressionRetries") != 0 \
            or n3ds12_validation.get(
                "n3ds12d3IsolatedPhysicalRegressionElapsedMillis") != 49254 \
            or n3ds12_validation.get("n3ds12d3StageCleanupPcRecoveredGiB") != 2.59 \
            or n3ds12_validation.get("n3ds12d3StageCleanupDeviceRecoveredBytes") != 0 \
            or n3ds12_validation.get("n3ds12d3DeliveryTestPackageRemaining") is not False \
            or n3ds12_validation.get("n3ds12d3DeviceTemporaryRootsRemaining") != 0 \
            or n3ds12_validation.get("n3ds12d3MainPackagePreserved") is not True \
            or n3ds12_validation.get("n3ds12d3PrivateTestPackagePreserved") is not True \
            or n3ds12_validation.get("n3ds12d3PrivateCoreAndContentPreserved") is not True:
        errors.append("N3DS-12D3 physical feature delivery evidence changed")

    if n3ds12.get("n3ds12eCompletedOn") != "2026-09-11" \
            or n3ds12_validation.get("n3ds12eArtifactGateStatus") != "PASSED" \
            or n3ds12_validation.get("n3ds12eBundletoolVersion") != "1.18.3" \
            or n3ds12_validation.get("n3ds12eBundleBytes") != 41209431 \
            or n3ds12_validation.get("n3ds12eBundleSha256") != (
                "e744f6a4b47deeed95261b750089643ee58f5fbd1327157bd4b786f228d2dbd6") \
            or n3ds12_validation.get("n3ds12eUniversalApkBytes") != 60198820 \
            or n3ds12_validation.get("n3ds12eUniversalApkSha256") != (
                "5f8d234dd8327b6ef2b6e3dde4a7aafa29404392b62f098d010d740c25ca56ad") \
            or n3ds12_validation.get("n3ds12eBaseUncompressedBytes") != 60993566 \
            or n3ds12_validation.get("n3ds12eBaseCompressedBytes") != 32563264 \
            or n3ds12_validation.get("n3ds12eBaseEntryCount") != 1443 \
            or n3ds12_validation.get("n3ds12eFeatureUncompressedBytes") != 23897863 \
            or n3ds12_validation.get("n3ds12eFeatureCompressedBytes") != 8248118 \
            or n3ds12_validation.get("n3ds12eFeatureEntryCount") != 9 \
            or n3ds12_validation.get("n3ds12eCoreBytes") != 23157736 \
            or n3ds12_validation.get("n3ds12eCoreSha256") != (
                "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c") \
            or n3ds12_validation.get("n3ds12eCoreSoname") != "azahar_libretro.so" \
            or n3ds12_validation.get("n3ds12eCoreDefinedExportCount") != 25 \
            or n3ds12_validation.get("n3ds12eBootstrapBytes") != 420712 \
            or n3ds12_validation.get("n3ds12eBootstrapSha256") != (
                "e3756164a5bbc52e294b71cdfd6159a882286b76a8de2b9d955ce1844cdbfd03") \
            or n3ds12_validation.get("n3ds12eBootstrapSoname") != (
                "libemuorbit_n3ds_bootstrap.so") \
            or n3ds12_validation.get("n3ds12eBootstrapDefinedExportCount") != 1 \
            or n3ds12_validation.get("n3ds12eBoundaryAuditStatus") != "PASSED" \
            or n3ds12_validation.get("n3ds12eArtifactOnlyAuditStatus") != "PASSED" \
            or n3ds12_validation.get("n3ds12eFocusedPythonTestsPassed") != 16 \
            or n3ds12_validation.get("n3ds12eBundleAuditTestsPassed") != 7 \
            or n3ds12_validation.get("n3ds12ePackagedArtifactAuditTestsPassed") != 5 \
            or n3ds12_validation.get("n3ds12eNativeArtifactAuditTestsPassed") != 4 \
            or n3ds12_validation.get("n3ds12eSourceOrSbomEntries") != 0 \
            or n3ds12_validation.get("n3ds12eRomOrHomebrewEntries") != 0 \
            or n3ds12_validation.get("n3ds12eApkSignatureV1") is not False \
            or n3ds12_validation.get("n3ds12eApkSignatureV2") is not True \
            or n3ds12_validation.get("n3ds12eApkSignatureV3") is not True \
            or n3ds12_validation.get("n3ds12eNativeCoreOrLoaderChanged") is not False \
            or n3ds12_validation.get("n3ds12ePhysicalRegressionBaselineReused") != (
                "N3DS-12D3_26_OF_26") \
            or n3ds12_validation.get("n3ds12eDeviceInstallAttempted") is not False \
            or n3ds12_validation.get("n3ds12ePublicSourceFrozenByOwner") is not True \
            or n3ds12_validation.get("n3ds12eStageCleanupPcRecoveredGiB") != 2.07 \
            or n3ds12_validation.get("n3ds12eStageCleanupDeviceRecoveredBytes") != 0 \
            or n3ds12_validation.get("n3ds12eDeliveryTestPackageRemaining") is not False \
            or n3ds12_validation.get("n3ds12eDeviceTemporaryRootsRemaining") != 0 \
            or n3ds12_validation.get("n3ds12eMainPackagePreserved") is not True \
            or n3ds12_validation.get("n3ds12ePrivateTestPackagePreserved") is not True:
        errors.append("N3DS-12E final packaged-artifact evidence changed")

    n3ds13 = manifest.get("n3ds13Decision", {})
    n3ds13_validation = n3ds13.get("validation", {})
    if n3ds13.get("status") != "DONE" \
            or n3ds13.get("completedSubsteps") != [
                "N3DS-13A", "N3DS-13B", "N3DS-13C", "N3DS-13D"] \
            or n3ds13.get("plannedSubsteps") != [
                "N3DS-13A", "N3DS-13B", "N3DS-13C", "N3DS-13D"] \
            or n3ds13.get("n3ds13aCompletedOn") != "2026-09-11" \
            or n3ds13.get("n3ds13bCompletedOn") != "2026-09-11" \
            or n3ds13.get("n3ds13cCompletedOn") != "2026-09-11" \
            or n3ds13.get("n3ds13dGalaxyCorpusCompletedOn") != "2026-09-11" \
            or n3ds13.get("n3ds13dCompletedOn") != "2026-09-11" \
            or n3ds13.get("n3ds13aScope") != (
                "FAIL_CLOSED_MATRIX_AND_SANITIZED_REPORT_CONTRACT") \
            or n3ds13.get("matrixContract") != (
                "config/nintendo3ds-regression-matrix.json") \
            or n3ds13.get("matrixValidator") != (
                "scripts/validate-nintendo3ds-regression-matrix.py") \
            or n3ds13.get("sanitizedReport") != (
                "config/nintendo3ds-regression-report.json") \
            or n3ds13.get("openHomebrewCorpus") != (
                "config/nintendo3ds-open-homebrew-corpus.json") \
            or n3ds13.get("openHomebrewCorpusValidator") != (
                "scripts/validate-nintendo3ds-open-homebrew-corpus.py") \
            or n3ds13.get("expandedCorpusRunner") != (
                "scripts/test-nintendo3ds-expanded-corpus.ps1") \
            or n3ds13.get("adrenoGateContract") != (
                "config/nintendo3ds-adreno-gate.json") \
            or n3ds13.get("adrenoGateValidator") != (
                "scripts/validate-nintendo3ds-adreno-gate.py") \
            or n3ds13.get("adrenoGatePackager") != (
                "scripts/prepare-nintendo3ds-adreno-gate.ps1") \
            or n3ds13.get("adrenoGateRunner") != (
                "scripts/run-nintendo3ds-adreno-gate.ps1") \
            or n3ds13.get("adrenoFirebaseRunner") != (
                "scripts/run-firebase-adreno-gate.ps1") \
            or n3ds13.get("privacyMode") != "OPAQUE_SLOT_IDS_ONLY" \
            or n3ds13.get("privateContentIdentityFieldsAllowed") is not False \
            or n3ds13.get("minimumDistinctNintendo3DsContents") != 10 \
            or n3ds13.get("maximumDistinctNintendo3DsContents") != 20 \
            or n3ds13.get("currentlyAvailableOpaqueSlots") != 10 \
            or n3ds13.get("requiredExistingSystems") != ["GB", "GBC", "GBA", "NDS"] \
            or n3ds13.get("remainingSubsteps") != [] \
            or n3ds13.get("externalClosureGates") != []:
        errors.append("N3DS-13 regression matrix contract changed")
    if n3ds13_validation.get("n3ds13aContractStatus") != "PASSED" \
            or n3ds13_validation.get("n3ds13aFocusedPythonTestsPassed") != 6 \
            or n3ds13_validation.get("n3ds13aSourceScopeStatus") != "PASSED" \
            or n3ds13_validation.get("n3ds13aMappedImplementationSourceFiles") != 251 \
            or n3ds13_validation.get("n3ds13aAndroidBuildRequired") is not False \
            or n3ds13_validation.get("n3ds13aDeviceTestRequired") is not False \
            or n3ds13_validation.get("n3ds13aPrivatePathsRecorded") != 0 \
            or n3ds13_validation.get("n3ds13aPrivateFilenamesRecorded") != 0 \
            or n3ds13_validation.get("n3ds13aPrivateContentHashesRecorded") != 0 \
            or n3ds13_validation.get("n3ds13aBuildArtifactsGenerated") != 0:
        errors.append("N3DS-13A focused validation evidence changed")
    if n3ds13_validation.get("n3ds13bReportStatus") != "PASSED" \
            or n3ds13_validation.get("n3ds13bDeviceClass") != (
                "GALAXY_XCLIPSE_REFERENCE") \
            or n3ds13_validation.get("n3ds13bPhysicalInstrumentationsPassed") != 16 \
            or n3ds13_validation.get("n3ds13bPhysicalInstrumentationsTotal") != 16 \
            or n3ds13_validation.get(
                "n3ds13bHardenedCandidateInstrumentationsPassed") != 12 \
            or n3ds13_validation.get(
                "n3ds13bExistingSystemInstrumentationsReused") != 4 \
            or n3ds13_validation.get("n3ds13bCandidateCoreBytes") != 23157736 \
            or n3ds13_validation.get("n3ds13bCandidateCoreRole") != (
                "HARDENED_N3DS12E_ARTIFACT") \
            or n3ds13_validation.get("n3ds13bHarnessCalibrationFailures") != 3 \
            or n3ds13_validation.get(
                "n3ds13bDistinctNintendo3DsContentsPassed") != 4 \
            or n3ds13_validation.get("n3ds13bExistingSystemsPassed") != 4 \
            or n3ds13_validation.get("n3ds13bTouchObservations") != 4 \
            or n3ds13_validation.get("n3ds13bContentFrames") != [60, 60, 60, 60] \
            or n3ds13_validation.get("n3ds13bAudioFrames") != [
                717600, 315200, 352160, 0] \
            or n3ds13_validation.get("n3ds13bInputPolls") != [69, 14, 145, 174] \
            or n3ds13_validation.get("n3ds13bContentElapsedMillis") != 78981 \
            or n3ds13_validation.get("n3ds13bFatalSignals") != 0 \
            or n3ds13_validation.get("n3ds13bSilentContentPolicy") != (
                "AUDIO_FRONTEND_VALID_WITH_ZERO_OUTPUT_PCM") \
            or n3ds13_validation.get("n3ds13bPrivateContentIdentitiesRecorded") != 0 \
            or n3ds13_validation.get("n3ds13bPrivateContentPathsRecorded") != 0 \
            or n3ds13_validation.get(
                "n3ds13bStageCleanupPcLogicalBytesBefore") != 848293051 \
            or n3ds13_validation.get("n3ds13bStageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds13_validation.get(
                "n3ds13bStageCleanupDeviceRecoveredMegabytes") != 3.34 \
            or n3ds13_validation.get("n3ds13bMainPackagePreserved") is not True \
            or n3ds13_validation.get("n3ds13bPrivateTestPackagePreserved") is not True \
            or n3ds13_validation.get(
                "n3ds13bRegenerableAppTestPackageRemoved") is not True \
            or n3ds13_validation.get("n3ds13bDeliveryTestPackageAbsent") is not True:
        errors.append("N3DS-13B sanitized physical regression evidence changed")
    if n3ds13_validation.get("n3ds13cReportStatus") != "PASSED" \
            or n3ds13_validation.get("n3ds13cPhysicalInstrumentationsPassed") != 7 \
            or n3ds13_validation.get("n3ds13cPhysicalInstrumentationsTotal") != 7 \
            or n3ds13_validation.get("n3ds13cSaveRoundTripContents") != 3 \
            or n3ds13_validation.get("n3ds13cSaveRestoredBytes") != 608487 \
            or n3ds13_validation.get("n3ds13cUpdateContents") != 3 \
            or n3ds13_validation.get("n3ds13cUpdateRestoredBytes") != 608487 \
            or n3ds13_validation.get("n3ds13cSurfaceRecreationContents") != 4 \
            or n3ds13_validation.get("n3ds13cSurfaceCyclesPerContent") != 3 \
            or n3ds13_validation.get(
                "n3ds13cLifecyclePeakPssGrowthKilobytes") != 128180 \
            or n3ds13_validation.get(
                "n3ds13cAndroidPauseResumeRotationPassed") is not True \
            or n3ds13_validation.get("n3ds13cProlongedRunStrategy") != (
                "REUSE_THREE_20_MINUTE_GALAXY_RUNS_PLUS_EXACT_HARDENED_CANDIDATE_SENTINEL") \
            or n3ds13_validation.get("n3ds13cProlongedRunBaselineRuns") != 3 \
            or n3ds13_validation.get("n3ds13cProlongedRunMinutesPerProfile") != 20 \
            or n3ds13_validation.get(
                "n3ds13cProlongedRunMinimumFramesPerProfile") != 70000 \
            or n3ds13_validation.get(
                "n3ds13cHardenedCandidateSentinelInstrumentations") != 12 \
            or n3ds13_validation.get("n3ds13cFatalSignals") != 0 \
            or n3ds13_validation.get("n3ds13cNewScratchRootsRemaining") != 0 \
            or n3ds13_validation.get("n3ds13cAndroidBuildRequired") is not False \
            or n3ds13_validation.get("n3ds13cProtectedPackagePreserved") is not True \
            or n3ds13_validation.get("n3ds13cPrivateContentIdentitiesRecorded") != 0 \
            or n3ds13_validation.get("n3ds13cPrivateContentPathsRecorded") != 0 \
            or n3ds13_validation.get(
                "n3ds13cStageCleanupPcLogicalBytesBefore") != 275386 \
            or n3ds13_validation.get("n3ds13cStageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds13_validation.get(
                "n3ds13cStageCleanupDeviceRecoveredMegabytes") != 0.0 \
            or n3ds13_validation.get("n3ds13cMainPackagePreserved") is not True \
            or n3ds13_validation.get(
                "n3ds13cRegenerableAppTestPackageAbsent") is not True \
            or n3ds13_validation.get("n3ds13cDeliveryTestPackageAbsent") is not True:
        errors.append("N3DS-13C optimized state regression evidence changed")
    if n3ds13_validation.get("n3ds13dGalaxyCorpusStatus") != "PASSED" \
            or n3ds13_validation.get("n3ds13dDeviceClass") != (
                "GALAXY_XCLIPSE_REFERENCE") \
            or n3ds13_validation.get(
                "n3ds13dDistinctNintendo3DsContentsPassed") != 10 \
            or n3ds13_validation.get(
                "n3ds13dNewRedistributableContentsPassed") != 6 \
            or n3ds13_validation.get(
                "n3ds13dPhysicalInstrumentationsPassed") != 12 \
            or n3ds13_validation.get(
                "n3ds13dPhysicalInstrumentationsTotal") != 12 \
            or n3ds13_validation.get("n3ds13dFramesPerNewContent") != [
                12, 12, 12, 12, 12, 12] \
            or n3ds13_validation.get("n3ds13dAudioFrames") != [0, 0, 0, 0, 0, 0] \
            or n3ds13_validation.get("n3ds13dInputPolls") != [12, 25, 4, 5, 6, 13] \
            or n3ds13_validation.get("n3ds13dContentElapsedMillis") != 31138 \
            or n3ds13_validation.get("n3ds13dTouchObservations") != 10 \
            or n3ds13_validation.get("n3ds13dFatalSignals") != 0 \
            or n3ds13_validation.get("n3ds13dExternalGatesRemaining") != 1 \
            or n3ds13_validation.get("n3ds13dTestStrategy") != (
                "TWO_FOCAL_INSTRUMENTATIONS_PER_NEW_CONTENT_REUSE_DEEP_BASELINES") \
            or n3ds13_validation.get("n3ds13dCorpusFrameCount") != 12 \
            or n3ds13_validation.get("n3ds13dRejectedCalibrationCandidates") != 1 \
            or n3ds13_validation.get("n3ds13dSelfContainedPrivateHarness") is not True \
            or n3ds13_validation.get("n3ds13dPrivateContentIdentitiesRecorded") != 0 \
            or n3ds13_validation.get("n3ds13dPrivateContentPathsRecorded") != 0 \
            or n3ds13_validation.get(
                "n3ds13dStageCleanupPcLogicalBytesBefore") != 7125637 \
            or n3ds13_validation.get("n3ds13dStageCleanupPcLogicalBytesAfter") != 0 \
            or n3ds13_validation.get(
                "n3ds13dStageCleanupDeviceBytesRemoved") != 4494928 \
            or n3ds13_validation.get("n3ds13dStagedContentsRemaining") != 0 \
            or n3ds13_validation.get("n3ds13dMainPackagePreserved") is not True \
            or n3ds13_validation.get("n3ds13dPrivateTestPackagePreserved") is not True \
            or n3ds13_validation.get(
                "n3ds13dPrivateCoreAndContentPreserved") is not True \
            or n3ds13_validation.get("n3ds13dPublicSourceFrozenByOwner") is not True:
        errors.append("N3DS-13D Galaxy corpus evidence changed")
    if n3ds13_validation.get("n3ds13dAdrenoPackageStatus") != (
            "PASSED_EXTERNAL_EXECUTION") \
            or n3ds13_validation.get("n3ds13dAdrenoPackageBytes") != 21978913 \
            or n3ds13_validation.get("n3ds13dAdrenoPackageSha256") != (
                "c579f89bac8b9405cef478e9123f3c57a904340ae43a9dbbedee6f8462256d2e") \
            or n3ds13_validation.get("n3ds13dAdrenoPackageFileCount") != 12 \
            or n3ds13_validation.get("n3ds13dAdrenoTestApkBytes") != 1994319 \
            or n3ds13_validation.get("n3ds13dAdrenoTestApkSha256") != (
                "3cca9787211b3fdf7f77f0ff66eb48862a6ae43133bdf3e4aa0836237f3bde28") \
            or n3ds13_validation.get("n3ds13dAdrenoCoreEmbeddedInTestApk") is not False \
            or n3ds13_validation.get(
                "n3ds13dAdrenoCommercialContentIncluded") is not False \
            or n3ds13_validation.get("n3ds13dAdrenoHomebrewLicenseIncluded") is not True \
            or n3ds13_validation.get(
                "n3ds13dAdrenoThirdPartyNoticesIncluded") is not True \
            or n3ds13_validation.get("n3ds13dAdrenoAcceptanceLongRunMinutes") != 20 \
            or n3ds13_validation.get("n3ds13dAdrenoCalibrationDevice") != (
                "SM_A376B_XCLIPSE_530") \
            or n3ds13_validation.get("n3ds13dAdrenoCalibrationFocalPassed") != 6 \
            or n3ds13_validation.get("n3ds13dAdrenoCalibrationFocalTotal") != 6 \
            or n3ds13_validation.get("n3ds13dAdrenoCalibrationElapsedMillis") != 17374 \
            or n3ds13_validation.get(
                "n3ds13dAdrenoCalibrationAcceptanceEligible") is not False \
            or n3ds13_validation.get(
                "n3ds13dAdrenoCalibrationLongRunExecuted") is not False \
            or n3ds13_validation.get("n3ds13dAdrenoCalibrationFatalSignals") != 0 \
            or n3ds13_validation.get("n3ds13dAdrenoCalibrationExactCleanup") is not True \
            or n3ds13_validation.get("n3ds13dAdrenoPaidServiceUsed") is not False \
            or n3ds13_validation.get("n3ds13dFirebaseHarnessStatus") != (
                "PASSED_PHYSICAL_ADRENO") \
            or n3ds13_validation.get("n3ds13dFirebaseProject") != "emuorbitadvance" \
            or n3ds13_validation.get("n3ds13dFirebasePlanObserved") != (
                "SPARK_NO_COST") \
            or n3ds13_validation.get("n3ds13dFirebaseBillingFailClosed") is not True \
            or n3ds13_validation.get("n3ds13dFirebaseTargetPackage") != (
                "com.mateussouza.emuorbit.n3ds.adreno.target") \
            or n3ds13_validation.get("n3ds13dFirebaseTargetApkBytes") != 28077941 \
            or n3ds13_validation.get("n3ds13dFirebaseTargetApkSha256") != (
                "4dd20c525ec26e33fc9eade74f0b6550d04e3f279098267b45bb4855e3fb0dfe") \
            or n3ds13_validation.get("n3ds13dFirebaseTestApkBytes") != 1994319 \
            or n3ds13_validation.get("n3ds13dFirebaseTestApkSha256") != (
                "cb976072fc3ccb9c6ef8df9dbb2709ed368f020af730d468e8bb3016a80a2ad2") \
            or n3ds13_validation.get(
                "n3ds13dFirebaseRecommendedDeviceCatalogId") != "redfin-30" \
            or n3ds13_validation.get("n3ds13dFirebaseRecommendedDevice") != (
                "GOOGLE_PIXEL_5_SNAPDRAGON_765G_ADRENO_620_API_30_PHYSICAL") \
            or n3ds13_validation.get(
                "n3ds13dFirebaseGalaxyHarnessFocalPassed") != 6 \
            or n3ds13_validation.get(
                "n3ds13dFirebaseGalaxyHarnessFocalTotal") != 6 \
            or n3ds13_validation.get(
                "n3ds13dFirebaseGalaxyHarnessLongRunExecuted") is not False \
            or n3ds13_validation.get(
                "n3ds13dFirebaseCommercialContentIncluded") is not False \
            or n3ds13_validation.get("n3ds13dFirebaseUploadExecuted") is not True \
            or n3ds13_validation.get("n3ds13dFirebaseMatrixExecuted") is not True \
            or n3ds13_validation.get("n3ds13dFirebaseDiagnosticMatrixId") != (
                "matrix-2tf2j7w57429w") \
            or n3ds13_validation.get("n3ds13dFirebaseDiagnosticMatrixPassed") != 3 \
            or n3ds13_validation.get("n3ds13dFirebaseDiagnosticMatrixFailed") != 4 \
            or n3ds13_validation.get("n3ds13dFirebaseFocalMatrixId") != (
                "matrix-3vammjxy074cr") \
            or n3ds13_validation.get("n3ds13dFirebaseFocalPassed") != 6 \
            or n3ds13_validation.get("n3ds13dFirebaseFocalTotal") != 6 \
            or n3ds13_validation.get("n3ds13dFirebaseLongRunMatrixId") != (
                "matrix-1g38thbeljpgy") \
            or n3ds13_validation.get("n3ds13dFirebaseLongRunPassed") != 1 \
            or n3ds13_validation.get("n3ds13dFirebaseLongRunTotal") != 1 \
            or n3ds13_validation.get(
                "n3ds13dFirebaseStageCleanupPcRecoveredBytes") != 671799923 \
            or n3ds13_validation.get(
                "n3ds13dFirebaseStageCleanupDeviceRecoveredKilobytes") != 3937284 \
            or n3ds13_validation.get(
                "n3ds13dFirebaseGeneratedBuildRootsRemaining") != 0 \
            or n3ds13_validation.get(
                "n3ds13dFirebaseQaTargetPackageRemaining") is not False \
            or n3ds13_validation.get(
                "n3ds13dFirebaseMainPackagePreserved") is not True \
            or n3ds13_validation.get(
                "n3ds13dFirebaseQaTestPackageRemaining") is not False \
            or n3ds13_validation.get("n3ds13dAdrenoPhysicalResultPending") is not False \
            or n3ds13_validation.get("n3ds13dFirebasePaidServiceUsed") is not False:
        errors.append("N3DS-13D portable Adreno gate evidence changed")

    reference_build = manifest.get("referenceBuild", {})
    if reference_build.get("completedOn") != "2026-09-11" \
            or reference_build.get("linkerFlags") != [
                "-Wl,--version-script=azahar_libretro.exports.map",
                "-Wl,--exclude-libs,ALL",
                "-Wl,--gc-sections",
                "-Wl,-z,relro",
                "-Wl,-z,now",
                "-Wl,-z,noexecstack",
                "-Wl,-z,max-page-size=16384",
                "-Wl,-z,common-page-size=16384"] \
            or reference_build.get("compileHardeningFlags") != [
                "-ffile-prefix-map",
                "-fmacro-prefix-map",
                "-fdebug-prefix-map",
                "-ffunction-sections",
                "-fdata-sections",
                "-fno-ident"] \
            or reference_build.get("stripFlags") != [
                "--strip-all", "--remove-section=.comment"] \
            or reference_build.get("exportMap") != (
                "nintendo3dscore/src/main/cpp/azahar_libretro.exports.map") \
            or reference_build.get("definedExportCount") != 25 \
            or reference_build.get("unstrippedBytes") != 405375968 \
            or reference_build.get("unstrippedSha256") != (
                "2ddb573c55eb21f049f2e7abc90bdf20bc541ce6c1b5f5230ccb89cde112a597") \
            or reference_build.get("strippedBytes") != 23157736 \
            or reference_build.get("strippedSha256") != (
                "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c") \
            or reference_build.get("runtimeDependencies") != [
                "liblog.so", "libm.so", "libdl.so", "libc.so"]:
        errors.append("N3DS-12B hardened reproducible core baseline changed")

    baseline = manifest.get("implementationBaselineCommit", "")
    if not COMMIT_PATTERN.fullmatch(baseline):
        errors.append("implementationBaselineCommit must be a full Git commit")

    generated_artwork = next(
        (item for item in manifest.get("generatedAssets", [])
         if item.get("id") == "nintendo3ds-library-card-artwork"),
        None,
    )
    if not generated_artwork \
            or generated_artwork.get("generator") != "OPENAI_BUILT_IN_IMAGEGEN" \
            or generated_artwork.get("license") != "GPL-3.0-or-later" \
            or generated_artwork.get("referenceAsset") \
            != "app/src/main/res/drawable-nodpi/img_system_nds.webp" \
            or generated_artwork.get("referenceUsage") != "STYLE_AND_COMPOSITION_ONLY" \
            or generated_artwork.get("sourcePath") \
            != "nintendo3dscore/compliance/assets/img_system_n3ds_source.png" \
            or generated_artwork.get("promptPath") \
            != "nintendo3dscore/compliance/assets/img_system_n3ds.prompt.txt" \
            or generated_artwork.get("packagedPath") \
            != "app/src/main/res/drawable-nodpi/img_system_n3ds.webp" \
            or generated_artwork.get("transparentAlpha") is not True \
            or generated_artwork.get("containsThirdPartyLogoOrText") is not False:
        errors.append("N3DS-10C11 generated library artwork provenance is incomplete")

    upstreams = manifest.get("distributedUpstreams", [])
    azahar = next((item for item in upstreams if item.get("id") == "azahar"), None)
    if not azahar:
        errors.append("Azahar distributed upstream is missing")
    else:
        if azahar.get("license") != "GPL-2.0-or-later":
            errors.append("Azahar must retain its GPL-2.0-or-later declaration")
        if not COMMIT_PATTERN.fullmatch(azahar.get("commit", "")):
            errors.append("Azahar must be pinned to a full commit")
        if azahar.get("recursiveSubmodules") is not True:
            errors.append("Azahar recursive submodules must remain in source scope")

    incorporated_destinations = {
        destination
        for reference in manifest.get("referenceImplementations", [])
        for source in reference.get("incorporatedSourcePaths", [])
        for destination in source.get("destinations", [])
    }
    for required_destination in (
        "nintendo3dscore/src/main/cpp/libretro_gameplay_abi.h",
        "nintendo3dscore/src/main/cpp/libretro_vulkan_abi.h",
        "nintendo3dscore/src/main/cpp/vulkan_render_host.cpp",
    ):
        if required_destination not in incorporated_destinations:
            errors.append(
                "adapted Nintendo 3DS frontend source is missing provenance: "
                f"{required_destination}"
            )
    references = manifest.get("referenceImplementations", [])
    performance_reference = next(
        (item for item in references
         if item.get("id") == "azahar-libretro-performance-options"),
        None,
    )
    if not performance_reference \
            or performance_reference.get("commit") \
            != "fbd3fb02f71e5f9ed5134037fd59bad96c7d2b8a" \
            or performance_reference.get("license") != "GPL-2.0-or-later" \
            or performance_reference.get("sourcePaths") != [
                "src/citra_libretro/core_settings.cpp",
                "src/citra_libretro/environment.cpp"] \
            or "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsPerformanceProfile.java" \
            not in performance_reference.get("destinations", []) \
            or "ACCURATE_SHADER_MULTIPLICATION_PRESERVED_EXCEPT_EXPLICIT_PERFORMANCE_PROFILE_TRADEOFF" \
            not in performance_reference.get("adaptedPatterns", []):
        errors.append("N3DS-11A Azahar performance-option provenance is incomplete")
    diagnostics_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-performance-diagnostics"),
        None,
    )
    if not diagnostics_reference \
            or diagnostics_reference.get("baselineCommit") \
            != "6babe3363abc851ccf11647b2cabf8db0c669819" \
            or diagnostics_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/emulator/diagnostics/EmulationDiagnosticsCollector.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/emulator/diagnostics/EmulationDiagnosticsSnapshot.java"] \
            or "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsPerformanceCollector.java" \
            not in diagnostics_reference.get("destinationPaths", []):
        errors.append("N3DS-11B existing diagnostics provenance is incomplete")
    mii_reference = next(
        (item for item in references
         if item.get("id") == "azahar-mii-selector-storage-contract"),
        None,
    )
    if not mii_reference \
            or mii_reference.get("commit") \
            != "fbd3fb02f71e5f9ed5134037fd59bad96c7d2b8a" \
            or "src/core/frontend/applets/mii_selector.cpp" \
            not in mii_reference.get("sourcePaths", []) \
            or "src/core/hle/mii.h" not in mii_reference.get("sourcePaths", []) \
            or "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsMiiDataManager.java" \
            not in mii_reference.get("destinations", []) \
            or "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsLaunchReadiness.java" \
            not in mii_reference.get("destinations", []) \
            or "DEFAULT_MII_SELECTOR_USES_STANDARD_MII_RESULT" \
            not in mii_reference.get("adaptedPatterns", []):
        errors.append("N3DS-10 Azahar Mii parser/path/fallback provenance is incomplete")
    readiness_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-launch-readiness-policy"),
        None,
    )
    if not readiness_reference \
            or readiness_reference.get("baselineCommit") != baseline \
            or readiness_reference.get("source") \
            != "app/src/main/java/com/mateussouza/emuorbit/advance/domain/emulation/GameLaunchReadiness.java" \
            or readiness_reference.get("destination") \
            != "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsLaunchReadiness.java":
        errors.append("N3DS-10B internal launch readiness provenance is incomplete")
    readiness_dialog_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-readiness-dialog-lifecycle"),
        None,
    )
    if not readiness_dialog_reference \
            or readiness_dialog_reference.get("baselineCommit") != baseline \
            or readiness_dialog_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/library/LibraryFragment.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/MateusDialog.java"] \
            or readiness_dialog_reference.get("destination") \
            != "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsReadinessDialogController.java":
        errors.append("N3DS-10C3 internal readiness dialog provenance is incomplete")
    readiness_action_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-readiness-action-effects"),
        None,
    )
    if not readiness_action_reference \
            or readiness_action_reference.get("baselineCommit") != baseline \
            or readiness_action_reference.get("source") \
            != "app/src/main/java/com/mateussouza/emuorbit/advance/ui/library/LibraryFragment.java" \
            or readiness_action_reference.get("destination") \
            != "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsReadinessActionCoordinator.java":
        errors.append("N3DS-10C4 internal readiness action provenance is incomplete")
    async_mii_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-async-saf-workflow"),
        None,
    )
    if not async_mii_reference \
            or async_mii_reference.get("baselineCommit") != baseline \
            or async_mii_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/emulation/EmulationCheatController.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/settings/SettingsBackupController.java"] \
            or async_mii_reference.get("destination") \
            != "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsMiiImportWorkflow.java":
        errors.append("N3DS-10C5 internal asynchronous SAF provenance is incomplete")
    post_import_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-post-import-refresh"),
        None,
    )
    if not post_import_reference \
            or post_import_reference.get("baselineCommit") != baseline \
            or post_import_reference.get("source") \
            != "app/src/main/java/com/mateussouza/emuorbit/advance/ui/settings/SettingsBackupController.java" \
            or post_import_reference.get("destination") \
            != "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsMiiRecoveryCoordinator.java":
        errors.append("N3DS-10C6 internal post-import refresh provenance is incomplete")
    activity_result_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-activity-result-saf-host"),
        None,
    )
    if not activity_result_reference \
            or activity_result_reference.get("baselineCommit") != baseline \
            or activity_result_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/emulation/EmulationCheatController.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/settings/SettingsBackupController.java"] \
            or activity_result_reference.get("destination") \
            != "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsActivityResultHost.java":
        errors.append("N3DS-10C7 internal Activity Result SAF host provenance is incomplete")
    settings_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-global-sparse-game-settings"),
        None,
    )
    if not settings_reference \
            or settings_reference.get("baselineCommit") != baseline \
            or settings_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/domain/settings/NintendoDsLayoutSettings.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/domain/settings/NintendoDsLayoutOverrides.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/data/preferences/NintendoDsLayoutSettingsStore.java"] \
            or settings_reference.get("destinationPaths") != [
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsExperienceSettings.java",
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsExperienceSettingsOverrides.java",
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsExperienceSettingsStore.java"]:
        errors.append("N3DS-10C8 internal settings provenance is incomplete")
    settings_dialog_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-settings-dialog-lifecycle"),
        None,
    )
    if not settings_dialog_reference \
            or settings_dialog_reference.get("baselineCommit") != baseline \
            or settings_dialog_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/emulation/NintendoDsLayoutDialog.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/MateusDialog.java"] \
            or settings_dialog_reference.get("destination") != (
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
                "Nintendo3DsExperienceSettingsDialogController.java"):
        errors.append("N3DS-10C9 internal settings dialog provenance is incomplete")
    product_host_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-product-host-lifecycle-and-dialog-style"),
        None,
    )
    if not product_host_reference \
            or product_host_reference.get("baselineCommit") != baseline \
            or product_host_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/emulation/EmulationActivity.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/emulation/EmulationMenuController.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/MateusDialog.java"] \
            or product_host_reference.get("destinationPaths") != [
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsProductActivity.java",
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsProductLaunchRequest.java",
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsProductDialogStyleAdapter.java"]:
        errors.append("N3DS-10C10 internal product host provenance is incomplete")
    virtual_controls_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-virtual-control-surface"),
        None,
    )
    if not virtual_controls_reference \
            or virtual_controls_reference.get("baselineCommit") != baseline \
            or virtual_controls_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/emulation/VirtualJoystickView.java",
                "app/src/main/res/layout/activity_emulation.xml",
                "app/src/main/res/drawable/emulator_control_background.xml",
                "app/src/main/res/animator/emulator_control_elevation.xml"] \
            or virtual_controls_reference.get("destinationPaths") != [
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsVirtualControlsOverlay.java",
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsAnalogStickView.java",
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsAnalogStickMath.java",
                "nintendo3dscore/src/main/res/drawable/n3ds_control_background.xml"]:
        errors.append("N3DS-10C13 internal virtual-control provenance is incomplete")
    library_presentation_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-library-system-presentation-and-filter"),
        None,
    )
    if not library_presentation_reference \
            or library_presentation_reference.get("baselineCommit") != baseline \
            or library_presentation_reference.get("sourcePaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/EmulatorSystemUiResources.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/library/LibraryFragment.java",
                "app/src/main/res/layout/popup_library_system_filter.xml",
                "app/src/main/res/drawable-nodpi/img_system_nds.webp"] \
            or library_presentation_reference.get("destinationPaths") != [
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/EmulatorSystemUiResources.java",
                "app/src/main/java/com/mateussouza/emuorbit/advance/ui/library/LibraryFragment.java",
                "app/src/main/res/layout/popup_library_system_filter.xml",
                "app/src/main/res/drawable-nodpi/img_system_n3ds.webp",
                "app/src/main/res/values*/strings.xml"]:
        errors.append("N3DS-10C11 internal library presentation provenance is incomplete")
    accessibility_reference = next(
        (item for item in references
         if item.get("id") == "emuorbit-existing-large-font-rtl-accessibility-tests"),
        None,
    )
    if not accessibility_reference \
            or accessibility_reference.get("baselineCommit") != baseline \
            or accessibility_reference.get("sourcePaths") != [
                "app/src/androidTest/java/com/mateussouza/emuorbit/advance/ui/PrimaryLayoutsResponsiveInstrumentedTest.java",
                "app/src/androidTest/java/com/mateussouza/emuorbit/advance/ui/emulation/CheatManagerDialogResponsiveInstrumentedTest.java",
                "app/src/androidTest/java/com/mateussouza/emuorbit/advance/ui/library/LibraryConsoleSelectorLayoutInstrumentedTest.java"] \
            or accessibility_reference.get("destinationPaths") != [
                "nintendo3dscore/src/androidTest/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsAccessibilityInstrumentedTest.java",
                "nintendo3dscore/src/debug/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsUiTestConfiguration.java",
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsProductActivity.java",
                "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/Nintendo3DsProductDialogStyleAdapter.java"]:
        errors.append("N3DS-10C12 responsive accessibility provenance is incomplete")

    renderer = manifest.get("rendererPolicy", {})
    if renderer.get("primary") != "VULKAN":
        errors.append("Vulkan must remain the qualified primary renderer")
    if renderer.get("openGlesFallback") != "REQUIRES_EXACT_DEVICE_GPU_DRIVER_LONG_RUN_QUALIFICATION":
        errors.append("OpenGL ES fallback must require exact device/GPU/driver qualification")
    if renderer.get("software") != "DIAGNOSTICS_ONLY":
        errors.append("software rendering must remain diagnostics-only")
    if not str(renderer.get("unavailableBehavior", "")).startswith("BLOCK_"):
        errors.append("an unsupported graphics configuration must block 3DS launch")
    quarantine = renderer.get("knownQuarantine", [])
    if not any(item.get("renderer") == "OPENGLES" for item in quarantine):
        errors.append("the observed OpenGL ES vendor-driver failure must remain quarantined")

    test_inputs = manifest.get("referenceTestInputs", [])
    licensed_inputs = [item for item in test_inputs if item.get("license") != "NOASSERTION"]
    if len(licensed_inputs) < 2:
        errors.append("at least two licensed homebrew inputs must have pinned provenance")
    for item in test_inputs:
        if not COMMIT_PATTERN.fullmatch(item.get("commit", "")):
            errors.append(f"test input {item.get('id', '<unknown>')} must pin a full source commit")
        if item.get("license") == "NOASSERTION" and item.get("usage") != "PRIVATE_DIAGNOSTIC_ONLY_NOT_COMMITTED_NOT_REDISTRIBUTED":
            errors.append("unlicensed diagnostic input must stay private and undistributed")

    patterns = manifest.get("publicationPathPatterns", [])
    if not patterns:
        errors.append("publicationPathPatterns cannot be empty")
    for required in (
        "config/nintendo3ds-source-scope.json",
        "config/nintendo3ds-open-homebrew-corpus.json",
        "config/nintendo3ds-adreno-gate.json",
        "scripts/verify-nintendo3ds-source-scope.py",
        "scripts/acquire-nintendo3ds-source.ps1",
        "scripts/generate-nintendo3ds-compliance.py",
        "scripts/test-nintendo3ds-core-update.ps1",
        "scripts/test-nintendo3ds-core-suite.ps1",
        "scripts/test-nintendo3ds-expanded-corpus.ps1",
        "scripts/prepare-nintendo3ds-adreno-gate.ps1",
        "scripts/run-nintendo3ds-adreno-gate.ps1",
        "scripts/run-firebase-adreno-gate.ps1",
        "scripts/validate-nintendo3ds-open-homebrew-corpus.py",
        "scripts/validate-nintendo3ds-adreno-gate.py",
        "scripts/tests/test_nintendo3ds_core_suite.py",
        "nintendo3dscore/compliance/**",
        "app/src/**/domain/model/EmulatorSystem*.java",
        "app/src/**/emulator/backend/**",
    ):
        if required not in patterns:
            errors.append(f"scope verifier input is not public: {required}")
    return errors


def _run_git(root: Path, *args: str) -> list[str]:
    process = subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return [line.strip().replace("\\", "/") for line in process.stdout.splitlines() if line.strip()]


def implementation_paths(root: Path, baseline: str) -> set[str]:
    changed = set(_run_git(root, "diff", "--name-only", "--diff-filter=ACMRTUXB", baseline, "--"))
    changed.update(_run_git(root, "ls-files", "--others", "--exclude-standard"))
    return changed


def is_source_path(relative_path: str) -> bool:
    path = Path(relative_path)
    return path.name == "CMakeLists.txt" or path.suffix.lower() in SOURCE_SUFFIXES


def matches_scope(relative_path: str, patterns: Iterable[str]) -> bool:
    normalized = relative_path.replace("\\", "/")
    return any(fnmatch.fnmatchcase(normalized, pattern) for pattern in patterns)


def validate_activity_result_boundary(root: Path) -> list[str]:
    errors: list[str] = []
    host = root / (
        "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
        "Nintendo3DsActivityResultHost.java"
    )
    dialog = root / (
        "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
        "Nintendo3DsReadinessDialogController.java"
    )
    base_build = root / "app/build.gradle.kts"
    try:
        host_source = host.read_text(encoding="utf-8")
        dialog_source = dialog.read_text(encoding="utf-8")
        base_build_source = base_build.read_text(encoding="utf-8")
    except OSError as error:
        return [f"N3DS-10C7 source boundary could not be inspected: {error}"]

    for token in (
        "registerForActivityResult(",
        "new ActivityResultContracts.StartActivityForResult()",
        "this::onMiiPickerResult",
        "Intent.ACTION_OPEN_DOCUMENT",
    ):
        if token not in host_source:
            errors.append(f"N3DS-10C7 Activity Result host lost required token: {token}")
    for token in ("interface StyleAdapter", "getThemeResource(", "onDialogShown("):
        if token not in dialog_source:
            errors.append(f"N3DS-10C7 style adapter lost required token: {token}")
    if 'project(":nintendo3dscore")' in base_build_source \
            or "project(':nintendo3dscore')" in base_build_source:
        errors.append("N3DS-10C7 must not add a static base-app dependency on nintendo3dscore")
    return errors


def validate_experience_settings_boundary(root: Path) -> list[str]:
    errors: list[str] = []
    source_root = root / (
        "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core"
    )
    files = {
        "settings": source_root / "Nintendo3DsExperienceSettings.java",
        "overrides": source_root / "Nintendo3DsExperienceSettingsOverrides.java",
        "store": source_root / "Nintendo3DsExperienceSettingsStore.java",
        "dialog": source_root / "Nintendo3DsExperienceSettingsDialogController.java",
    }
    try:
        sources = {
            name: path.read_text(encoding="utf-8")
            for name, path in files.items()
        }
    except OSError as error:
        return [f"N3DS-10C8 settings boundary could not be inspected: {error}"]

    required_tokens = {
        "settings": (
            "static Nintendo3DsExperienceSettings defaults()",
            "DEFAULT_VIRTUAL_CONTROL_OPACITY);",
            "Nintendo3DsMicrophoneStartResult applyTo(",
            "checked.setPerformanceProfile(performanceProfile);",
            "checked.setScreenLayout(Objects.requireNonNull(effectiveScreenLayout));",
            "checked.setAudioEnabled(audioEnabled);",
            "checked.setMicrophoneEnabled(microphoneEnabled);",
        ),
        "overrides": (
            "static Nintendo3DsExperienceSettingsOverrides between(",
            "Nintendo3DsExperienceSettings resolve(",
            "boolean isEmpty()",
            "getPerformanceProfile()",
        ),
        "store": (
            '"nintendo_3ds_experience_settings_v1"',
            '"n3ds-v1:[0-9a-f]{64}:[0-9a-f]{64}(?::[0-9a-f]{64})?"',
            "loadGameOverrides(",
            "writeAllGameOverrides(",
            ".commit();",
            "catch (ClassCastException exception)",
            '"performance_profile"',
        ),
        "dialog": (
            "enum Scope",
            "interface StyleAdapter",
            "showGlobal(",
            "showForGame(",
            "new MateusDialog.Builder(checkedActivity)",
            ".setValidatingPositiveButton(",
            ".setValidatingNeutralButton(",
            "new NestedScrollView(activity)",
            "layoutLabel.setLabelFor(layoutSpinner.getId())",
            "layoutSpinner.setContentDescription(",
            "performanceProfileSpinner.setContentDescription(",
        ),
    }
    for name, tokens in required_tokens.items():
        for token in tokens:
            if token not in sources[name]:
                substep = "N3DS-10C9" if name == "dialog" else "N3DS-10C8"
                errors.append(f"{substep} {name} source lost required token: {token}")
    resource_names = (
        "n3ds_settings_global_title",
        "n3ds_settings_game_title",
        "n3ds_settings_screen_layout",
        "n3ds_settings_layout_default",
        "n3ds_settings_layout_side_by_side",
        "n3ds_settings_layout_large_top",
        "n3ds_settings_layout_large_bottom",
        "n3ds_settings_layout_single_top",
        "n3ds_settings_layout_single_bottom",
        "n3ds_settings_performance_profile",
        "n3ds_settings_performance_conservative",
        "n3ds_settings_performance_balanced",
        "n3ds_settings_performance_performance",
        "n3ds_settings_audio_enabled",
        "n3ds_settings_audio_volume",
        "n3ds_settings_microphone_enabled",
        "n3ds_settings_save",
        "n3ds_settings_reset",
    )
    for qualifier in (
            "values", "values-en", "values-es", "values-zh-rCN", "values-hi",
            "values-ar", "values-bn", "values-fr", "values-ru", "values-in"):
        resource_file = root / "nintendo3dscore/src/main/res" / qualifier / "strings.xml"
        try:
            resource_source = resource_file.read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"N3DS-10C9 localized settings resources unavailable: {error}")
            continue
        for resource_name in resource_names:
            if f'name="{resource_name}"' not in resource_source:
                errors.append(
                    f"N3DS-10C9 {qualifier} lost settings resource: {resource_name}")
    return errors


def validate_product_entry_boundary(root: Path) -> list[str]:
    errors: list[str] = []
    source_root = root / (
        "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core"
    )
    files = {
        "activity": source_root / "Nintendo3DsProductActivity.java",
        "base_bridge": source_root / "Nintendo3DsBaseProductBridge.java",
        "controller": source_root / "Nintendo3DsCoreLifecycleController.java",
        "frame_capture": source_root / "Nintendo3DsSurfaceFrameCapture.java",
        "report_context": source_root / "Nintendo3DsBugReportContext.java",
        "share_launcher": source_root / "Nintendo3DsShareIntentLauncher.java",
        "request": source_root / "Nintendo3DsProductLaunchRequest.java",
        "style": source_root / "Nintendo3DsProductDialogStyleAdapter.java",
        "manifest": root / "nintendo3dscore/src/main/AndroidManifest.xml",
        "base_build": root / "app/build.gradle.kts",
    }
    try:
        sources = {
            name: path.read_text(encoding="utf-8")
            for name, path in files.items()
        }
    except OSError as error:
        return [f"N3DS-10C10 product entry boundary could not be inspected: {error}"]

    required_tokens = {
        "activity": (
            "extends ComponentActivity",
            "implements SurfaceHolder.Callback",
            "registerForActivityResult(",
            "new ActivityResultContracts.RequestPermission()",
            "new Nintendo3DsActivityResultHost(",
            ".loadGameOverrides(launchRequest.getPersistentId())",
            "settings.applyTo(",
            ".showForGame(",
            "onDialogDismissed(",
            "stopFrameLoop();",
            "RESULT_ACTION_EXTRACT_CONTENT",
            "new PopupMenu(this, menuButton)",
            "MENU_RESET_ID",
            "MENU_SHARE_ID",
            "MENU_REPORT_ID",
            "showProductConfirmation(",
            "new OnBackPressedCallback(true)",
            "surfaceFrameCapture.capture(surfaceView",
            "baseProductBridge.createScreenshotShareIntent(",
            "baseProductBridge.sendProblemReport(",
            "Nintendo3DsBugReportContext.create(",
        ),
        "base_bridge": (
            '"com.mateussouza.emuorbit.advance.playstore.PlayStoreNavigator"',
            '"com.mateussouza.emuorbit.advance.sharing.ShareArtifactManager"',
            '"com.mateussouza.emuorbit.advance.ui.bugreport.BugReportSender"',
            "Class.forName(className).getMethod(methodName, parameterTypes)",
            "createScreenshotShareIntent(",
            "sendProblemReport(",
        ),
        "controller": (
            "public void restartSessionAndAwait()",
            "surfaceGeneration++;",
            "transition = submitCloseSession();",
            "audioOutput.releaseForTransition();",
        ),
        "frame_capture": (
            "PixelCopy.request(source, destination",
            "MAXIMUM_EDGE_PIXELS = 2_048",
            "surface == null || !surface.isValid()",
            "destination.recycle();",
        ),
        "report_context": (
            'context.put("game_system", "NINTENDO_3DS")',
            'context.put("emulation_state"',
            'context.put("screen_layout"',
            'context.put("performance_profile"',
            "Collections.unmodifiableMap(context)",
        ),
        "share_launcher": (
            "Intent.createChooser(shareIntent, chooserTitle)",
            "ActivityNotFoundException | SecurityException",
        ),
        "request": (
            "Nintendo3DsExperienceSettingsStore.normalizePersistentId(",
            "requireAbsolute(coreLibrary",
            "requireAbsolute(content",
            "new Intent(Objects.requireNonNull(context), Nintendo3DsProductActivity.class)",
        ),
        "style": (
            "implements Nintendo3DsReadinessDialogController.StyleAdapter",
            "Nintendo3DsExperienceSettingsDialogController.StyleAdapter",
            "MateusDialog owns the same responsive sizing",
        ),
        "manifest": (
            'android:name=".Nintendo3DsProductActivity"',
            'android:exported="false"',
            'android:hardwareAccelerated="true"',
        ),
    }
    for name, tokens in required_tokens.items():
        for token in tokens:
            if token not in sources[name]:
                errors.append(
                    f"N3DS-10C10 {name} source lost required token: {token}")
    if 'project(":nintendo3dscore")' in sources["base_build"] \
            or "project(':nintendo3dscore')" in sources["base_build"]:
        errors.append("N3DS-10C10 must keep the base app independent from the hidden host")

    product_resources = (
        "n3ds_product_settings",
        "n3ds_product_launch_failed",
        "n3ds_product_settings_save_failed",
        "n3ds_product_microphone_permission_denied",
        "n3ds_product_more_actions_show",
        "n3ds_product_more_actions_hide",
        "n3ds_product_reset",
        "n3ds_product_restarting",
        "n3ds_product_reset_title",
        "n3ds_product_reset_message",
        "n3ds_product_reset_confirm",
        "n3ds_product_exit_title",
        "n3ds_product_exit_message",
        "n3ds_product_exit_confirm",
        "n3ds_product_cancel",
        "n3ds_product_share",
        "n3ds_product_report_problem",
        "n3ds_product_share_unavailable_until_published",
        "n3ds_product_share_frame_unavailable",
        "n3ds_product_share_preparing",
        "n3ds_product_share_failed",
        "n3ds_product_share_no_compatible_app",
        "n3ds_product_share_brand_caption",
        "n3ds_product_share_text",
        "n3ds_product_share_chooser_title",
        "n3ds_product_report_title",
        "n3ds_product_report_message",
        "n3ds_product_report_hint",
        "n3ds_product_report_required",
        "n3ds_product_report_send",
        "n3ds_product_report_sent",
        "n3ds_product_report_failed",
    )
    for qualifier in (
            "values", "values-en", "values-es", "values-zh-rCN", "values-hi",
            "values-ar", "values-bn", "values-fr", "values-ru", "values-in"):
        resource_file = root / "nintendo3dscore/src/main/res" / qualifier / "strings.xml"
        try:
            resource_source = resource_file.read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"N3DS-10C10 localized product resources unavailable: {error}")
            continue
        for resource_name in product_resources:
            if f'name="{resource_name}"' not in resource_source:
                errors.append(
                    f"N3DS-10C10 {qualifier} lost product resource: {resource_name}")
    return errors


def validate_conditional_library_presentation_boundary(
        root: Path,
        manifest: dict,
) -> list[str]:
    errors: list[str] = []
    files = {
        "presentation": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/ui/"
            "EmulatorSystemUiResources.java"
        ),
        "library": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/ui/library/"
            "LibraryFragment.java"
        ),
        "registry": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/domain/model/"
            "EmulatorSystemRegistry.java"
        ),
        "layout": root / "app/src/main/res/layout/popup_library_system_filter.xml",
        "jvm_test": root / (
            "app/src/test/java/com/mateussouza/emuorbit/advance/ui/"
            "EmulatorSystemUiResourcesTest.java"
        ),
        "device_test": root / (
            "app/src/androidTest/java/com/mateussouza/emuorbit/advance/ui/library/"
            "LibrarySearchSortUiInstrumentedTest.java"
        ),
    }
    try:
        sources = {
            name: path.read_text(encoding="utf-8")
            for name, path in files.items()
        }
    except OSError as error:
        return [f"N3DS-10C11 library boundary could not be inspected: {error}"]

    required_tokens = {
        "presentation": (
            "R.string.game_system_n3ds",
            "R.drawable.img_system_n3ds",
            "static Presentation forRegisteredSystem(",
            "independently from their release exposure",
            "case N3DS:",
            "return NINTENDO_3DS;",
        ),
        "library": (
            "R.id.library_system_filter_n3ds",
            "EmulatorSystem.N3DS",
            "EmulatorSystemRegistry.isLibraryVisible(",
            "option.setVisibility(userVisible ? View.VISIBLE : View.GONE)",
            "View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS",
            "R.string.library_filter_emulators",
        ),
        "layout": (
            'android:id="@+id/library_system_filter_n3ds"',
            'android:text="@string/filter_n3ds"',
            'android:visibility="gone"',
            'android:importantForAccessibility="noHideDescendants"',
        ),
        "jvm_test": (
            "keepsRegisteredNintendo3dsPresentationStable",
            "forRegisteredSystem(EmulatorSystem.N3DS)",
            'assertPresentation("N3DS"',
        ),
        "device_test": (
            "R.id.library_system_filter_n3ds",
            "Visibility.GONE",
            'setLibrarySystemFilter("N3DS")',
            "R.drawable.img_system_n3ds",
            "artwork.hasAlpha()",
        ),
    }
    for name, tokens in required_tokens.items():
        for token in tokens:
            if token not in sources[name]:
                errors.append(
                    f"N3DS-10C11 {name} source lost required token: {token}")

    if not re.search(
            r"EmulatorSystem\.N3DS,.*?Exposure\.INTERNAL_ONLY",
            sources["registry"],
            re.DOTALL):
        errors.append("N3DS-10C11 registry must keep Nintendo 3DS internal")

    filter_order = [
        "R.id.library_system_filter_nds",
        "R.id.library_system_filter_n3ds",
        "R.id.library_system_filter_gba",
        "R.id.library_system_filter_gbc",
        "R.id.library_system_filter_gb",
    ]
    positions = [sources["library"].find(token + ",") for token in filter_order]
    if -1 in positions or positions != sorted(positions):
        errors.append("N3DS-10C11 library filter ordering changed")

    for qualifier in (
            "values", "values-en", "values-es", "values-zh-rCN", "values-hi",
            "values-ar", "values-bn", "values-fr", "values-ru", "values-in"):
        resource_file = root / "app/src/main/res" / qualifier / "strings.xml"
        try:
            resource_source = resource_file.read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"N3DS-10C11 localized library resources unavailable: {error}")
            continue
        for resource_name in ("game_system_n3ds", "filter_n3ds"):
            if f'name="{resource_name}"' not in resource_source:
                errors.append(
                    f"N3DS-10C11 {qualifier} lost library resource: {resource_name}")

    artwork = next(
        (item for item in manifest.get("generatedAssets", [])
         if item.get("id") == "nintendo3ds-library-card-artwork"),
        {},
    )
    for path_field, hash_field in (
            ("sourcePath", "sourceSha256"),
            ("packagedPath", "packagedSha256")):
        relative_path = artwork.get(path_field, "")
        expected_hash = artwork.get(hash_field, "")
        try:
            actual_hash = hashlib.sha256((root / relative_path).read_bytes()).hexdigest()
        except (OSError, ValueError) as error:
            errors.append(f"N3DS-10C11 artwork unavailable: {error}")
            continue
        if actual_hash != expected_hash:
            errors.append(f"N3DS-10C11 {path_field} digest changed")
    prompt_path = artwork.get("promptPath", "")
    try:
        prompt_source = (root / prompt_path).read_text(encoding="utf-8")
    except (OSError, ValueError) as error:
        errors.append(f"N3DS-10C11 artwork prompt unavailable: {error}")
    else:
        for token in ("genuinely transparent background", "no logo", "Correction pass"):
            if token not in prompt_source:
                errors.append(f"N3DS-10C11 artwork prompt lost constraint: {token}")
    return errors


def validate_performance_profile_boundary(root: Path) -> list[str]:
    files = {
        "profile": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsPerformanceProfile.java"
        ),
        "controller": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsCoreLifecycleController.java"
        ),
        "session": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsCoreSession.java"
        ),
        "native": root / "nintendo3dscore/src/main/cpp/core_gameplay_session.cpp",
        "jni": root / "nintendo3dscore/src/main/cpp/jni_core_gameplay_session.cpp",
        "report": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsCoreFrameReport.java"
        ),
        "device_test": root / (
            "nintendo3dscore/src/androidTest/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsCoreSessionInstrumentedTest.java"
        ),
    }
    try:
        sources = {
            name: path.read_text(encoding="utf-8")
            for name, path in files.items()
        }
    except OSError as error:
        return [f"N3DS-11A performance boundary could not be inspected: {error}"]

    required_tokens = {
        "profile": (
            'CONSERVATIVE("conservative", "1", true)',
            'BALANCED("balanced", "2", true)',
            'PERFORMANCE("performance", "2", false)',
            'options.put("citra_use_disk_shader_cache", "enabled")',
            'accurateShaderMultiplication ? "enabled" : "disabled"',
            "Collections.unmodifiableMap(options)",
        ),
        "controller": (
            "setPerformanceProfile(",
            'await(transition, timeoutMs, "troca do perfil de desempenho")',
            "request.performanceProfile",
        ),
        "session": (
            "Nintendo3DsPerformanceProfile performanceProfile",
            "Objects.requireNonNull(performanceProfile).getCoreValue()",
        ),
        "native": (
            "supportedPerformanceProfile(",
            "resolutionFactorForProfile(",
            '"citra_use_cpu_jit"',
            '"citra_use_disk_shader_cache"',
            '"citra_resolution_factor"',
            'performanceProfile_ == "performance" ? "disabled" : "enabled"',
            "report_.performanceOptionRequests++",
        ),
        "jni": (
            "const std::array<std::string, 65> values",
            "report.performanceProfile",
            "report.performanceOptionRequests",
        ),
        "report": (
            "FIELD_COUNT = 65",
            "getPerformanceOptionRequests()",
            "isDiskShaderCacheEnabled()",
        ),
        "device_test": (
            "appliesPerformanceProfileBeforeSessionAndRecreatesAtomically",
            "assertEquals(1, controller.getClosedSessionCount())",
            "assertTrue(performance.getPerformanceOptionRequests() >= 11)",
        ),
    }
    errors: list[str] = []
    for name, tokens in required_tokens.items():
        for token in tokens:
            if token not in sources[name]:
                errors.append(
                    f"N3DS-11A {name} source lost required token: {token}")
    return errors


def validate_performance_measurement_boundary(root: Path) -> list[str]:
    files = {
        "collector": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsPerformanceCollector.java"
        ),
        "snapshot": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsPerformanceSnapshot.java"
        ),
        "device": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsDevicePerformanceState.java"
        ),
        "cache": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsRegenerableCachePolicy.java"
        ),
        "storage": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsStorageLayout.java"
        ),
        "controller": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsCoreLifecycleController.java"
        ),
        "collector_test": root / (
            "nintendo3dscore/src/test/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsPerformanceCollectorTest.java"
        ),
        "cache_test": root / (
            "nintendo3dscore/src/test/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsRegenerableCachePolicyTest.java"
        ),
        "device_test": root / (
            "nintendo3dscore/src/androidTest/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsCoreSessionInstrumentedTest.java"
        ),
    }
    try:
        sources = {name: path.read_text(encoding="utf-8") for name, path in files.items()}
    except OSError as error:
        return [f"N3DS-11B performance measurement boundary could not be inspected: {error}"]

    required_tokens = {
        "collector": (
            "PERCENTILE_CAPACITY_FRAMES = 120_000",
            "synchronized void recordFrame(",
            "percentileMillis(sorted, 0.95)",
            "percentileMillis(sorted, 0.99)",
            "synchronized void reset()",
        ),
        "snapshot": (
            "Aggregate-only performance evidence",
            "getEffectiveSpeedPercent()",
            "getDeviceState()",
            "getCacheReport()",
        ),
        "device": (
            "Debug.getPss()",
            "powerManager.getCurrentThermalStatus()",
            "powerManager.isPowerSaveMode()",
        ),
        "cache": (
            "scanAll()",
            "Files.isSymbolicLink(child)",
            "clearAfterCoreClosed()",
            "Files.createDirectories(scan.root)",
            "Aggregate-only cache inventory",
        ),
        "storage": ("getShaderCacheDirectory()",),
        "controller": (
            "performanceCollector.recordFrame(",
            "getPerformanceSnapshot()",
            "resetPerformanceMeasurementsAndAwait(",
            "clearRegenerableCachesAndAwait(",
            "closeCoreSessionAndCheckpointOnOwnerThread();",
            "performanceCollector.reset();",
        ),
        "collector_test": (
            "reportsBoundedAggregatePercentilesAndCanReset",
            "assertEquals(50.0, snapshot.getP99FrameMillis()",
        ),
        "cache_test": (
            "measuresAndClearsOnlyExplicitRegenerableTrees",
            "assertTrue(durable.isFile())",
        ),
        "device_test": (
            "measuresFramesAndClearsOnlyRegenerablePrivateCaches",
            "sustainsSelectedPerformanceProfileForLongRun",
            "result.getEffectiveSpeedPercent() >= 95.0",
            "assertTrue(durable.isFile())",
            "assertEquals(2, controller.getOpenedSessionCount())",
        ),
    }
    errors: list[str] = []
    for name, tokens in required_tokens.items():
        for token in tokens:
            if token not in sources[name]:
                errors.append(f"N3DS-11B {name} source lost required token: {token}")
    return errors


def validate_stage_cleanup_boundary(root: Path) -> list[str]:
    cleanup_path = root / "scripts/clean-local-build-artifacts.ps1"
    try:
        cleanup_source = cleanup_path.read_text(encoding="utf-8")
    except OSError as error:
        return [f"N3DS stage cleanup could not be inspected: {error}"]

    required_tokens = (
        "[CmdletBinding(SupportsShouldProcess = $true)]",
        "'build/reports',",
        "[switch] $RemoveNativeBuildCaches",
        "'app/.cxx'",
        "'melondscore/.cxx'",
        "'nintendo3dscore/.cxx'",
        "'scripts/__pycache__'",
        "'scripts/tests/__pycache__'",
        "$regenerablePackages = @('com.mateussouza.emuorbit.advance.test')",
        "'com.mateussouza.emuorbit.advance.n3ds.deliverytest.test'",
        "'com.mateussouza.emuorbit.advance.n3ds.deliverytest'",
        "@('uninstall', $regenerablePackage)",
        "-CleanNintendo3DsDeliveryTestPackage requires -CleanConnectedDevice.",
        "'com.mateussouza.emuorbit.advance'",
        "'com.mateussouza.emuorbit.n3ds.core.test'",
        "Pacote protegido desapareceu durante a limpeza",
        "Preservados: app principal, pacote privado 3DS, ROMs, core, saves e dados do usuário.",
    )
    for token in required_tokens:
        if token not in cleanup_source:
            errors = [f"N3DS stage cleanup lost required safeguard: {token}"]
            return errors

    forbidden_patterns = (
        r"\bpm\s+clear\b",
        r"@\(\s*['\"]uninstall['\"]\s*,\s*['\"]com\.mateussouza\.emuorbit\.advance['\"]",
        r"@\(\s*['\"]uninstall['\"]\s*,\s*['\"]com\.mateussouza\.emuorbit\.n3ds\.core\.test['\"]",
    )
    return [
        f"N3DS stage cleanup contains forbidden device operation: {pattern}"
        for pattern in forbidden_patterns
        if re.search(pattern, cleanup_source, re.IGNORECASE)
    ]


def validate_rendered_accessibility_boundary(root: Path) -> list[str]:
    files = {
        "product": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsProductActivity.java"
        ),
        "style": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsProductDialogStyleAdapter.java"
        ),
        "mateus_dialog": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/ui/MateusDialog.java"
        ),
        "configuration": root / (
            "nintendo3dscore/src/debug/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsUiTestConfiguration.java"
        ),
        "settings_host": root / (
            "nintendo3dscore/src/debug/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsSettingsTestActivity.java"
        ),
        "readiness_host": root / (
            "nintendo3dscore/src/debug/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsReadinessTestActivity.java"
        ),
        "device_test": root / (
            "nintendo3dscore/src/androidTest/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsAccessibilityInstrumentedTest.java"
        ),
    }
    try:
        sources = {
            name: path.read_text(encoding="utf-8")
            for name, path in files.items()
        }
    except OSError as error:
        return [f"N3DS-10C12 accessibility boundary could not be inspected: {error}"]

    required_tokens = {
        "product": (
            "View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS",
            "button.setContentDescription(",
            "statusView.setAccessibilityLiveRegion(",
        ),
        "style": (
            "MateusDialog owns the same responsive sizing",
        ),
        "mateus_dialog": (
            "configureAction(",
            "applyResponsiveWidth(context, dialog)",
            "applyResponsiveHeight(context, dialog, binding.getRoot())",
            "window.setLayout(responsiveWidthPx, WindowManager.LayoutParams.WRAP_CONTENT)",
            "attributes.dimAmount = 0.82f",
        ),
        "configuration": (
            "configuration.fontScale = fontScale",
            "configuration.setLocale(locale)",
            "configuration.setLayoutDirection(locale)",
            "base.createConfigurationContext(configuration)",
        ),
        "settings_host": (
            "Nintendo3DsUiTestConfiguration.wrap(newBase)",
            "Nintendo3DsProductDialogStyleAdapter",
        ),
        "readiness_host": (
            "Nintendo3DsUiTestConfiguration.wrap(newBase)",
            "new Nintendo3DsProductDialogStyleAdapter()",
        ),
        "device_test": (
            "rendersSettingsAndReadinessForTalkBackAtLargeFontInTenLocales",
            "LARGE_FONT_SCALE = 1.3f",
            '"pt-BR", "en", "es", "zh-CN", "hi", "ar", "bn", "fr", "ru", "id"',
            "AccessibilityNodeInfo",
            "assertTextNotEllipsized",
            "Bitmap.createBitmap(",
            "View.LAYOUT_DIRECTION_RTL",
        ),
    }
    errors: list[str] = []
    for name, tokens in required_tokens.items():
        for token in tokens:
            if token not in sources[name]:
                errors.append(
                    f"N3DS-10C12 {name} source lost required token: {token}")
    return errors


def validate_delivery_decision_boundary(root: Path) -> list[str]:
    files = {
        "decision": root / "nintendo3dscore/DELIVERY_ARCHITECTURE.md",
        "feature_build": root / "nintendo3dscore/build.gradle.kts",
        "feature_manifest": root / "nintendo3dscore/src/main/AndroidManifest.xml",
        "base_build": root / "app/build.gradle.kts",
        "root_build": root / "build.gradle.kts",
        "catalog": root / "gradle/libs.versions.toml",
        "delivery_title": (
            root / "app/src/main/res/values/strings_nintendo3ds_delivery.xml"),
        "bundle_audit": root / "scripts/audit-nintendo3ds-bundle.py",
        "base_application": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/"
            "EmuOrbitApplication.java"),
        "delivery_contract": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/nintendo3ds/"
            "delivery/Nintendo3DsFeatureContract.java"),
        "delivery_state": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/nintendo3ds/"
            "delivery/Nintendo3DsFeatureDeliveryState.java"),
        "delivery_coordinator": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/nintendo3ds/"
            "delivery/Nintendo3DsFeatureInstallCoordinator.java"),
        "play_installer": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/nintendo3ds/"
            "delivery/PlayNintendo3DsFeatureInstaller.java"),
        "library_fragment": root / (
            "app/src/main/java/com/mateussouza/emuorbit/advance/ui/library/"
            "LibraryFragment.java"),
        "feature_activity": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsProductActivity.java"),
        "core_bootstrap": root / (
            "nintendo3dscore/src/main/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsCoreBootstrap.java"),
        "gameplay_session": root / (
            "nintendo3dscore/src/main/cpp/core_gameplay_session.cpp"),
        "delivery_test": root / (
            "app/src/debug/java/com/mateussouza/emuorbit/advance/nintendo3ds/"
            "delivery/Nintendo3DsDeliveryTestActivity.java"),
        "delivery_probe": root / (
            "nintendo3dscore/src/debug/java/com/mateussouza/emuorbit/n3ds/core/"
            "Nintendo3DsDeliveryProbeActivity.java"),
        "delivery_script": root / "scripts/test-nintendo3ds-delivery.ps1",
    }
    try:
        sources = {
            name: path.read_text(encoding="utf-8")
            for name, path in files.items()
        }
    except OSError as error:
        return [f"N3DS-12 delivery boundary could not be inspected: {error}"]

    errors: list[str] = []
    decision_tokens = (
        "Play Feature Delivery no momento da instalação",
        "com.android.dynamic-feature",
        'dist:install-time',
        'dist:removable value="true"',
        'dist:fusing include="true"',
        "SplitCompat",
        "23.157.736 bytes",
        "A separação em feature não reduz a obrigação de código correspondente.",
        "https://developer.android.com/guide/playcore/feature-delivery/install-time",
        "https://developer.android.com/guide/playcore/asset-delivery",
    )
    for token in decision_tokens:
        if token not in sources["decision"]:
            errors.append(f"N3DS-12C delivery record lost required token: {token}")

    feature_build_tokens = (
        "alias(libs.plugins.android.dynamic.feature)",
        'implementation(project(":app"))',
        "EMUORBIT_N3DS_CORE_FILE",
        "23_157_736L",
        "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c",
        "prepareNintendo3DsCore",
        "prepareNintendo3DsComplianceAssets",
        '.file(".gradle/emuorbit-tools/pinned-n3ds-core/libazahar_libretro.so")',
        "-DEMUORBIT_N3DS_LOADER_DIAGNOSTIC=false",
        "testNintendo3DsDebugUnit",
        "org.junit.runner.JUnitCore",
    )
    for token in feature_build_tokens:
        if token not in sources["feature_build"]:
            errors.append(f"N3DS-12D1 feature build lost required token: {token}")
    if 'id("com.android.library")' in sources["feature_build"] \
            or "alias(libs.plugins.android.library)" in sources["feature_build"]:
        errors.append("N3DS-12D1 feature must not regress to an Android library")
    if 'dynamicFeatures += setOf(":nintendo3dscore")' not in sources["base_build"]:
        errors.append("N3DS-12D1 feature must remain attached to the base bundle")
    for token in (
            '"NINTENDO_3DS_INTERNAL_UX_ENABLED",\n            "true"',
            'applicationId = playStoreApplicationId'):
        if token not in sources["base_build"]:
            errors.append(
                f"N3DS standard Android Studio variants lost required token: {token}")
    for forbidden in (
            "nintendo3DsUxTestEnabled",
            'applicationIdSuffix = ".n3ds.uxtest"',
            'versionNameSuffix = "-n3ds-ux-release"'):
        if forbidden in sources["base_build"]:
            errors.append(
                f"N3DS standard Android Studio variants regained QA split: {forbidden}")
    if 'implementation(project(":nintendo3dscore"))' in sources["base_build"] \
            or 'api(project(":nintendo3dscore"))' in sources["base_build"]:
        errors.append("N3DS-12D1 base must not statically depend on feature classes")
    if "alias(libs.plugins.android.dynamic.feature) apply false" \
            not in sources["root_build"]:
        errors.append("N3DS-12D1 root build lost the dynamic-feature plugin")
    if 'id = "com.android.dynamic-feature"' not in sources["catalog"]:
        errors.append("N3DS-12D1 catalog lost the pinned dynamic-feature plugin")
    manifest_tokens = (
        'dist:instant="false"',
        "<dist:install-time>",
        '<dist:removable dist:value="true" />',
        '<dist:fusing dist:include="true" />',
        'android:exported="false"',
    )
    for token in manifest_tokens:
        if token not in sources["feature_manifest"]:
            errors.append(f"N3DS-12D1 feature manifest lost required token: {token}")
    if "nintendo3ds_delivery_title" not in sources["delivery_title"]:
        errors.append("N3DS-12D1 base lost the Play delivery title")
    bundle_audit_tokens = (
        "EXPECTED_CORE_SHA256",
        "feature native payload differs from the approved core and bootstrap",
        "Nintendo 3DS feature classes leaked into base DEX",
        "feature is not declared for install-time delivery",
        "install-time feature is not kept as a removable split",
    )
    for token in bundle_audit_tokens:
        if token not in sources["bundle_audit"]:
            errors.append(f"N3DS-12D1 bundle auditor lost required token: {token}")

    d2_tokens = {
        "base_build": (
            "implementation(libs.play.feature.delivery)",
            "testNintendo3DsDeliveryDebugUnit",
        ),
        "feature_build": ("implementation(libs.play.feature.delivery)",),
        "catalog": (
            'playFeatureDelivery = "2.1.0"',
            "com.google.android.play:feature-delivery",
        ),
        "base_application": ("extends SplitCompatApplication",),
        "feature_activity": ("SplitCompat.installActivity(this)",),
        "delivery_contract": (
            'MODULE_NAME = "nintendo3dscore"',
            "ENTRY_ACTIVITY_CLASS",
            "setClassName(",
        ),
        "delivery_state": (
            "REQUIRES_USER_CONFIRMATION",
            "boolean canCancel()",
            "boolean canRetry()",
        ),
        "delivery_coordinator": (
            "getSessionStates(",
            "requestInstall()",
            "cancelInstall()",
            "retryInstall()",
            "installer.unregister(sessionListener)",
        ),
        "play_installer": (
            "SplitInstallManagerFactory.create(",
            "SplitInstallRequest.newBuilder()",
            "manager.registerListener(",
            "manager.getSessionStates()",
            "manager.startInstall(request)",
            "manager.cancelInstall(sessionId)",
            "SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION",
        ),
    }
    for name, tokens in d2_tokens.items():
        for token in tokens:
            if token not in sources[name]:
                errors.append(
                    f"N3DS-12D2 {name} source lost required token: {token}")

    d3_tokens = {
        "feature_build": (
            "Stages only the minimum Nintendo 3DS runtime notice",
        ),
        "bundle_audit": (
            "the external corresponding-source SBOM leaked into the app bundle",
            "--allow-isolated-delivery-test-probe",
        ),
        "base_build": (
            'applicationIdSuffix = ".n3ds.deliverytest"',
            'tasks.matching { it.name.endsWith("GoogleServices") }',
            "enabled = !nintendo3DsDeliveryTestEnabled",
            "EMUORBIT_N3DS_DELIVERY_TEST_VERSION_CODE",
        ),
        "core_bootstrap": (
            "p(checkedContext.getAssets())",
            'PACKAGED_CORE_LIBRARY_PATH = "azahar_libretro.so"',
        ),
        "gameplay_session": (
            "loadableCorePath(",
            'std::strcmp(path, "azahar_libretro.so") == 0',
        ),
        "delivery_test": (
            "MODE_STATUS",
            "MODE_INSTALL",
            "STATE:",
            "ACTION:START_PROBE",
        ),
        "delivery_probe": (
            "Nintendo3DsCoreBootstrap.inspectPackaged(this)",
            "PROBE:SUCCESS:",
            "runFrames(",
        ),
        "delivery_script": (
            "--local-testing",
            "--adb=$script:adb",
            "SHA256]::Create()",
            "installTimePresence",
            "nativeAtFirstLaunch",
            "processRecreation",
            "privateDataPreservedAcrossUpdate",
            "Set-NetworkOffline",
            "Restore-Network",
        ),
        "decision": (
            "GLP-EmuOrbitAzahar3ds",
            "runtime compilado",
            "aviso legal",
        ),
    }
    for name, tokens in d3_tokens.items():
        for token in tokens:
            if token not in sources[name]:
                errors.append(
                    f"N3DS-12D3 {name} source lost required token: {token}")

    for forbidden in (
            "nintendo3DsLaunchCoordinator.requestInstall()",
            "nintendo3DsLaunchCoordinator.retryInstall()",
            "nintendo3DsLaunchCoordinator.cancelInstall()",
            "requestUserConfirmation()"):
        if forbidden in sources["library_fragment"]:
            errors.append(
                "N3DS install-time Library flow still exposes runtime delivery: "
                + forbidden)

    base_source_root = root / "app/src/main/java"
    for path in base_source_root.rglob("*.java"):
        source = path.read_text(encoding="utf-8")
        if re.search(
                r"^\s*import\s+com\.mateussouza\.emuorbit\.n3ds\.core\.",
                source,
                re.MULTILINE):
            errors.append(
                "N3DS-12D2 base source has a static feature import: "
                f"{path.relative_to(root).as_posix()}")
    return errors


def audit_repository(root: Path, manifest: dict) -> tuple[list[str], list[str]]:
    errors = validate_manifest(manifest)
    if errors:
        return errors, []
    baseline = manifest["implementationBaselineCommit"]
    _run_git(root, "merge-base", "--is-ancestor", baseline, "HEAD")
    candidates = sorted(path for path in implementation_paths(root, baseline) if is_source_path(path))
    patterns = manifest["publicationPathPatterns"]
    uncovered = [path for path in candidates if not matches_scope(path, patterns)]
    publication = manifest.get("publication", {})
    if publication.get("publicSourceDiscrepancyBlocksImplementation") is True:
        errors.extend(
            f"implementation source is outside public scope: {path}"
            for path in uncovered
        )
    recorded_source_count = manifest.get("n3ds12Decision", {}).get(
        "validation", {}).get("mappedImplementationSourceFiles")
    if publication.get("publicSourceDiscrepancyBlocksImplementation") is True \
            and recorded_source_count != len(candidates):
        errors.append(
            "mapped Nintendo 3DS implementation source count differs from Git: "
            f"recorded {recorded_source_count}, observed {len(candidates)}"
        )
    errors.extend(validate_activity_result_boundary(root))
    errors.extend(validate_experience_settings_boundary(root))
    errors.extend(validate_product_entry_boundary(root))
    errors.extend(validate_conditional_library_presentation_boundary(root, manifest))
    errors.extend(validate_performance_profile_boundary(root))
    errors.extend(validate_performance_measurement_boundary(root))
    errors.extend(validate_stage_cleanup_boundary(root))
    errors.extend(validate_rendered_accessibility_boundary(root))
    errors.extend(validate_delivery_decision_boundary(root))
    return errors, candidates


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--root", type=Path, default=PROJECT_ROOT)
    args = parser.parse_args(argv)

    manifest = load_manifest(args.manifest)
    try:
        errors, candidates = audit_repository(args.root.resolve(), manifest)
    except (subprocess.CalledProcessError, OSError, ValueError) as error:
        print(f"Nintendo 3DS source-scope audit failed: {error}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(
        "Nintendo 3DS source-scope audit approved: "
        f"{len(candidates)} current implementation source file(s) covered by the "
        "frozen path policy; the pinned public package was not changed."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
