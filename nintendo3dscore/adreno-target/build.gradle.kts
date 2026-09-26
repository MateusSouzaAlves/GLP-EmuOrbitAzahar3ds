import java.security.MessageDigest
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
}

val expectedCoreBytes = 23_157_736L
val expectedCoreSha256 =
    "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c"
val expectedContentBytes = 713_384L
val expectedContentSha256 =
    "00fb87d97ecb866a99902740ab67e38e05f81d74295e0c3774eb62b90b0a335b"
val expectedContentLicenseBytes = 1_071L
val expectedContentLicenseSha256 =
    "e7263faf3265216f672f54cedc9f8e112182c80c8b97031b53379fefc0b42f32"

val configuredCorePath = providers
    .gradleProperty("EMUORBIT_N3DS_CORE_FILE")
    .orElse(providers.environmentVariable("EMUORBIT_N3DS_CORE_FILE"))
val configuredContentPath = providers
    .gradleProperty("EMUORBIT_N3DS_ADRENO_CONTENT_FILE")
    .orElse(providers.environmentVariable("EMUORBIT_N3DS_ADRENO_CONTENT_FILE"))
val configuredContentLicensePath = providers
    .gradleProperty("EMUORBIT_N3DS_ADRENO_CONTENT_LICENSE_FILE")
    .orElse(providers.environmentVariable("EMUORBIT_N3DS_ADRENO_CONTENT_LICENSE_FILE"))
val generatedAssets = layout.buildDirectory.dir("generated/adreno-target-assets")
val generatedJni = layout.buildDirectory.dir("generated/adreno-target-jni")
val nintendo3DsDebugRuntimeClasses = project(":nintendo3dscore").layout.buildDirectory.file(
    "intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar"
)

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

fun requirePinnedFile(
    configuredPath: String?,
    expectedBytes: Long,
    expectedSha256: String,
    label: String
): File {
    val path = configuredPath
        ?: throw GradleException("$label path was not configured for the Adreno QA target")
    val source = rootProject.file(path)
    if (!source.isFile || source.length() != expectedBytes || sha256Hex(source) != expectedSha256) {
        throw GradleException("$label does not match its pinned identity")
    }
    return source
}

val prepareNintendo3DsAdrenoTargetAssets by tasks.registering(Sync::class) {
    group = "verification"
    description = "Stages pinned, redistributable inputs only for the private Adreno QA target"
    into(generatedAssets.map { it.dir("n3ds-adreno") })
    from(configuredCorePath) { rename { "azahar_libretro.so" } }
    from(configuredContentPath) { rename { "open-homebrew.3dsx" } }
    from(configuredContentLicensePath) { rename { "MARS3DS_LICENSE.txt" } }
    from(rootProject.file("nintendo3dscore/compliance/THIRD_PARTY_NOTICES.txt"))

    doFirst {
        requirePinnedFile(
            configuredCorePath.orNull,
            expectedCoreBytes,
            expectedCoreSha256,
            "Nintendo 3DS core"
        )
        requirePinnedFile(
            configuredContentPath.orNull,
            expectedContentBytes,
            expectedContentSha256,
            "Nintendo 3DS open homebrew"
        )
        requirePinnedFile(
            configuredContentLicensePath.orNull,
            expectedContentLicenseBytes,
            expectedContentLicenseSha256,
            "Nintendo 3DS open homebrew license"
        )
    }
}

val prepareNintendo3DsAdrenoTargetJni by tasks.registering(Sync::class) {
    group = "verification"
    description = "Stages only the ARM64 JNI bridge required by the private cloud target"
    dependsOn(":nintendo3dscore:mergeDebugNativeLibs")
    from(rootProject.layout.projectDirectory.dir(
        "nintendo3dscore/build/intermediates/merged_native_libs/" +
            "debug/mergeDebugNativeLibs/out/lib"
    )) {
        include("arm64-v8a/libemuorbit_n3ds_bootstrap.so")
    }
    into(generatedJni)
}

android {
    namespace = "com.mateussouza.emuorbit.n3ds.adreno.target"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mateussouza.emuorbit.n3ds.adreno.target"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            assets.directories.add(generatedAssets.get().asFile.absolutePath)
            jniLibs.directories.add(generatedJni.get().asFile.absolutePath)
        }
    }

    lint {
        disable += "BidiSpoofing"
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareNintendo3DsAdrenoTargetAssets)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(prepareNintendo3DsAdrenoTargetJni)
}

dependencies {
    // The Firebase target is private QA infrastructure. Embedding the feature's
    // debug runtime here keeps its lifecycle Activity in the target process so
    // ActivityScenario and the controller observe the same static/session state.
    implementation(
        files(nintendo3DsDebugRuntimeClasses)
            .builtBy(":nintendo3dscore:bundleDebugClassesToRuntimeJar")
    )
}
