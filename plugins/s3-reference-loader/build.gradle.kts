plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation("org.eclipse.rdf4j:rdf4j-query:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-turtle:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-jsonld:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-n3:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-ntriples:5.0.2")
    implementation("io.quarkiverse.minio:quarkus-minio:3.7.5")
    testImplementation(project(":plugins:kafka-channel-initializer"))
}