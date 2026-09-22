// Applied from build.gradle.kts, and only when an Android target was asked for. See the note
// there for why this is not inlined.
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
