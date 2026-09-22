// The Android Gradle Plugin is loaded here, onto the root project's buildscript classpath,
// rather than declared in the `plugins` block below.
//
// It has to be at the root, and not in a module. The Kotlin Gradle Plugin comes from the
// `plugins` block, so it is loaded by the root project's class loader; a module that declares
// AGP for itself gets it in a *child* class loader, which the Kotlin plugin cannot see. Kotlin
// then fails applying `org.jetbrains.kotlin.android` — its AgpWithBuiltInKotlinAppliedCheck
// reaches for com.android.build.gradle.BaseExtension and does not find it. Loading AGP here
// puts the two side by side.
//
// It has to be a `buildscript` block, and not the `plugins` block, because only this form can
// be made conditional. AGP declared in `plugins` — even with `apply false` — is resolved on
// every invocation, and that would stop :core building on a machine with no access to Google's
// Maven repository.
buildscript {
    val withUi = (providers.gradleProperty("soundbound.withUi").orNull ?: "true").toBoolean()
    val withAndroid = (providers.gradleProperty("soundbound.withAndroid").orNull ?: "true").toBoolean()
    if (withUi && withAndroid) {
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
        dependencies {
            classpath(
                "com.android.tools.build:gradle:" +
                    providers.gradleProperty("soundbound.agpVersion").get(),
            )
        }
    }
}

// Every other plugin is declared here once, with its version, so that subprojects can apply
// it by bare id. That is what keeps the Kotlin plugin from being loaded twice in one build.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

allprojects {
    group = "app.soundbound"
    version = "1.0.0"
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
