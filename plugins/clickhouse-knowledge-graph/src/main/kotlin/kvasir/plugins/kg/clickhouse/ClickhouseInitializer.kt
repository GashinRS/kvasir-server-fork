package kvasir.plugins.kg.clickhouse

import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.event.Observes
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB

class ClickhouseInitializer(private val clickhouseClient: ClickhouseClient) {

    fun init(@Observes @Priority(100) event: StartupEvent) {
        Log.debug("Initializing Clickhouse schema for Kvasir system tables...")
        createDatabase(SYSTEM_DB)
            .chain { _ -> createPodSchema(SYSTEM_DB) }
            .chain { _ -> createSliceSchema(SYSTEM_DB) }
            .await().indefinitely()
    }

    fun initializePodSchema(podId: String): Uni<Void> {
        Log.debug("Making sure a Clickhouse schema exists for pod $podId...")
        val database = databaseFromPodId(podId)
        return createDatabase(database)
            .chain { _ -> createDataSchema(database) }
            .chain { _ -> createMetadataSchema(database) }
    }

    fun createDatabase(database: String): Uni<Void> {
        return clickhouseClient.execute("CREATE DATABASE IF NOT EXISTS $database;")
    }

    fun createDataSchema(database: String): Uni<Void> {
        return clickhouseClient.execute(
            """
            CREATE TABLE IF NOT EXISTS $database.data (
                subject String,
                predicate String,
                object Dynamic,
                datatype LowCardinality(String),
                language LowCardinality(String),
                graph LowCardinality(String),
                sign  Int8,
                timestamp DateTime64(3) MATERIALIZED now64()
            ) ENGINE = CollapsingMergeTree(sign)
                PARTITION BY toYYYYMM(timestamp)
                ORDER BY (subject, predicate, object, datatype, language, graph);
        """.trimIndent()
        )
    }

    fun createMetadataSchema(database: String): Uni<Void> {
        return clickhouseClient.execute(
            """
            CREATE TABLE IF NOT EXISTS $database.metadata (
                type_uri LowCardinality(String),
                property_uri LowCardinality(String),
                property_kind LowCardinality(String),
                property_ref LowCardinality(String)
            ) ENGINE = ReplacingMergeTree()
                ORDER BY (type_uri, property_uri, property_ref, property_kind);
        """.trimIndent()
        )
    }

    fun createSliceSchema(database: String): Uni<Void> {
        return clickhouseClient.execute(
            """
            CREATE TABLE IF NOT EXISTS $database.slices (
                id String,
                pod_id String,
                timestamp DateTime64(3),
                json String
            ) ENGINE = ReplacingMergeTree()
                ORDER BY (pod_id, id);
        """.trimIndent()
        )
    }

    fun createPodSchema(database: String): Uni<Void> {
        return clickhouseClient.execute(
            """
            CREATE TABLE IF NOT EXISTS $database.pods (
                id String,
                timestamp DateTime64(3),
                json String
            ) ENGINE = ReplacingMergeTree()
                ORDER BY (id);
        """.trimIndent()
        )
    }

}