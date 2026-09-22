import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android Gradle Plugin is put on this module's buildscript classpath only when an Android
// target is actually wanted. A `plugins { id("com.android.library") apply false }` declaration
// would not do: Gradle resolves the artifact even when it is never applied, so
// `-Psoundbound.withAndroid=false` would still need Google's Maven repository to build the
// shared interface and the desktop app.
buildscript {
    if ((providers.gradleProperty("soundbound.withAndroid").orNull ?: "true").toBoolean()) {
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

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val withAndroid = (providers.gradleProperty("soundbound.withAndroid").orNull ?: "true").toBoolean()
if (withAndroid) {
    apply(plugin = "com.android.library")
}

kotlin {
    jvm()

    if (withAndroid) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }

        if (withAndroid) {
            getByName("androidMain").dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

// The Android library extension is configured from a separate script, because this file has to
// compile on a machine where AGP is absent from the classpath entirely — and a reference to
// `LibraryExtension` anywhere in it would stop that. An applied script is only compiled if it is
// actually applied.
if (withAndroid) {
    apply(from = "android.gradle.kts")
}
