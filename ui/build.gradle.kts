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

// The Android library extension is configured by name rather than through LibraryExtension.
//
// This file has to compile on a machine where AGP is absent from the class path entirely, and a
// reference to an AGP type anywhere in it would stop that. Moving the block to a script applied
// only when wanted does not help: a script applied with `apply(from = ...)` is compiled against
// its own class path, not the one the plugins here provide, so the AGP types are out of reach
// there too. Configuring the extension dynamically compiles either way, at the cost of these
// names being checked when the build runs rather than when the script compiles — which the
// Android CI job does on every push.
if (withAndroid) {
    extensions.getByName("android").withGroovyBuilder {
        setProperty("namespace", "app.soundbound.ui")
        setProperty("compileSdk", 37)

        "defaultConfig" {
            setProperty("minSdk", 26)
        }

        "compileOptions" {
            setProperty("sourceCompatibility", JavaVersion.VERSION_17)
            setProperty("targetCompatibility", JavaVersion.VERSION_17)
        }
    }
}
