import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase (Crashlytics) is wired only when a google-services.json is present, so
// CI and contributors without a Firebase project still build. Drop your own
// app/google-services.json (see google-services.json.example) to activate it.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")

    // The debug variant uses the .debug applicationId (see below), but the
    // google-services plugin matches the JSON's package_name against the
    // applicationId and fails on a mismatch. Mirror the config into src/debug with
    // the .debug package so the suffixed debug build still resolves a Firebase
    // client (crashes report to the same Firebase project). Generated file is
    // gitignored.
    val baseGoogleServices = file("google-services.json")
    val debugGoogleServices = file("src/debug/google-services.json")
    val patched = baseGoogleServices.readText().replace(
        Regex("(\"package_name\"\\s*:\\s*\")sg\\.act\\.domain(\")"),
        "$1sg.act.domain.debug$2",
    )
    debugGoogleServices.parentFile.mkdirs()
    if (!debugGoogleServices.exists() || debugGoogleServices.readText() != patched) {
        debugGoogleServices.writeText(patched)
    }
}

// Release signing reads from keystore.properties (local, gitignored) or env vars
// (CI secrets). Absent both, release builds unsigned and CI/debug are unaffected.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "sg.act.domain"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "sg.act.domain"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 108
        versionName = "1.08"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "ORACLE_KEYSTORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "ORACLE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ORACLE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ORACLE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Distinct applicationId so a debug build installs alongside a release
            // build (separate app + separate sandboxed data).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            // Sign debug builds with your keystore too (when configured via
            // keystore.properties or env), so debug installs have a stable
            // signature — useful for Firebase SHA matching and installing over a
            // release build. Falls back to the default debug key when absent.
            signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use the release signing config only when a keystore was provided.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }

            // Upload native (llama.cpp) debug symbols so Crashlytics can symbolicate
            // NDK crashes. Guarded by firebaseConfigured for the same reason the
            // plugin is — builds without google-services.json must still work.
            if (firebaseConfigured) {
                configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                    nativeSymbolUploadEnabled = true
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":llama"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Markdown rendering for chat replies (pure Compose) + code syntax highlighting.
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.highlights)

    // Crashlytics for opt-in crash reporting (JVM + native). Inert unless a
    // google-services.json is present and the user opts in at runtime.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.crashlytics.ndk)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

// --- Signing fingerprints ---------------------------------------------------
// Prints the SHA-1 / SHA-256 of the signing certificate (handy for registering
// the app with Firebase). Runs automatically after assembleDebug/assembleRelease,
// and on demand via `./gradlew :app:printSigningSha`.

fun loadKeystore(file: File, password: String): KeyStore {
    val pass = password.toCharArray()
    for (type in listOf("PKCS12", "JKS")) {
        try {
            return KeyStore.getInstance(type).apply { file.inputStream().use { load(it, pass) } }
        } catch (_: Exception) {
            // try the next keystore type
        }
    }
    throw GradleException("Could not load keystore: ${file.absolutePath}")
}

fun fingerprint(cert: Certificate, algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(cert.encoded)
        .joinToString(":") { "%02X".format(it) }

fun printSha(label: String, file: File, storePassword: String, alias: String) {
    if (!file.exists()) return
    runCatching {
        val cert = loadKeystore(file, storePassword).getCertificate(alias)
        if (cert == null) {
            println("  [signing] $label: alias '$alias' not found")
        } else {
            println("  [signing] $label (alias=$alias)")
            println("    SHA-1:   ${fingerprint(cert, "SHA-1")}")
            println("    SHA-256: ${fingerprint(cert, "SHA-256")}")
        }
    }.onFailure { println("  [signing] $label: could not read (${it.message})") }
}

tasks.register("printSigningSha") {
    group = "verification"
    description = "Prints SHA-1 / SHA-256 fingerprints of the signing certificate."
    doLast {
        val storePath = signingValue("storeFile", "ORACLE_KEYSTORE_FILE")
        if (!storePath.isNullOrBlank()) {
            printSha(
                "configured keystore",
                file(storePath),
                signingValue("storePassword", "ORACLE_KEYSTORE_PASSWORD").orEmpty(),
                signingValue("keyAlias", "ORACLE_KEY_ALIAS").orEmpty(),
            )
        } else {
            printSha(
                "default debug keystore",
                File(System.getProperty("user.home"), ".android/debug.keystore"),
                "android",
                "androiddebugkey",
            )
        }
    }
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    finalizedBy("printSigningSha")
}
