pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Espresso and Media3 transitive artifacts use Central's mirror where the
        // main host is unavailable. Keep this scoped to those dependencies.
        exclusiveContent {
            forRepository { maven("https://maven-central.storage-download.googleapis.com/maven2") }
            filter {
                includeModule("com.squareup", "javawriter")
                includeGroup("org.hamcrest")
                includeGroup("org.sonatype.oss")
                includeModule("com.google.code.findbugs", "jsr305")
                includeGroup("com.google.guava")
                includeGroup("com.google.errorprone")
                includeGroup("com.google.j2objc")
                includeModule("org.checkerframework", "checker-qual")
            }
        }
    }
}

rootProject.name = "MangaReader"

// Plan section 7's module structure. Modules land as the milestones that need them
// do: :core:model and :app for M1's shell, then :core:capture, :ml:detection,
// :ml:ocr and :feature:pipeline for M2, then :ml:analysis for M3 and :ml:translate
// for M4. That is the full set section 7 names.
include(":app")
include(":core:model")
include(":core:capture")
include(":core:database")
include(":ml:detection")
include(":ml:ocr")
include(":ml:analysis")
include(":ml:translate")
include(":feature:pipeline")
