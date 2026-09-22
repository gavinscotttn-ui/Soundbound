import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    // Resolved but not applied here: whether the Android target exists is decided below, so
    // that the shared interface and the desktop app can be built without an Android SDK.
    id("com.android.library") apply false
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

if (withAndroid) {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "app.soundbound.ui"
        compileSdk = 36

        defaultConfig {
            minSdk = 26
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
