import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":pdfjvm"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // The JVM build of ONNX Runtime, exposing the same ai.onnxruntime API :core compiles against.
    implementation(libs.onnxruntime)
    // jump3r decodes MP3 here as well as encoding it; :core keeps it off its own API.
    implementation(libs.jump3r)

    // Used only to bind libespeak-ng when the user has installed it.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly(libs.slf4j.simple)
}

compose.desktop {
    application {
        mainClass = "app.soundbound.desktop.MainKt"

        // Compose Desktop runs on a stripped runtime image, so every module the app reaches for at
        // runtime has to be named. `jdk.crypto.ec` is the one people forget: without it TLS fails
        // when fetching the voice list, and only in the packaged build.
        jvmArgs += listOf("-Xmx2g", "-Dapple.awt.application.appearance=system")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Soundbound"
            packageVersion = rootProject.version.toString()
            description = "An offline reader that reads aloud"
            copyright = "Soundbound"
            vendor = "Soundbound"

            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )

            macOS {
                bundleID = "app.soundbound"
                dockName = "Soundbound"
                iconFile.set(project.file("src/main/resources/icon.icns"))
                // Ad-hoc signing only; a Developer ID is needed for distribution outside the Mac.
                signing { sign.set(false) }
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSHighResolutionCapable</key>
                        <true/>
                        <key>LSApplicationCategoryType</key>
                        <string>public.app-category.books</string>
                        <key>CFBundleDocumentTypes</key>
                        <array>
                          <dict>
                            <key>CFBundleTypeName</key>
                            <string>EPUB book</string>
                            <key>CFBundleTypeRole</key>
                            <string>Viewer</string>
                            <key>LSItemContentTypes</key>
                            <array><string>org.idpf.epub-container</string></array>
                          </dict>
                          <dict>
                            <key>CFBundleTypeName</key>
                            <string>PDF document</string>
                            <key>CFBundleTypeRole</key>
                            <string>Viewer</string>
                            <key>LSItemContentTypes</key>
                            <array><string>com.adobe.pdf</string></array>
                          </dict>
                        </array>
                    """.trimIndent()
                }
            }

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menu = true
                shortcut = true
                perUserInstall = true
                // A fixed UUID, so an upgrade replaces the previous install rather than adding a
                // second copy to Add or Remove Programs.
                upgradeUuid = "5B1C0E4A-8E2F-4C4B-9C3A-7D2E6F8A1B45"
            }

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                packageName = "soundbound"
                debMaintainer = "soundbound@localhost"
                appCategory = "Office"
            }
        }

        buildTypes.release.proguard {
            // The interface is reached only through Compose's generated code, and ONNX Runtime
            // through JNI, so obfuscation buys nothing and risks a lot.
            isEnabled.set(false)
        }
    }
}
