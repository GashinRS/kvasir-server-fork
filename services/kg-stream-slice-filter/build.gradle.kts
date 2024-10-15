plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation(project(":plugins:kafka-channel-initializer"))
    implementation(project(":plugins:clickhouse-knowledge-graph"))
}
