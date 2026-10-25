plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Room's schema JSON, exported from M4 on. It is what a migration is written against:
// the DDL Room expects has to match the DDL a migration produces exactly, and guessing at
// it is how a migration passes review and then throws on a user's phone.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "com.mangareader.database"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MigrationTestHelper reads the exported schemas at runtime, off the device's
    // asset path rather than the build directory.
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:model"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
