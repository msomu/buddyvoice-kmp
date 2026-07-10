import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

mavenPublishing {
    // group + version come from GROUP / VERSION_NAME in gradle.properties.
    coordinates(artifactId = "buddyvoice-transport")

    // Signing only happens when a key is provided (the release workflow); local
    // builds and publishToMavenLocal skip it.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    pom {
        name.set("BuddyVoice Transport")
        description.set("Shared Ktor WebSocket/HTTP transport for WebSocket-based voice agent providers")
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
        namespace = "com.msomu.buddyvoice.voiceagent.transport"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            // `api`, not `implementation`: RealtimeClient's public constructors take
            // HttpClientEngine(Factory), and Kotlin/JS klib linkage prunes any
            // declaration whose signature references symbols missing from the
            // consumer's compile classpath (see docs/adr/0001).
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}
