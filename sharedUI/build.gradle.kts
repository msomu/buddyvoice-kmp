import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    js {
        browser()
    }

    // iOS consumes the sample UI as a single framework. sharedUI's iosMain also
    // hosts the app-layer wiring (IosVoiceAgentController + MainViewController)
    // because the Swift shell cannot host Kotlin the way androidApp does — see
    // docs/architecture.md.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedUI"
            isStatic = true
        }
    }

    androidLibrary {
       namespace = "com.msomu.buddyvoice.sharedUI"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
        }
        commonMain.dependencies {
            api(projects.sharedLogic)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // App-layer wiring for the iOS sample only; commonMain stays free of
        // voiceagent dependencies (the UI seam).
        iosMain.dependencies {
            implementation(projects.voiceagentCore)
            implementation(projects.voiceagentAudio)
            implementation(projects.voiceagentProviderGrok)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}