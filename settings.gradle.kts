rootProject.name = "Soundbound"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
    }
}

// The pure-Kotlin engine room. Builds on any JDK with no Android SDK present.
include(":core")

// Apache PDFBox binding for the desktop. Kept out of :core so that no java.awt reference
// ever reaches the Android build, where the PDFBox-Android port is used instead.
include(":pdfjvm")

// Everything below needs the Android SDK and/or Google's Maven repository.
// Set `soundbound.withUi=false` in gradle.properties (or pass -Psoundbound.withUi=false)
// to work on :core alone on a machine without the Android SDK.
val withUi = (providers.gradleProperty("soundbound.withUi").orNull ?: "true").toBoolean()
if (withUi) {
    include(":ui")
    include(":desktopApp")
}

val withAndroid = (providers.gradleProperty("soundbound.withAndroid").orNull ?: "true").toBoolean()
if (withUi && withAndroid) {
    include(":androidApp")
}
