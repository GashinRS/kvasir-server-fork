pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "kvasir"
include("libs:definitions")
include("plugins:clickhouse-knowledge-graph")
include("plugins:xtdb-knowledge-graph")
include("services:kg-change-processor")
include("services:kg-inbox-api")
include("services:kg-query-api")
include("services:monolith")
