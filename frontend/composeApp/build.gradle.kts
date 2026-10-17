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
        // Off by default for KMP libraries; without it composeResources
        // (Literata, Figtree, licence texts) never reach the APK and Android
        // silently falls back to Roboto.
        androidResources { enable = true }
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
            implementation(libs.androidsvg)
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

// The server's library merge cases (internal/library/testdata) as a string
// constant, so every test target runs them without file IO
// (LibraryMergeCasesTest).
val generateMergeCases by tasks.registering {
    val cases = rootProject.layout.projectDirectory.file("../internal/library/testdata/merge_cases.json")
    val outDir = layout.buildDirectory.dir("generated/mergecases/commonTest/kotlin")
    inputs.file(cases)
    outputs.dir(outDir)
    doLast {
        val text = cases.asFile.readText()
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("\r", "").replace("\n", "\\n")
        val file = outDir.get().file("com/libraryz/data/MergeCases.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.libraryz.data\n\n" +
                "/** Generated from internal/library/testdata/merge_cases.json. */\n" +
                "internal const val MERGE_CASES_JSON = \"$text\"\n",
        )
    }
}
kotlin.sourceSets.getByName("commonTest").kotlin.srcDir(generateMergeCases)

// The usage events the server accepts (internal/telemetry/catalog.json) as
// a typed API: one function per event, an enum per enum prop. The app can
// only send what the catalog lists, and adding an event is an edit to that
// one reviewed file.
val generateTelemetryEvents by tasks.registering {
    val catalog = rootProject.layout.projectDirectory.file("../internal/telemetry/catalog.json")
    val outDir = layout.buildDirectory.dir("generated/telemetry/commonMain/kotlin")
    inputs.file(catalog)
    outputs.dir(outDir)
    doLast {
        fun camel(s: String, upper: Boolean) = s.split('_').mapIndexed { i, w ->
            if (i == 0 && !upper) w else w.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        fun doc(s: String) = s.replace("*/", "* /")
        @Suppress("UNCHECKED_CAST")
        val events = (groovy.json.JsonSlurper().parse(catalog.asFile) as Map<String, Any?>)["events"] as List<Map<String, Any?>>
        val fns = StringBuilder()
        val enums = StringBuilder()
        for (e in events) {
            val name = e["name"] as String
            @Suppress("UNCHECKED_CAST")
            val props = e["props"] as List<Map<String, Any?>>
            val params = props.joinToString(", ") { p ->
                val pn = p["name"] as String
                val type = when (p["type"]) {
                    "enum" -> camel(name, true) + camel(pn, true)
                    "bool" -> "Boolean"
                    "int" -> "Long"
                    else -> error("$name.$pn: unknown type ${p["type"]}")
                }
                "${camel(pn, false)}: $type"
            }
            val values = props.joinToString(", ") { p ->
                val pn = p["name"] as String
                val v = camel(pn, false)
                val wire = when (p["type"]) {
                    "enum" -> "JsonPrimitive($v.wire)"
                    "int" -> "JsonPrimitive($v.coerceIn(${p["min"]}L, ${p["max"]}L))"
                    else -> "JsonPrimitive($v)"
                }
                "\"$pn\" to $wire"
            }
            fns.append("    /** ${doc(e["description"] as String)} */\n")
            fns.append("    fun ${camel(name, false)}($params): TelemetryEvent =\n")
            fns.append("        TelemetryEvent(\"$name\", mapOf($values))\n\n")
            for (p in props.filter { it["type"] == "enum" }) {
                val pn = p["name"] as String
                @Suppress("UNCHECKED_CAST")
                val entries = (p["values"] as List<String>).joinToString(", ") { "${camel(it, true)}(\"$it\")" }
                enums.append("enum class ${camel(name, true)}${camel(pn, true)}(val wire: String) { $entries }\n\n")
            }
        }
        val file = outDir.get().file("com/libraryz/data/telemetry/Events.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.libraryz.data.telemetry\n\n" +
                "import kotlinx.serialization.json.JsonPrimitive\n\n" +
                "/** Generated from internal/telemetry/catalog.json: every usage event the server accepts. */\n" +
                "object Events {\n" + fns.toString().trimEnd() + "\n}\n\n" + enums.toString().trimEnd() + "\n",
        )
    }
}
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateTelemetryEvents)

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
            // The "Lz" mark, generated by branding/generate_icons.py.
            linux { iconFile.set(project.file("icons/libraryz.png")) }
            windows { iconFile.set(project.file("icons/libraryz.ico")) }
            // libraryz:// links (the web app's "Open in the LibraryZ app").
            // macOS reads this at install; Linux and Windows register the
            // scheme when the installed app first runs (DesktopLinks.kt).
            macOS {
                bundleID = "com.libraryz"
                iconFile.set(project.file("icons/libraryz.icns"))
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
