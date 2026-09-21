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

    // The Android Gradle Plugin's version is supplied here rather than in a root `plugins`
    // block. A root declaration — even `apply false` — is resolved on every invocation, which
    // would stop :core building on a machine with no Android SDK and no access to Google's
    // Maven repository. This way only the modules that actually ask for AGP pull it in.
    val agpVersion = "8.11.1"
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android")) {
                useModule("com.android.tools.build:gradle:" + agpVersion)
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
// The shared Compose Multiplatform interface and the two application shells.
//
//   -Psoundbound.withUi=false       build :core and :pdfjvm alone, on any JDK with no
//                                   Android SDK and no Google Maven access.
//   -Psoundbound.withAndroid=false  build the shared interface and the desktop app without
//                                   an Android SDK installed.
val withUi = (providers.gradleProperty("soundbound.withUi").orNull ?: "true").toBoolean()
val withAndroid = (providers.gradleProperty("soundbound.withAndroid").orNull ?: "true").toBoolean()

if (withUi) {
    include(":ui")
    include(":desktopApp")
    if (withAndroid) include(":androidApp")
}
