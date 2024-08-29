plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("com.dashjoin:jsonata:0.9.7")
    implementation("io.github.nstdio:rsql-parser:2.3.2")
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-web-client")
}