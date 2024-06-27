plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("com.xtdb:xtdb-api:2.0.0-SNAPSHOT")
    implementation("com.xtdb:xtdb-http-client-jvm:2.0.0-SNAPSHOT")
    implementation("com.github.jsonld-java:jsonld-java:0.13.5")
}
