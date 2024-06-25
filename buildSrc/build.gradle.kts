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
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-allopen:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-noarg:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-serialization:1.9.22")
    implementation("org.kordamp.gradle:jandex-gradle-plugin:0.13.2")
    implementation("io.quarkus:gradle-application-plugin:3.11.1")
}
