plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("org.glassfish:jakarta.json:2.0.1")
    implementation("com.github.jsonld-java:jsonld-java:0.13.5")
    implementation(project(":plugins:xtdb-knowledge-graph"))
}
