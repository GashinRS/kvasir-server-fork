plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation(project(":plugins:common-http-extensions"))
    implementation(project(":plugins:kafka-channel-initializer"))
    implementation(project(":plugins:clickhouse-knowledge-graph"))
    implementation("com.github.jsonld-java:jsonld-java:0.13.5")
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-kafka-client")
}
