plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Section 7's `:feature:pipeline` -- stage orchestration. It exists as of M2, when the
// chain became a chain: capture hands over a rectified page, this runs detect -> order
// -> OCR against it and persists the result. M3 adds stage 6 to the end of that chain
// and `DictionaryStore`, which unpacks the assets it needs. M4 adds stage 7 alongside it
// as `PageTranslator` -- alongside rather than inside, because translation is asked for
// rather than run on open.

android {
    namespace = "com.mangareader.pipeline"
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
    // The isolated test APK bundles both Kuromoji JARs, just like the app.
    packaging.resources.excludes += setOf(
        "META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md",
        "META-INF/NOTICE.md", "META-INF/DEPENDENCIES",
    )
}

dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
    implementation(project(":core:capture"))
    implementation(project(":ml:detection"))
    implementation(project(":ml:ocr"))
    api(project(":ml:analysis"))
    api(project(":ml:translate"))
    implementation(libs.opencv)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit)
}
