plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":services:kg-change-processor"))
    implementation(project(":services:kg-inbox-api"))
    implementation(project(":services:kg-query-api"))
    implementation(project(":services:kg-stream-api"))
    implementation(project(":services:kg-stream-slice-filter"))
    implementation(project(":services:pod-management-api"))
    implementation(project(":services:storage-api"))
    implementation(project(":services:simple-rdf-ingester"))
    implementation("io.quarkiverse.minio:quarkus-minio:3.7.5")
}
