import java.security.MessageDigest
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.library")
}

val expectedCoreBytes = 23_157_736L
val expectedCoreSha256 =
    "64221f5ca8e731846523669dab3ca569f796ccee57f5e4f78b29b4e0330a734c"
val configuredCorePath = providers
    .gradleProperty("EMUORBIT_N3DS_CORE_FILE")
    .orElse(providers.environmentVariable("EMUORBIT_N3DS_CORE_FILE"))
val generatedCoreDirectory = layout.buildDirectory
    .dir("generated/nintendo3ds-core/jniLibs/arm64-v8a")

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

val prepareNintendo3DsCore by tasks.registering(Sync::class) {
    from(configuredCorePath)
    into(generatedCoreDirectory)
    rename { "libazahar_libretro.so" }
    inputs.property("expectedCoreBytes", expectedCoreBytes)
    inputs.property("expectedCoreSha256", expectedCoreSha256)
    outputs.file(generatedCoreDirectory.map { it.file("libazahar_libretro.so") })

    doFirst {
        val configuredPath = configuredCorePath.orNull
            ?: throw GradleException(
                "EMUORBIT_N3DS_CORE_FILE must point to the reproduced Azahar core"
            )
        val source = rootProject.file(configuredPath)
        if (!source.isFile || source.length() != expectedCoreBytes
                || sha256Hex(source) != expectedCoreSha256) {
            throw GradleException("Nintendo 3DS core identity mismatch: $source")
        }
    }
}

android {
    namespace = "com.mateussouza.emuorbit.n3ds.core"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { targets += "emuorbit_n3ds_bootstrap" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // The exact on-demand manifest remains in src/main for source
            // correspondence. The isolated AAR uses this host-free manifest.
            manifest.srcFile("src/standalone/AndroidManifest.xml")
            jniLibs.directories.add(generatedCoreDirectory.get().asFile.parentFile.absolutePath)
            assets.directories.add(file("compliance").absolutePath)
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("com.google.android.play:feature-delivery:2.1.0")
}

tasks.matching { task ->
    task.name.startsWith("merge") &&
        (task.name.endsWith("JniLibFolders") || task.name.endsWith("NativeLibs"))
}.configureEach {
    dependsOn(prepareNintendo3DsCore)
}
