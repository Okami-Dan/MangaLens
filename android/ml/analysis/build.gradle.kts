plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Section 7's `:ml:analysis` -- stage 6. Kuromoji, furigana alignment, romaji, JMdict
// lookup and the SFX dictionary. No network, no ONNX, and no UI: everything here is a
// pure function of a string plus two bundled assets, which is why most of it is covered
// by JVM unit tests rather than instrumented ones.

android {
    namespace = "com.mangareader.analysis"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:model"))
    // Section 7: Kuromoji IPADIC -- pure Java, no JNI, same dictionary janome ships in
    // the harness, which is what keeps segmentation comparable between the two.
    api(libs.kuromoji.ipadic)

    testImplementation(libs.junit)
    // android.jar's org.json is a stub that throws; the real one so the SFX glossary
    // and the fixture can both be parsed in a JVM test rather than only on a device.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(":app:verifyGrammarCoverage")
}
