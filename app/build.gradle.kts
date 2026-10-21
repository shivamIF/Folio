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
        // The build to judge smoothness with: the debug build's code, but not debuggable, so ART
        // compiles it fully and Compose skips its debug-only bookkeeping. Signed with the debug
        // key, so it installs over a debug build (adb install -r) and keeps the notebooks.
        // Not debuggable means no `run-as`: back up and run the E2E scripts with a debug build.
        create("perf") {
            initWith(getByName("debug"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
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
    implementation(libs.graphics.core)
    // Google ML Kit, on-device: document scanner (Insert) and handwriting recognition (S Pen to text).
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.digital.ink)

    // Brush Lab (src/debug) compares Folio's engine with AndroidX Ink. Never in release builds.
    debugImplementation(libs.ink.brush)
    debugImplementation(libs.ink.strokes)
    debugImplementation(libs.ink.rendering)

    testImplementation(libs.kotlin.test)
}
