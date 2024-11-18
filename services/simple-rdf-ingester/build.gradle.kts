plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation("io.quarkiverse.minio:quarkus-minio:3.7.5")
}
