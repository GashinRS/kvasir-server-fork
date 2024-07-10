plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("com.github.jsonld-java:jsonld-java:0.13.5")
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-web-client")
}
