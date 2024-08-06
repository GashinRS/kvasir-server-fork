/**
 * Gradle build file defining the 'buildSrc' project.
 * 'buildSrc' is a built-in Gradle construct that allows implementing common build logic.
 */

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.0.0")
    implementation("org.jetbrains.kotlin:kotlin-noarg:2.0.0")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.0.0")
    implementation("org.kordamp.gradle:jandex-gradle-plugin:0.13.2")
    implementation("io.quarkus:gradle-application-plugin:3.13.0")
}
