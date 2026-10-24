import java.security.MessageDigest
import groovy.json.JsonSlurper
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

plugins {
    alias(libs.plugins.android.dynamic.feature)
}

val baselineCoreBytes = 23_157_736L
val baselineCoreSha256 =
    "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c"
val coreSourceScope = JsonSlurper().parse(
    rootProject.file("config/nintendo3ds-source-scope.json")
) as Map<*, *>
val recoveryCoreBuild = coreSourceScope["playQualityRecoveryCore"] as? Map<*, *>
val expectedCoreBytes = (recoveryCoreBuild?.get("strippedBytes") as? Number)?.toLong()
    ?: baselineCoreBytes
val expectedCoreSha256 = recoveryCoreBuild?.get("strippedSha256") as? String
    ?: baselineCoreSha256
check(expectedCoreBytes == baselineCoreBytes && expectedCoreSha256.matches(Regex("[0-9a-f]{64}"))) {
    "Invalid pinned Nintendo 3DS recovery core identity"
}
check(recoveryCoreBuild == null || recoveryCoreBuild["status"] == "PINNED_RELEASE_CANDIDATE") {
    "Nintendo 3DS recovery core must have explicit validation state"
}
val protectedBuildSeed = rootProject.extra["emuorbitProtectedBuildSeed"] as String
val protectedCoreContext = "n3ds-arm64-v8a"
val configuredCorePath = providers
    .gradleProperty("EMUORBIT_N3DS_CORE_FILE")
    .orElse(providers.environmentVariable("EMUORBIT_N3DS_CORE_FILE"))
    .orElse(
        rootProject.layout.projectDirectory
            .file(".gradle/emuorbit-tools/pinned-n3ds-core/libazahar_libretro.so")
            .asFile.absolutePath
    )
val deliveryTestEnabled = providers
    .gradleProperty("EMUORBIT_N3DS_DELIVERY_TEST")
    .orElse(providers.environmentVariable("EMUORBIT_N3DS_DELIVERY_TEST"))
    .orNull
    .orEmpty()
    .trim()
    .lowercase()
    .let { value ->
        when (value) {
            "", "false" -> false
            "true" -> true
            else -> throw GradleException(
                "EMUORBIT_N3DS_DELIVERY_TEST must be either true or false"
            )
        }
    }
val adrenoCloudTestEnabled = providers
    .gradleProperty("EMUORBIT_N3DS_ADRENO_CLOUD_TEST")
    .orElse(providers.environmentVariable("EMUORBIT_N3DS_ADRENO_CLOUD_TEST"))
    .orNull
    .orEmpty()
    .trim()
    .lowercase()
    .let { value ->
        when (value) {
            "", "false" -> false
            "true" -> true
            else -> throw GradleException(
                "EMUORBIT_N3DS_ADRENO_CLOUD_TEST must be either true or false"
            )
        }
    }
fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.US_ASCII))
    .joinToString("") { "%02x".format(it) }

val protectedCoreAssetName = "q" + sha256Text(
    "$protectedBuildSeed|$protectedCoreContext|container-asset"
).take(23)
val generatedCoreAssets = layout.buildDirectory
    .dir("generated/nintendo3ds-core/assets")
val generatedComplianceAssets = layout.buildDirectory
    .dir("generated/nintendo3ds-compliance-assets")
val generatedSelfContainedTestJni = layout.buildDirectory
    .dir("generated/nintendo3ds-self-test-jni")

fun sha256Hex(file: File): String = file.inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

fun validatedNintendo3DsCore(): File {
    val source = rootProject.file(configuredCorePath.get())
    if (!source.isFile) {
        throw GradleException("Nintendo 3DS core does not exist: $source")
    }
    val observedBytes = source.length()
    val observedSha256 = sha256Hex(source)
    if (observedBytes != expectedCoreBytes || observedSha256 != expectedCoreSha256) {
        throw GradleException(
            "Nintendo 3DS core identity mismatch: expected " +
                "$expectedCoreBytes bytes/$expectedCoreSha256, observed " +
                "$observedBytes bytes/$observedSha256"
        )
    }
    return source
}

val prepareNintendo3DsCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Validates and seals the pinned Azahar core in a protected asset"
    inputs.property("expectedCoreBytes", expectedCoreBytes)
    inputs.property("expectedCoreSha256", expectedCoreSha256)
    inputs.property("protectedBuildSeed", protectedBuildSeed)
    inputs.property("protectedCoreContext", protectedCoreContext)
    inputs.property("protectedCoreAssetName", protectedCoreAssetName)
    inputs.file(configuredCorePath.map { rootProject.file(it) })
        .withPathSensitivity(PathSensitivity.NONE)
    outputs.file(generatedCoreAssets.map { it.file(protectedCoreAssetName) })

    doFirst {
        // Recheck at use time as well as before execution of the task graph.
        val source = validatedNintendo3DsCore()
        // The asset name changes with the seed. Remove only this task-owned
        // staging directory so an incremental build cannot package an older container too.
        project.delete(generatedCoreAssets.get().asFile)
        val pythonCommand = if (
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        ) {
            listOf("py", "-3")
        } else {
            listOf("python3")
        }
        commandLine(
            pythonCommand + listOf(
                rootProject.file("scripts/package_core_container.py").absolutePath,
                "pack",
                "--input", source.absolutePath,
                "--output", generatedCoreAssets.get().file(protectedCoreAssetName)
                    .asFile.absolutePath,
                "--seed", protectedBuildSeed,
                "--context", protectedCoreContext
            )
        )
    }
}

// Only packaging graphs need the binary: IDE sync, model checks and Java/unit
// compilation remain usable without it. Refuse missing/stale cores before any
// expensive native work (even when the sealed asset was previously up to date).
gradle.taskGraph.whenReady {
    if (hasTask(prepareNintendo3DsCore.get())) {
        validatedNintendo3DsCore()
    }
}

val prepareNintendo3DsComplianceAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages only the minimum Nintendo 3DS runtime notice"
    from(file("compliance/THIRD_PARTY_NOTICES.txt"))
    into(generatedComplianceAssets.map { it.dir("nintendo3ds") })
}

val prepareNintendo3DsSelfContainedTestJni by tasks.registering(Sync::class) {
    group = "verification"
    description = "Stages only the ARM64 JNI bridge used by the private 3DS harness"
    dependsOn("mergeDebugNativeLibs")
    from(
        layout.buildDirectory.dir(
            "intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"
        )
    ) {
        include("arm64-v8a/libemuorbit_n3ds_bootstrap.so")
    }
    into(generatedSelfContainedTestJni)
}

tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        jvmArgs("-Dfile.encoding=windows-1252")
    }
}

android {
    namespace = "com.mateussouza.emuorbit.n3ds.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["EMUORBIT_N3DS_DELIVERY_TEST_ENABLED"] =
            deliveryTestEnabled.toString()

        externalNativeBuild {
            cmake {
                targets += "emuorbit_n3ds_bootstrap"
                arguments += "-DEMUORBIT_BUILD_SEED=$protectedBuildSeed"
                arguments += "-DEMUORBIT_N3DS_LOADER_DIAGNOSTIC=false"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // A dynamic feature must resolve the *same* base variant. Falling back
        // to release binds its manifest to the normal applicationId, producing
        // an invalid bundle when the private base carries an applicationIdSuffix.
        // R8 remains owned by the base module; no separate signing/ID is set here.
        if (providers.gradleProperty("EMUORBIT_PS2_PRODUCT_DELIVERY_QA")
                .orNull?.trim()?.lowercase() == "true") {
            create("ps2ProductQaRelease") {
                initWith(getByName("release"))
                matchingFallbacks += listOf("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(generatedCoreAssets.get().asFile.absolutePath)
            assets.directories.add(
                generatedComplianceAssets.get().asFile.absolutePath
            )
        }
        getByName("androidTest") {
            // The private physical-test APK is self-targeted. Keep the feature's
            // debug-only Activity hosts and feature resources in that APK rather
            // than in the user's app. AGP does not merge dynamic-feature resources
            // into a self-targeted instrumentation package automatically.
            java.directories.add(file("src/debug/java").absolutePath)
            res.directories.add(file("src/main/res").absolutePath)
            assets.directories.add(generatedCoreAssets.get().asFile.absolutePath)
            jniLibs.directories.add(generatedSelfContainedTestJni.get().asFile.absolutePath)
        }
    }

    lint {
        // AGP 9.3.0's BidiSpoofing detector requires List.removeLast(), unavailable on JDK 17.
        // scripts/verify-repository-policy.ps1 enforces the same check on every tracked text file.
        disable += "BidiSpoofing"
    }
}

val nintendo3DsDebugRuntimeClasses = layout.buildDirectory.file(
    "intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar"
)
val baseDebugRuntimeClasses = project(":app").layout.buildDirectory.file(
    "intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar"
)
val nintendo3DsSelfContainedBaseClassIncludes = listOf(
    "com/mateussouza/emuorbit/advance/domain/model/EmulatorSystem.class",
    "com/mateussouza/emuorbit/advance/data/preferences/" +
        "GamepadVirtualControlsPreferenceStore*.class",
    "com/mateussouza/emuorbit/advance/ui/emulation/input/" +
        "ExternalGamepadMonitor*.class",
    "com/mateussouza/emuorbit/advance/ui/emulation/input/" +
        "GamepadVirtualControlsCoordinator*.class",
    "com/mateussouza/emuorbit/advance/data/save/sync/SaveSyncRuntime*.class",
    // Complete dependency closure for the owner's observed-mapping journal,
    // without importing the whole base app/ads/Firebase into this QA UID.
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupStreamingCodec\$Guard.class",
    // Bounded private payload preparation is shared with the feature harness.
    // This is not transport activation and does not include base workers/UI.
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupArtifact.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupArtifact\$*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupStreamingCodec*.class",
    // The real local capture adapter needs the immutable artifact store, not
    // just the E3S decoder. No worker, Google service, UI or transport is added.
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupArtifactStore*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/Nintendo3DsSaveBackupBridge*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/Nintendo3DsCaptureStatus.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/Nintendo3DsBackupDispatch.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/Nintendo3DsRestoreSelectionBridge*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/Nintendo3DsRestorePreview.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/SaveProtectionCoverage.class",
    // Fake-connection ART transport contracts in the disposable UID only.
    // No OAuth client, base worker/UI or actual cloud connection is included.
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/DriveArtifact*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/DriveDownloadJournalCodec*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupDocumentInputStream*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupConsent.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupConsentRepository*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupMetadataLease*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupMetadataDigest*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveApplicationReceipt*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupRetention.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupRetention\$*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupRetentionAdmission*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupRetentionPermit.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/CommonStateLibraryCapture*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/RestoreLaunchPlan*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/RestoreLaunchJournal*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/RestoreLaunchJournalCodec*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/RestoreLaunchJournalFile*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveProtectionProcess.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveResumeFence*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/CommonSaveResumeFenceFile*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/SaveBackupConsentFile*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/DriveBackupBinding*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/EssentialSaveCodec*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/EssentialSaveSnapshot*.class",
    // Typed Android delivery contracts use fresh private roots in the same
    // disposable UID. Only their filesystem/codec dependency closure is added;
    // no BackupAgent, Worker, Google client, Room, ads or app UI is imported.
    "com/mateussouza/emuorbit/advance/data/save/protection/v2/AndroidSavePackage*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/ProtectedSaveStore*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/EssentialSaveRetention*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/EssentialSaveReconciler*.class",
    "com/mateussouza/emuorbit/advance/data/save/protection/DriveSaveTransfer*.class",
    "com/mateussouza/emuorbit/advance/data/storage/AtomicFileIo*.class",
    "com/mateussouza/emuorbit/advance/domain/save/SaveStateSlots.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsLoadedSaveMapping*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsSaveCapture*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsSavePreview*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsSaveRestoreStaging*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsSaveRestoreTransaction*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsSaveSource*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsSaveCensus*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsSaveDescriptor*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsDataSource*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsFormat*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsHeaderMetadata*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsHeaderParser*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsIdentity*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsArchiveIdentity*.class",
    "com/mateussouza/emuorbit/advance/data/storage/Nintendo3DsRejectedException*.class"
)
val prepareNintendo3DsSelfContainedBaseClasses by tasks.registering(Jar::class) {
    group = "verification"
    description = "Stages only shared gamepad classes used by the isolated 3DS harness"
    dependsOn(":app:bundleDebugClassesToRuntimeJar")
    archiveFileName.set("emuorbit-n3ds-base-test-runtime.jar")
    destinationDirectory.set(
        layout.buildDirectory.dir("generated/nintendo3ds-self-test-runtime")
    )
    inputs.property("includedBaseClasses", nintendo3DsSelfContainedBaseClassIncludes)
    from(baseDebugRuntimeClasses.map { zipTree(it.asFile) }) {
        include(*nintendo3DsSelfContainedBaseClassIncludes.toTypedArray())
    }
}
val nintendo3DsSelfContainedTestRuntime = configurations.create(
    "nintendo3DsSelfContainedTestRuntime"
) {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.ANDROID)
        )
    }
    resolutionStrategy.force("androidx.tracing:tracing:1.1.0")
    exclude(group = "androidx.annotation", module = "annotation")
}

dependencies {
    implementation(project(":app"))
    implementation(libs.play.feature.delivery)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation("androidx.annotation:annotation-experimental:1.5.0")
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.espresso.accessibility)
    androidTestImplementation("androidx.annotation:annotation-jvm:1.10.0")
    androidTestImplementation("androidx.lifecycle:lifecycle-common:2.11.0")
    // AGP normally treats libraries shared with the tested app as provided.
    // Flatten only the runtimes exercised before/in the isolated harness;
    // importing debugRuntimeClasspath would also merge the app's ads/Firebase
    // manifests and other emulator modules into this private test package.
    add(nintendo3DsSelfContainedTestRuntime.name,
        "org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    add(nintendo3DsSelfContainedTestRuntime.name,
        "androidx.tracing:tracing:1.1.0")
    add(nintendo3DsSelfContainedTestRuntime.name, libs.androidx.activity)
    add(nintendo3DsSelfContainedTestRuntime.name, libs.androidx.lifecycle.livedata)
    add(nintendo3DsSelfContainedTestRuntime.name,
        "androidx.lifecycle:lifecycle-common:2.11.0")
    add(nintendo3DsSelfContainedTestRuntime.name,
        "com.google.guava:guava:31.1-android")
    add(nintendo3DsSelfContainedTestRuntime.name, "javax.inject:javax.inject:1")
    androidTestImplementation(files(nintendo3DsSelfContainedTestRuntime))
    androidTestImplementation(
        files(nintendo3DsDebugRuntimeClasses).builtBy("bundleDebugClassesToRuntimeJar")
    )
    androidTestImplementation(files(prepareNintendo3DsSelfContainedBaseClasses))
}


tasks.matching { it.name == "mergeDebugAndroidTestJniLibFolders" }.configureEach {
    dependsOn(prepareNintendo3DsSelfContainedTestJni)
}

tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("Assets")
}.configureEach {
    dependsOn(prepareNintendo3DsCore, prepareNintendo3DsComplianceAssets)
}

// AGP's lint analysis and lint-model tasks consume the generated core and
// compliance notice directly, so declare their producers instead of relying
// on whichever task order Gradle happens to choose.
tasks.matching { task ->
    val normalizedName = task.name.lowercase()
    normalizedName.startsWith("lintanalyze") ||
        normalizedName.contains("lintvital") ||
        normalizedName.contains("lintmodel")
}.configureEach {
    dependsOn(prepareNintendo3DsCore, prepareNintendo3DsComplianceAssets)
    inputs.files(prepareNintendo3DsCore)
    inputs.dir(generatedComplianceAssets)
}

tasks.matching { it.name == "processDebugAndroidTestManifest" }.configureEach {
    // Dynamic-feature tests normally target the base app. The physical core
    // harness deliberately runs under its own debuggable UID so private test
    // dumps and generated state never enter the user's application sandbox.
    inputs.property("adrenoCloudTestEnabled", adrenoCloudTestEnabled)
    doLast {
        val manifest = layout.buildDirectory.file(
            "intermediates/packaged_manifests/debugAndroidTest/" +
                "processDebugAndroidTestManifest/AndroidManifest.xml"
        ).get().asFile
        val baseTargets = listOf(
            "com.mateussouza.emuorbit.advance",
            "com.mateussouza.emuorbit.advance.n3ds.uxtest",
            // UI QA can exercise all eight systems from the isolated library package.
            "com.mateussouza.emuorbit.advance.n64.librarytest"
        )
        val isolatedTarget = if (adrenoCloudTestEnabled) {
            "com.mateussouza.emuorbit.n3ds.adreno.target"
        } else {
            "com.mateussouza.emuorbit.n3ds.core.test"
        }
        val source = manifest.readText(Charsets.UTF_8)
        when {
            source.contains("android:targetPackage=\"$isolatedTarget\"") -> Unit
            baseTargets.any { source.contains("android:targetPackage=\"$it\"") } -> {
                val baseTarget = baseTargets.first {
                    source.contains("android:targetPackage=\"$it\"")
                }
                manifest.writeText(
                    source.replace(
                        "android:targetPackage=\"$baseTarget\"",
                        "android:targetPackage=\"$isolatedTarget\""
                    ),
                    Charsets.UTF_8
                )
            }
            else -> throw GradleException(
                "Unexpected Nintendo 3DS instrumentation target in $manifest"
            )
        }
    }
}

// AGP 9.3.0 discovers these dynamic-feature tests but its generated Test task
// cannot load the compiled classes on Windows. Keep the public AGP lifecycle
// task while delegating execution to a short JUnit process with the feature and
// test outputs made explicit.
val nintendo3DsDebugUnitTestClasses = layout.buildDirectory.dir(
    "intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"
)
val nintendo3DsUnitTestRunner = configurations.create("nintendo3DsUnitTestRunner") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add(nintendo3DsUnitTestRunner.name, libs.junit4)

val testNintendo3DsDebugUnit by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs Nintendo 3DS debug unit tests without AGP's Unicode-path worker"
    dependsOn(
        "compileDebugUnitTestJavaWithJavac",
        "bundleDebugClassesToRuntimeJar",
        ":app:bundleDebugClassesToRuntimeJar"
    )
    val testSources = fileTree("src/test/java") { include("**/*Test.java") }
    inputs.files(testSources)
    inputs.dir(nintendo3DsDebugUnitTestClasses)
    inputs.file(nintendo3DsDebugRuntimeClasses)
    inputs.file(baseDebugRuntimeClasses)
    inputs.files(nintendo3DsUnitTestRunner)

    doFirst {
        val sdkDirectory = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val expectedAndroidJar = sdkDirectory.resolve(
            "platforms/android-${libs.versions.compileSdk.get()}/android.jar"
        )
        val androidJar = expectedAndroidJar.takeIf(File::isFile)
            ?: sdkDirectory.resolve("platforms").listFiles()
                ?.filter {
                    it.isDirectory &&
                        it.name.startsWith("android-${libs.versions.compileSdk.get()}.") &&
                        it.resolve("android.jar").isFile
                }
                ?.maxByOrNull { it.name }
                ?.resolve("android.jar")
            ?: throw GradleException("Android API stub not found: $expectedAndroidJar")
        val testRoot = file("src/test/java")
        val testClassNames = testSources.files
            .sortedBy { it.invariantSeparatorsPath }
            .map {
                it.relativeTo(testRoot).invariantSeparatorsPath
                    .removeSuffix(".java")
                    .replace('/', '.')
            }
        if (testClassNames.isEmpty()) {
            throw GradleException("No Nintendo 3DS unit tests were discovered")
        }
        val javaExecutable = File(
            System.getProperty("java.home"),
            if (System.getProperty("os.name").startsWith("Windows", true)) {
                "bin/java.exe"
            } else {
                "bin/java"
            }
        )
        commandLine(
            javaExecutable,
            "-Xmx256m",
            "-XX:ActiveProcessorCount=2",
            "-Dfile.encoding=UTF-8",
            "-cp",
            files(
                nintendo3DsDebugUnitTestClasses,
                nintendo3DsDebugRuntimeClasses,
                baseDebugRuntimeClasses,
                nintendo3DsUnitTestRunner,
                androidJar
            ).asPath,
            "org.junit.runner.JUnitCore",
            *testClassNames.toTypedArray()
        )
    }
}

tasks.withType<Test>().matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(testNintendo3DsDebugUnit)
    onlyIf("delegated to testNintendo3DsDebugUnit for AGP 9.3.0") { false }
}
