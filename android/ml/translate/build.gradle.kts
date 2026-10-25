import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Section 5 tier 1 runs llama.cpp. This project is not a git checkout, so the source
// arrives as a pinned release tarball rather than a submodule: downloaded once, verified
// against a SHA-256, and extracted into the build directory. The same discipline
// `ModelStore` applies to model weights, applied to the code that runs them -- a native
// library built from whatever happened to be at a URL that day is not reproducible.
//
// To move to a newer llama.cpp: change both constants. The build fails loudly rather than
// silently using a different tree if only one is changed.
val llamaVersion = "v0.2.0"
val llamaSha256 = "72e6c3e70c584f84e61697e449ee388f43458d662ef8f3bd3f6b4a054c947958"
val llamaUrl = "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$llamaVersion.tar.gz"

// The tarball's own root directory, which is the tag without its leading "v".
val llamaRoot = layout.buildDirectory.dir("llama.cpp/llama.cpp-${llamaVersion.removePrefix("v")}")

val fetchLlama by tasks.registering {
    description = "Downloads and verifies the pinned llama.cpp source for the JNI build."
    val archive = layout.buildDirectory.file("llama.cpp/$llamaVersion.tar.gz")
    val into = layout.buildDirectory.dir("llama.cpp")
    val marker = layout.buildDirectory.file("llama.cpp/.verified-$llamaSha256")

    outputs.file(marker)
    outputs.upToDateWhen { marker.get().asFile.exists() }

    doLast {
        val target = archive.get().asFile
        target.parentFile.mkdirs()

        if (!target.exists() || target.sha256() != llamaSha256) {
            logger.lifecycle("fetching llama.cpp $llamaVersion")
            target.outputStream().use { sink ->
                URI(llamaUrl).toURL().openStream().use { source -> source.copyTo(sink) }
            }
        }

        val actual = target.sha256()
        check(actual == llamaSha256) {
            "llama.cpp $llamaVersion checksum mismatch.\n  expected $llamaSha256\n  got      $actual"
        }

        val root = llamaRoot.get().asFile
        if (!root.resolve("CMakeLists.txt").exists()) {
            logger.lifecycle("extracting llama.cpp $llamaVersion")
            // The NDK ships no tar; Windows 10+ and every Linux have one on PATH.
            providers.exec {
                commandLine("tar", "-xzf", target.absolutePath, "-C", into.get().asFile.absolutePath)
            }.result.get().assertNormalExitValue()
        }
        check(root.resolve("CMakeLists.txt").exists()) {
            "llama.cpp extracted but ${root.name}/CMakeLists.txt is missing"
        }
        marker.get().asFile.writeText("$llamaUrl\n$llamaSha256\n")
    }
}

fun File.sha256(): String = MessageDigest.getInstance("SHA-256").let { digest ->
    inputStream().buffered().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

android {
    namespace = "com.mangareader.translate"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The target device is arm64, and so is every other ABI this project ships.
            abiFilters += providers.gradleProperty("tutorialEmulatorAbi").orNull ?: "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DLLAMA_SOURCE_DIR=${llamaRoot.get().asFile.absolutePath}"
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += "-O3"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// CMake cannot configure until the source it is told to add_subdirectory() exists.
tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildJsonTask>().configureEach {
    dependsOn(fetchLlama)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // android.jar's org.json is a stub that throws; the real one so the parity fixture
    // can be read on the JVM. Same reason as :ml:analysis.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
