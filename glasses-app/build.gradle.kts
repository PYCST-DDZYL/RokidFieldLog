plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.rokidfieldlog.glasses"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.rokidfieldlog.glasses"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"
    }
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
