plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("io.quarkiverse.minio:quarkus-minio:3.7.5")
}
