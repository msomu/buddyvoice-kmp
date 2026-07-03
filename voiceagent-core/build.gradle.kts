import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.msomu"
version = "0.1.0-SNAPSHOT"

mavenPublishing {
    coordinates("io.github.msomu", "buddyvoice-core", version.toString())
    pom {
        name.set("BuddyVoice Core")
        description.set("Provider-agnostic realtime voice agent interfaces for Kotlin Multiplatform")
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
        namespace = "com.msomu.buddyvoice.voiceagent.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
