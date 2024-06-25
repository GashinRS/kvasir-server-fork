plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("com.xtdb:xtdb-api:2.0.0-20240618.123640-15")
    implementation("com.xtdb:xtdb-http-client-jvm:2.0.0-20240618.123640-10")
}
