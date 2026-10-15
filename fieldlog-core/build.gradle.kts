plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0") {
        // Build-time Java annotations are not used by the Kotlin/Android runtime.
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

// Test text stays UTF-8; Windows worker argument-file encoding is configured
// separately in the project-private Gradle user properties by our script.
tasks.test {
    systemProperty("file.encoding", "UTF-8")
    // Explicit opt-in: publish only synthetic fixtures into the ignored preview directory.
    if (providers.gradleProperty("fieldlogDemo").orNull == "true") {
        systemProperty("fieldlog.demoOutput", rootProject.file(".local/offline-preview").absolutePath)
        outputs.upToDateWhen { false }
    }
}
