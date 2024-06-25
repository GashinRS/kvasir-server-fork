plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-web-client")
}
