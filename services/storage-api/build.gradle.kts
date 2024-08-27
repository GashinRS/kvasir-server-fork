plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation(project(":plugins:kafka-channel-initializer"))
    implementation("io.quarkus:quarkus-reactive-routes")
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-http-proxy")
    implementation("uk.co.lucasweb:aws-v4-signer-java:1.3")
    implementation("io.quarkiverse.minio:quarkus-minio:3.7.5")
}
