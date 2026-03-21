plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "de.espend.intellij-agent-cli"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.3.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }

    // HTTP Server
    implementation("io.javalin:javalin:7.1.0")

    // Kotlin scripting for code execution
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    jvmToolchain(21)
}
