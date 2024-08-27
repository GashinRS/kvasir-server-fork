pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "kvasir"
include("libs:definitions")
include("libs:utils")
include("plugins:kafka-channel-initializer")
include("plugins:xtdb-knowledge-graph")
include("services:kg-change-processor")
include("services:kg-inbox-api")
include("services:kg-query-api")
include("services:monolith")
include("services:simple-rdf-ingester")
include("services:storage-api")
