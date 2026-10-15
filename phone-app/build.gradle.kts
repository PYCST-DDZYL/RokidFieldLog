plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.rokidfieldlog.phone"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.rokidfieldlog.phone"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-inspection-preview"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":fieldlog-core"))
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
}
