plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation(project(":plugins:common-http-extensions"))
    implementation(project(":plugins:clickhouse-knowledge-graph"))
}
