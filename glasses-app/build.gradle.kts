import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
}

val releaseFile = rootProject.file(".private/release-signing.properties")
val releaseProps = Properties().apply { if (releaseFile.isFile) releaseFile.inputStream().use { load(it) } }
fun signingValue(key: String, env: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: releaseProps.getProperty(key)
val releaseStore = signingValue("storeFile", "FIELDLOG_RELEASE_STORE_FILE")?.let { rootProject.file(it) }
val releaseStorePassword = signingValue("storePassword", "FIELDLOG_RELEASE_STORE_PASSWORD")
val releaseAlias = signingValue("keyAlias", "FIELDLOG_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "FIELDLOG_RELEASE_KEY_PASSWORD")
val verifyReleaseKey = tasks.register("verifyReleaseKey") {
    doLast {
        check(releaseStore?.isFile == true && !releaseStorePassword.isNullOrBlank() && !releaseAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
            "Persistent release signing required. Run scripts/Initialize-ReleaseSigning.ps1 or configure FIELDLOG_RELEASE_*; never use the debug key for release."
        }
        val pin = rootProject.file("release-certificate.sha256").readText().trim()
        val keyStore = KeyStore.getInstance(releaseStore!!, releaseStorePassword!!.toCharArray())
        val certificate = checkNotNull(keyStore.getCertificate(releaseAlias)) { "Release alias has no certificate" }
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        check(actual == pin) { "Release certificate changed. Restore the retained key instead of generating a new one." }
    }
}
tasks.configureEach { if (name == "preReleaseBuild") dependsOn(verifyReleaseKey) }

android {
    namespace = "org.rokidfieldlog.glasses"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.rokidfieldlog.glasses"
        minSdk = 31
        targetSdk = 36
        versionCode = 10
        versionName = "0.2.7-validation"
    }
    signingConfigs {
        create("persistentRelease") {
            storeFile = releaseStore
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("persistentRelease") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":fieldlog-core"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
}
