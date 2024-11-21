import io.quarkus.gradle.tasks.QuarkusBuildDependencies
import org.gradle.api.JavaVersion

/**
 * Plugin that defines a common library type project (that uses the Quarkus platform).
 * Add this plugin to a project to inherit preset configuration.
 */

plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    kotlin("plugin.noarg")
    kotlin("plugin.serialization")
    id("org.kordamp.gradle.jandex")
}

repositories {
    mavenCentral()
    maven {
        name = "ossrh-snapshots"
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
    maven {
        name = "clojars"
        url = uri("https://repo.clojars.org/")
    }
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-resteasy-reactive")
    implementation("io.quarkus:quarkus-smallrye-reactive-messaging-kafka")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    implementation("com.google.guava:guava:33.0.0-jre")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("io.quarkiverse.minio:quarkus-minio:3.7.7")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-jacoco")
}

group = "idlab.obelisk.hfs"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val multiProjectRootDir: String = rootProject.projectDir.absolutePath

tasks.withType<Test> {
    dependsOn("jandex")
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    systemProperty("quarkus.jacoco.data-file", "${multiProjectRootDir}/build/jacoco-quarkus.exec")
    systemProperty("quarkus.jacoco.report-location", "${multiProjectRootDir}/build/jacoco-report")
    systemProperty("quarkus.jacoco.reuse-data-file", "true")

}

tasks.withType<QuarkusBuildDependencies> {
    dependsOn("jandex")
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
    annotation("kvasir.definitions.annotations.ForceAllOpen")
}

noArg {
    annotation("kvasir.definitions.annotations.GenerateNoArgConstructor")
}
