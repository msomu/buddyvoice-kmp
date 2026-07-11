import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.sharedUI)
    implementation(projects.voiceagentCore)
    implementation(projects.voiceagentAudio)
    implementation(projects.voiceagentProviderGrok)
    implementation(projects.voiceagentProviderOpenai)
    implementation(projects.voiceagentProviderElevenlabs)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

// Proxy settings for the sample app live in the untracked local.properties —
// see server-proxy/README.md. Only placeholders may ever appear in the repo.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.msomu.buddyvoice"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.msomu.buddyvoice"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "BUDDYVOICE_PROXY_BASE_URL",
            "\"${localProperties.getProperty("buddyvoice.proxyBaseUrl", "")}\"",
        )
        buildConfigField(
            "String",
            "BUDDYVOICE_PROXY_KEY",
            "\"${localProperties.getProperty("buddyvoice.proxyKey", "")}\"",
        )
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}