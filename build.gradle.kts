plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "de.espend.intellij-agent-cli"
version = providers.gradleProperty("pluginVersion").get()
val javaVersion = providers.gradleProperty("javaVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        val version = providers.gradleProperty("platformVersion")
        val type = providers.gradleProperty("platformType")
        create(type, version) {
            useInstaller.set(true)
            useCache.set(true)
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }

    // HTTP Server
    implementation("io.javalin:javalin:7.1.0")

    // Kotlin scripting for code execution
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(javaVersion))
        }
    }
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}

kotlin {
    jvmToolchain(javaVersion.toInt())
}
