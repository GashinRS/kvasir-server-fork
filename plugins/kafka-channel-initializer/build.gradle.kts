plugins {
    id("kvasir-commonlib")
    id("io.quarkus")
}

dependencies {
    implementation(project(":libs:definitions"))
}