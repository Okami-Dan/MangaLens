import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Stage 6's assets, staged into the APK from where the `/tools` scripts leave them.
 *
 * Section 6 lets JMdict ship "as an asset (or downloaded on first run alongside the OCR
 * model)". It ships as an asset -- see `DictionaryStore` for why -- but 53 MB does not
 * belong checked in under `app/src/main/assets`, so it is copied at build time from
 * `var/assets/` instead, the same directory `harness/pipeline/runner.py` reads it from.
 *
 * The sidecar `.meta` exists because `AssetManager` compresses a `.sqlite` entry, and a
 * compressed entry cannot report its uncompressed length without being read in full.
 * `DictionaryStore` needs that length on every launch to decide whether the unpacked
 * copy is complete, so the build records it here instead.
 */
val repoRoot: File = rootProject.projectDir.parentFile
val jmdictSource: File = File(repoRoot, "var/assets/jmdict-mobile.sqlite")
val sfxSource: File = File(repoRoot, "tools/sfx_dict.json")
val grammarSource: File = File(repoRoot, "tools/grammar_rules.json")
val grammarLexiconSource: File = File(repoRoot, "tools/grammar_lexicon.json")
val dictionaryAssets: Provider<Directory> = layout.buildDirectory.dir("generated/assets/dictionary")

val verifyGrammarCoverage = tasks.register<Exec>("verifyGrammarCoverage") {
    description = "Checks offline grammar inventory reviews, references and generated asset freshness."
    workingDir(repoRoot)
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/build_grammar_coverage.py")
}

// Recording/debugging on an x86_64 AVD must not depend on ARM instruction translation.
// Release variants are disabled when this explicit, debug-only override is supplied.
val tutorialEmulatorAbi = providers.gradleProperty("tutorialEmulatorAbi").orNull
require(tutorialEmulatorAbi == null || tutorialEmulatorAbi == "x86_64")

val stageDictionaryAssets = tasks.register("stageDictionaryAssets") {
    dependsOn(verifyGrammarCoverage)
    description = "Copies JMdict, the SFX glossary and the grammar rules into the APK's assets."
    inputs.file(sfxSource)
    inputs.file(grammarSource)
    inputs.file(grammarLexiconSource)
    inputs.files(files(jmdictSource))
    outputs.dir(dictionaryAssets)
    doLast {
        val out = dictionaryAssets.get().asFile
        out.mkdirs()
        require(jmdictSource.isFile) {
            "$jmdictSource is missing. Build it with:\n" +
                "    python tools/setup_assets.py"
        }
        require(sfxSource.isFile) { "$sfxSource is missing" }
        require(grammarSource.isFile) { "$grammarSource is missing" }

        val target = File(out, "jmdict.sqlite")
        // This task is input-tracked; equal file lengths do not establish asset identity.
        jmdictSource.copyTo(target, overwrite = true)
        sfxSource.copyTo(File(out, "sfx_dict.json"), overwrite = true)
        grammarSource.copyTo(File(out, "grammar_rules.json"), overwrite = true)
        grammarLexiconSource.copyTo(File(out, "grammar_lexicon.json"), overwrite = true)

        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        File(out, "jmdict.meta").writeText(
            "bytes=${target.length()}\n" +
                "sha256=${digest.digest().joinToString("") { "%02x".format(it) }}\n" +
                "source=var/assets/jmdict-mobile.sqlite\n",
        )
    }
}

/**
 * The INT8 OCR pair, staged the same way `jmdict.sqlite` is above.
 *
 * Plan section 4 stage 5 says not to ship the model in the APK -- that was measured
 * against the fp32 checkpoint (~444 MB, "bloats the download unacceptably"). The INT8
 * export these two files are is 111 MB combined, smaller than the dictionary already
 * bundled above, and unlike `detector.onnx` and `vocab.txt` they have no public URL at
 * all: a fresh install used to leave them on `ModelStore.Source.NONE` with nothing but
 * a file picker and nothing local to point it at. `detector.onnx` (161 MB) stays
 * downloaded from Hugging Face, same as before -- duplicating it here would only grow
 * the APK for a file that already fetches cleanly.
 *
 * No `.meta` sidecar, unlike `jmdict.sqlite`: `ModelStore.ARTIFACTS` already pins each
 * file's exact size and SHA-256, and `ModelStore.install`'s existing verify-after-copy
 * already checks a bundled artifact the same way it checks a downloaded or staged one.
 * The target path matches `ModelStore.Artifact.relativePath` exactly, so the asset is
 * read back at runtime by that one string with nothing to keep in step by hand.
 */
val ocrEncoderSource: File = File(repoRoot, "var/models/manga-ocr-onnx-int8/encoder.onnx")
val ocrDecoderSource: File = File(repoRoot, "var/models/manga-ocr-onnx-int8/decoder.onnx")
val ocrModelAssets: Provider<Directory> = layout.buildDirectory.dir("generated/assets/ocr-models")

val stageOcrAssets = tasks.register("stageOcrAssets") {
    description = "Copies the INT8 OCR encoder/decoder into the APK's assets."
    inputs.files(files(ocrEncoderSource, ocrDecoderSource))
    outputs.dir(ocrModelAssets)
    doLast {
        val out = File(ocrModelAssets.get().asFile, "manga-ocr")
        out.mkdirs()
        require(ocrEncoderSource.isFile) {
            "$ocrEncoderSource is missing. Build it with:\n" +
                "    python tools/setup_assets.py"
        }
        require(ocrDecoderSource.isFile) { "$ocrDecoderSource is missing -- see above" }

        val encoderTarget = File(out, "encoder.onnx")
        if (encoderTarget.length() != ocrEncoderSource.length()) {
            ocrEncoderSource.copyTo(encoderTarget, overwrite = true)
        }
        val decoderTarget = File(out, "decoder.onnx")
        if (decoderTarget.length() != ocrDecoderSource.length()) {
            ocrDecoderSource.copyTo(decoderTarget, overwrite = true)
        }
    }
}

android {
    namespace = "com.mangareader"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mangareader"
        // Section 7 leaves minSdk [OPEN] at 26, to be raised "if ONNX Runtime or the
        // scanner require it". Neither does: ONNX Runtime Android and
        // play-services-mlkit-document-scanner are both satisfied at 26.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.4-m4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The target device is a Galaxy A73 5G (docs/on-device-llm.md), which is
            // arm64. Every other ABI is dead weight in a download that already has to
            // carry ONNX Runtime.
            abiFilters += tutorialEmulatorAbi ?: "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // kuromoji-core and kuromoji-ipadic each carry their own copy of the
            // project's licence and contributor files under META-INF.
            excludes += setOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    // encoder.onnx/decoder.onnx are already-quantized weight blobs -- close to
    // incompressible -- so DEFLATE spends real build time on them for no size benefit.
    // ONNX Runtime always reads the unpacked copy `ModelStore` writes to app-private
    // storage, never the asset directly, so this is purely a packaging cost to avoid,
    // not a correctness requirement either way.
    androidResources {
        noCompress += "onnx"
    }

    sourceSets["main"].assets.srcDir(File(repoRoot, "LICENSES"))
    sourceSets["main"].assets.srcDir(dictionaryAssets)
    sourceSets["main"].assets.srcDir(ocrModelAssets)

}

// Asset merge and lint's models both consume these generated assets.
androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar(Char::uppercase)
    project.tasks.matching {
        it.name == "merge${variantName}Assets" ||
            (it.name.contains("lint", ignoreCase = true) &&
                it.name.contains(variantName, ignoreCase = true))
    }.configureEach {
        dependsOn(stageDictionaryAssets)
        dependsOn(stageOcrAssets)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:capture"))
    implementation(project(":core:database"))
    implementation(project(":feature:pipeline"))
    implementation(project(":ml:detection"))
    implementation(project(":ml:ocr"))
    implementation(project(":ml:analysis"))
    implementation(project(":ml:translate"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(libs.opencv)
    testImplementation(libs.junit)
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
    if (tutorialEmulatorAbi != null) it.enable = false
}
