pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "kvasir"
include("libs:definitions")
include("libs:utils")
include("plugins:clickhouse-knowledge-graph")
include("plugins:common-http-extensions")
include("plugins:kafka-channel-initializer")
include("plugins:s3-reference-loader")
include("services:kg-change-processor")
include("services:kg-inbox-api")
include("services:kg-query-api")
include("services:kg-stream-api")
include("services:kg-stream-slice-filter")
include("services:monolith")
include("services:simple-rdf-ingester")
include("services:storage-api")
