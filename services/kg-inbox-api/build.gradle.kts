plugins {
    id("kvasir-service")
}

dependencies {
    implementation(project(":libs:definitions"))
    implementation("com.github.jsonld-java:jsonld-java:0.13.5")
}
