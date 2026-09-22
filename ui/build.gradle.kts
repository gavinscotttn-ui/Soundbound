import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP is not declared here. It is on the root project's buildscript classpath when an Android
// target is wanted — see the note in the root build file for why it has to be there — and this
// module simply applies it.
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
// compile when AGP is absent from the classpath entirely — and a reference to LibraryExtension
// anywhere in it would stop that. An applied script is only compiled if it is actually applied.
if (withAndroid) {
    apply(from = "android.gradle.kts")
}
