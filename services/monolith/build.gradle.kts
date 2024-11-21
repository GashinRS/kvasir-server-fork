plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation(project(":libs:utils"))
    implementation(project(":services:kg-change-processor"))
    implementation(project(":services:kg-inbox-api"))
    implementation(project(":services:kg-query-api"))
    implementation(project(":services:kg-stream-api"))
    implementation(project(":services:pod-management-api"))
    implementation(project(":services:storage-api"))
    implementation(project(":services:simple-rdf-ingester"))
}
