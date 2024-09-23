package kvasir.plugins.kg.clickhouse

import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.event.Observes
import kvasir.definitions.config.StaticBootstrapConfig
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient

class ClickhouseInitializer(private val clickhouseClient: ClickhouseClient) {

    fun init(@Observes event: StartupEvent, config: StaticBootstrapConfig) {
        Multi.createFrom().iterable(config.pods().map { it.name() })
            .onItem().transformToUniAndConcatenate { pod ->
                Log.debug("Making sure a Clickhouse schema exists pod $pod...")
                val database = databaseFromPodId(pod)
                createDatabase(database)
                    .chain { _ -> createDataSchema(database) }
                    .chain { _ -> createMetadataSchema(database) }
            }
            .skipToLast()
            .await().indefinitely()
    }

    private fun createDatabase(database: String): Uni<Void> {
        return clickhouseClient.execute("CREATE DATABASE IF NOT EXISTS $database;")
    }

    private fun createDataSchema(database: String): Uni<Void> {
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

    private fun createMetadataSchema(database: String): Uni<Void> {
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

}