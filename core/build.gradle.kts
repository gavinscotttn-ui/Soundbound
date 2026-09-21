plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.RequiresOptIn")
    }
}

java {
    // Android-friendly bytecode level, and no toolchain declaration on purpose: the project
    // builds on any JDK from 17 upwards without needing to download a second one.
    // :core also avoids java.awt and java.nio.file, so the identical jar runs on the desktop
    // JVM and on ART (minSdk 26).
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okio)
    implementation(libs.jsoup)
    api(libs.okhttp)

    // ONNX Runtime is `compileOnly` here: the desktop app pulls in the JVM build and the
    // Android app pulls in `onnxruntime-android`. Both expose the identical `ai.onnxruntime`
    // Java API, so :core compiles once against the contract and runs on either.
    compileOnly(libs.onnxruntime)

    testImplementation(libs.onnxruntime)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
