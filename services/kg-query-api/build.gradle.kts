plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation(project(":plugins:prefix-cc-namespaces"))
    implementation("com.github.jsonld-java:jsonld-java:0.13.5")
    implementation(project(":plugins:xtdb-knowledge-graph"))
}
