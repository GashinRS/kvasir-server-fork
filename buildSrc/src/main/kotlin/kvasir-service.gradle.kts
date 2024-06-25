/**
 * Plugin that defines a launchable service type project (that uses the Quarkus platform).
 * Add this plugin to a project to inherit preset configuration, including the dependencies that are
 * typically used for this type of service: REST API utilities, Kafka client, JUnit, etc.
 */
plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation("io.quarkus:quarkus-resteasy-reactive-jackson")
    implementation("io.quarkus:quarkus-security")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-container-image-jib")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-test-kafka-companion")
}

