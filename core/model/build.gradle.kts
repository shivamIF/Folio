plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

// Same heap as a Galaxy Tab S6 Lite app (256 MB), so the dense-page codec test
// fails here if decoding ever needs more memory than a real device has.
tasks.test { maxHeapSize = "256m" }
