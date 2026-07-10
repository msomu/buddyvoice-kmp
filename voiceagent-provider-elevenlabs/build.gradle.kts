import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.msomu"
version = "0.1.0-SNAPSHOT"

mavenPublishing {
    coordinates("io.github.msomu", "buddyvoice-provider-elevenlabs", version.toString())
    pom {
        name.set("BuddyVoice ElevenLabs Provider")
        description.set("ElevenLabs Agents WebSocket provider for BuddyVoice")
        url.set("https://github.com/msomu/buddyvoice-kmp")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
            }
        }
        developers {
            developer {
                id.set("msomu")
                name.set("Somasundaram Mahesh")
            }
        }
        scm {
            url.set("https://github.com/msomu/buddyvoice-kmp")
        }
    }
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    js {
        browser()
    }

    androidLibrary {
        namespace = "com.msomu.buddyvoice.provider.elevenlabs"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.voiceagentCore)
            // `api`, not `implementation`: GrokVoiceAgentProvider's public constructor
            // takes a RealtimeClient, which Kotlin/JS consumers must be able to
            // resolve at compile time (see docs/adr/0001).
            api(projects.voiceagentTransport)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
