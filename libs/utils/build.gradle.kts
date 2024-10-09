plugins {
    id("kvasir-commonlib")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("com.graphql-java:graphql-java-extended-scalars:22.0")
    implementation("org.eclipse.rdf4j:rdf4j-query:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-turtle:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-jsonld:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-n3:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-ntriples:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-shacl:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-storage:5.0.2")
    implementation("io.quarkiverse.minio:quarkus-minio:3.7.5")
    implementation("io.github.nstdio:rsql-parser:2.3.2")
    api("com.dashjoin:jsonata:0.9.7")
    implementation("org.apache.jena:jena-shacl:5.1.0")
}
