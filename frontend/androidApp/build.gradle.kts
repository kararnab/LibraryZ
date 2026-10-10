import java.net.URI

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin); no kotlin-android plugin.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.libraryz"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.libraryz"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = providers.gradleProperty("libraryz.version").get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.compose.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
}

// Verified App Links for the emailed https links. Set libraryz.appLinkUrl to
// the deployment's LIBRARYZ_PUBLIC_URL (https) to claim {url}/reset-password
// and {url}/verify-email. Android only trusts the claim if that host serves
// /.well-known/assetlinks.json naming this app's signing key; :composeApp
// puts one in the web bundle when libraryz.androidCertSha256 is set (see
// "Opening emailed links in the apps" in the README). Unset: libraryz://
// links only (the manifest's own filter).
abstract class AppLinksManifest : DefaultTask() {
    @get:Input abstract val publicUrl: Property<String>
    @get:OutputFile abstract val manifest: RegularFileProperty

    @TaskAction
    fun write() {
        val url = URI(publicUrl.get().trim().trimEnd('/'))
        require(url.scheme == "https" && !url.host.isNullOrEmpty() && url.rawQuery == null && url.rawFragment == null) {
            "libraryz.appLinkUrl must be an https URL with no query or fragment, got ${publicUrl.get()}"
        }
        val prefix = url.rawPath.orEmpty().trimEnd('/')
        val port = if (url.port != -1) "\n                <data android:port=\"${url.port}\" />" else ""
        manifest.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from libraryz.appLinkUrl (androidApp/build.gradle.kts). -->
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            |    <application>
            |        <activity android:name="com.libraryz.MainActivity">
            |            <intent-filter android:autoVerify="true">
            |                <action android:name="android.intent.action.VIEW" />
            |                <category android:name="android.intent.category.DEFAULT" />
            |                <category android:name="android.intent.category.BROWSABLE" />
            |                <data android:scheme="https" />
            |                <data android:host="${url.host}" />$port
            |                <data android:path="$prefix/reset-password" />
            |                <data android:path="$prefix/verify-email" />
            |            </intent-filter>
            |        </activity>
            |    </application>
            |</manifest>
            |""".trimMargin(),
        )
    }
}

val appLinkUrl = providers.gradleProperty("libraryz.appLinkUrl").orNull?.takeIf { it.isNotBlank() }
if (appLinkUrl != null) {
    androidComponents {
        onVariants { variant ->
            val task = tasks.register<AppLinksManifest>("${variant.name}AppLinksManifest") {
                publicUrl.set(appLinkUrl)
            }
            variant.sources.manifests?.addGeneratedManifestFile(task, AppLinksManifest::manifest)
        }
    }
}
