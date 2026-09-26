import java.security.MessageDigest
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

plugins {
    alias(libs.plugins.android.dynamic.feature)
}

val expectedCoreBytes = 23_157_736L
val expectedCoreSha256 =
    "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c"
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

val prepareNintendo3DsCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Validates and seals the pinned Azahar core in a protected asset"
    inputs.property("expectedCoreBytes", expectedCoreBytes)
    inputs.property("expectedCoreSha256", expectedCoreSha256)
    inputs.property("protectedBuildSeed", protectedBuildSeed)
    inputs.property("protectedCoreContext", protectedCoreContext)
    inputs.property("protectedCoreAssetName", protectedCoreAssetName)
    outputs.file(generatedCoreAssets.map { it.file(protectedCoreAssetName) })

    doFirst {
        val configuredPath = configuredCorePath.get()
        val source = rootProject.file(configuredPath)
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
        "GamepadVirtualControlsCoordinator*.class"
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
            "com.mateussouza.emuorbit.advance.n3ds.uxtest"
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
        "bundleDebugClassesToRuntimeJar"
    )
    val testSources = fileTree("src/test/java") { include("**/*Test.java") }
    inputs.files(testSources)
    inputs.dir(nintendo3DsDebugUnitTestClasses)
    inputs.file(nintendo3DsDebugRuntimeClasses)
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
            "-Dfile.encoding=UTF-8",
            "-cp",
            files(
                nintendo3DsDebugUnitTestClasses,
                nintendo3DsDebugRuntimeClasses,
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
