/**
 * AGP 9 compiles Kotlin itself ("built-in Kotlin") and ships a default Kotlin
 * Gradle plugin version. The Compose and serialization compiler plugins applied
 * in :app must run on the *same* KGP that AGP loaded — two KGP versions on one
 * classpath is the classic source of
 * "ClassNotFoundException: org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin".
 *
 * So the KGP version is pinned here, once, and `gradle/libs.versions.toml`
 * refers to `kotlin = "2.4.21"` for the matching compiler-plugin versions.
 */
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
