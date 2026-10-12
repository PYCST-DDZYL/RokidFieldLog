plugins { kotlin("jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies { testImplementation("junit:junit:4.13.2") }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

// Test text stays UTF-8; Windows worker argument-file encoding is configured
// separately in the project-private Gradle user properties by our script.
tasks.test { systemProperty("file.encoding", "UTF-8") }
