plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation("io.github.nstdio:rsql-parser:2.3.2")
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-web-client")
    testImplementation(project(":plugins:kafka-channel-initializer"))
}