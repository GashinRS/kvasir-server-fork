plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("io.quarkiverse.minio:quarkus-minio:3.7.5")
    implementation("org.apache.jena:jena-shacl:5.1.0")
    implementation("com.github.jsonld-java:jsonld-java:0.13.5")
}
