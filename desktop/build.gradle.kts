// Folio for macOS: a Compose Desktop app on the same portable core (model, ink, storage) as the Android app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose") version "1.9.3"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ink"))
    implementation(project(":core:storage"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
    // PDF page backgrounds (Apache 2.0).
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    testImplementation(libs.kotlin.test)
}

compose.desktop {
    application {
        mainClass = "app.folio.desktop.MainKt"
        // Trackpad pinch: macOS delivers it through an AWT package the JDK doesn't export by default.
        jvmArgs += listOf("--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED", "--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "Folio"
            packageVersion = "1.0.0"
            macOS { bundleID = "app.folio.notes.mac" }
        }
    }
}
