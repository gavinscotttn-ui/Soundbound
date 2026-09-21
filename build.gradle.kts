// The Android Gradle Plugin is deliberately absent from this block.
//
// Declaring AGP here — even with `apply false` — forces Gradle to resolve it on every
// invocation, which breaks `-Psoundbound.withAndroid=false` on a machine with no Android SDK
// and no access to Google's Maven repository. :androidApp declares it itself instead.
//
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
