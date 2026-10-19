plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.folio.notes"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.folio.notes"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ink"))
    implementation(project(":core:storage"))
    implementation(project(":core:interop"))
    implementation(project(":core:sync"))
    // Google sign-in for Drive sync (free; only the private app-folder scope is requested).
    implementation(libs.play.services.auth)
    implementation(libs.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.motion.prediction)

    // Brush Lab (src/debug) compares Folio's engine with AndroidX Ink. Never in release builds.
    debugImplementation(libs.ink.brush)
    debugImplementation(libs.ink.strokes)
    debugImplementation(libs.ink.rendering)

    testImplementation(libs.kotlin.test)
}
