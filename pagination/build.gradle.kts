import org.jetbrains.compose.reload.gradle.ComposeHotRun

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    jvmToolchain(
        libs.versions.jvmToolchain
            .get()
            .toInt(),
    )

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.seforimlibrary.core)
            implementation(libs.seforimlibrary.dao)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            // AndroidX Paging 3 (common)
            implementation(libs.androidx.paging.common)
            implementation(project(":logger"))
        }

        jvmMain.dependencies {
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<ComposeHotRun>().configureEach {
    mainClass.set("MainKt")
}
