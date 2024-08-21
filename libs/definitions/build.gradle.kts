plugins {
    id("kvasir-commonlib")
}

dependencies {
    api("com.github.jsonld-java:jsonld-java:0.13.5")
    api("com.graphql-java:graphql-java:22.1")
    implementation("org.eclipse.microprofile.openapi:microprofile-openapi-api:3.1.1")
}
