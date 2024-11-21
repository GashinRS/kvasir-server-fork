plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation("com.graphql-java:graphql-java-extended-scalars:22.0")
    implementation("org.eclipse.rdf4j:rdf4j-query:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-turtle:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-jsonld:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-n3:5.0.2")
    implementation("org.eclipse.rdf4j:rdf4j-rio-ntriples:5.0.2")
    implementation("io.github.nstdio:rsql-parser:2.3.2")
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-web-client")
    testImplementation(project(":plugins:kafka-channel-initializer"))
}