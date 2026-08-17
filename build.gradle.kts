import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.xclusivecyborg"
version = "0.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")

    intellijPlatform {
        // Compile against IntelliJ IDEA Community — the plugin only uses
        // com.intellij.modules.platform APIs, so this covers Android Studio too.
        intellijIdeaCommunity("2024.1")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"              // 2024.1 — matches the platform we build against
            untilBuild = provider { null }  // open-ended — supports all future versions
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Run the plugin inside the locally installed Android Studio:
//   ./gradlew runAndroidStudio
// Override the location with -PandroidStudioPath=/path/to/Android Studio.app
val runAndroidStudio by intellijPlatformTesting.runIde.registering {
    localPath.set(
        file(providers.gradleProperty("androidStudioPath").getOrElse("/Applications/Android Studio.app"))
    )
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // Stay within the Kotlin stdlib bundled by the target IDEs — the plugin
        // ships no stdlib of its own (see kotlin.stdlib.default.dependency).
        apiVersion = KotlinVersion.KOTLIN_1_9
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}
