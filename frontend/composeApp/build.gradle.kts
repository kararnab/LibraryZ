import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    // Shared code's Android target. The installable app (MainActivity,
    // manifest, res) lives in :androidApp — AGP 9 no longer allows
    // com.android.application in a KMP module.
    android {
        namespace = "com.libraryz.shared"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.auth)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.cio)
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.pdfbox)
        }
        wasmJsMain {
            // @JsFun / JsAny interop is still flagged experimental in Kotlin 2.4.
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(libs.kotlinx.browser)
                implementation(libs.ktor.client.js)
            }
        }
        // Kotlin >= 2.4 compiles iOS klibs on Linux too, so iosMain always
        // exists; only linking the framework needs a Mac.
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// BuildInfo.AppVersion for common code (Settings › About), generated from
// libraryz.version in gradle.properties so it can't drift from the
// Android versionName.
val generateBuildInfo by tasks.registering {
    val version = providers.gradleProperty("libraryz.version")
    val outDir = layout.buildDirectory.dir("generated/buildinfo/commonMain/kotlin")
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/libraryz/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.libraryz\n\n" +
                "/** Generated from libraryz.version in gradle.properties. */\n" +
                "const val AppVersion = \"${version.get()}\"\n",
        )
    }
}
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateBuildInfo)

// /.well-known/assetlinks.json for the web bundle, which lets Android open
// the emailed https links in the app (androidApp's libraryz.appLinkUrl).
// libraryz.androidCertSha256: the SHA-256 fingerprint(s) of the app's
// signing certificate, AA:BB:…, comma-separated. Unset: no file.
val generateAssetLinks by tasks.registering {
    val fingerprints = providers.gradleProperty("libraryz.androidCertSha256").orElse("")
    val outDir = layout.buildDirectory.dir("generated/assetlinks/wasmJsMain/resources")
    inputs.property("fingerprints", fingerprints)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.deleteRecursively()
        val prints = fingerprints.get().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (prints.isEmpty()) return@doLast
        prints.forEach {
            require(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(it)) {
                "libraryz.androidCertSha256: expected a SHA-256 fingerprint like AA:BB:…, got $it"
            }
        }
        val file = dir.resolve(".well-known/assetlinks.json")
        file.parentFile.mkdirs()
        file.writeText(
            """[{"relation":["delegate_permission/common.handle_all_urls"],""" +
                """"target":{"namespace":"android_app","package_name":"com.libraryz",""" +
                """"sha256_cert_fingerprints":[${prints.joinToString(",") { "\"$it\"" }}]}}]""" + "\n",
        )
    }
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(generateAssetLinks)

compose.desktop {
    application {
        mainClass = "com.libraryz.MainKt"
        // Lets Main.kt name the X11 window "LibraryZ" (WM_CLASS) instead of
        // "com-libraryz-MainKt". The package only exists in Linux JDKs, so
        // other hosts would warn about it at every launch.
        if (System.getProperty("os.name").startsWith("Linux")) {
            jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "LibraryZ"
            // Compose Desktop's installer toolchain (jpackage) requires
            // MAJOR >= 1, so "0.x.y" is rejected even though SemVer allows it.
            packageVersion = "1.0.0"
            // libraryz:// links (the web app's "Open in the LibraryZ app").
            // macOS reads this at install; Linux and Windows register the
            // scheme when the installed app first runs (DesktopLinks.kt).
            macOS {
                bundleID = "com.libraryz"
                infoPlist {
                    extraKeysRawXml = """
                        |  <key>CFBundleURLTypes</key>
                        |  <array>
                        |    <dict>
                        |      <key>CFBundleURLName</key>
                        |      <string>LibraryZ link</string>
                        |      <key>CFBundleURLSchemes</key>
                        |      <array><string>libraryz</string></array>
                        |    </dict>
                        |  </array>
                    """.trimMargin()
                }
            }
        }
    }
}
