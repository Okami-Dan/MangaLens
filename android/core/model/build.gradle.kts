import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Section 7: "domain types, no Android deps". A plain Kotlin module, so the pipeline
// stages that consume it can be unit-tested on the JVM without an emulator.
//
// Compile to JVM 17 while allowing Gradle to use any supported installed JDK.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
