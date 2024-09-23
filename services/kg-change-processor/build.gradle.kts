plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation("org.apache.jena:jena-shacl:5.1.0")
    implementation("com.github.jsonld-java:jsonld-java:0.13.5")
    implementation(project(":plugins:s3-reference-loader"))
    implementation(project(":plugins:clickhouse-knowledge-graph"))
}
