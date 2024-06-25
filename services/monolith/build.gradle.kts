plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":services:kg-change-processor"))
    implementation(project(":services:kg-inbox-api"))
    implementation(project(":services:kg-query-api"))
}
