import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // AGP 9 would provide Kotlin itself and refuse this plugin, but android.builtInKotlin is
    // off — see gradle.properties — so that this module and the multiplatform :ui are both
    // built by the same Kotlin plugin.
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "app.soundbound.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.soundbound"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = rootProject.version.toString()
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // ONNX Runtime and okhttp both ship metadata that collides on merge.
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
        // Voice models are already compressed; zipping them again wastes build time and gains
        // nothing, and leaving them uncompressed lets ONNX Runtime map them straight from the APK.
        jniLibs.useLegacyPackaging = false
    }

    signingConfigs {
        // A debug key is generated automatically. A release build is signed only when the
        // keystore details are supplied, so CI can still produce an unsigned release APK.
        create("release") {
            val storeFilePath = providers.gradleProperty("soundbound.release.storeFile").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("soundbound.release.storePassword").orNull
                keyAlias = providers.gradleProperty("soundbound.release.keyAlias").orNull
                keyPassword = providers.gradleProperty("soundbound.release.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val hasKeystore = providers.gradleProperty("soundbound.release.storeFile").isPresent
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.splashscreen)
    implementation(libs.kotlinx.coroutines.android)

    // ONNX Runtime's Android build: the same ai.onnxruntime API :core compiles against.
    implementation(libs.onnxruntime.android)

    // PDFBox-Android, a port that avoids java.awt. :core stays free of both.
    implementation(libs.pdfbox.android)
}
