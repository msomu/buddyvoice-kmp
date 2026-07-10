plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        outputModuleName = "webApp"
        browser {
            commonWebpackConfig {
                outputFileName = "webApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(projects.sharedUI)
            implementation(projects.voiceagentCore)
            implementation(projects.voiceagentAudio)
            implementation(projects.voiceagentProviderGrok)
            implementation(projects.voiceagentProviderOpenai)
            implementation(projects.voiceagentProviderElevenlabs)

            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
