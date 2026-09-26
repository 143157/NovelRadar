buildscript {
    dependencies {
        // AGP 9's built-in Kotlin compiler must match QuickJS 1.0.14 metadata.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}
