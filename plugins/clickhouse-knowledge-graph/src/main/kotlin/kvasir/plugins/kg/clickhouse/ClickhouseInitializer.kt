package kvasir.plugins.kg.clickhouse

import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.event.Observes
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import org.eclipse.microprofile.config.inject.ConfigProperty

class ClickhouseInitializer(
    private val clickhouseClient: ClickhouseClient,
    @ConfigProperty(name = "kvasir.plugins.kg.clickhouse.init-db", defaultValue = "true")
    private val initDb: Boolean
) {

    fun init(@Observes @Priority(100) event: StartupEvent) {
        if (initDb) {
            Log.debug("Initializing Clickhouse schema for Kvasir system tables...")
            createDatabase(SYSTEM_DB)
                .chain { _ -> createPodSchema(SYSTEM_DB) }
                .chain { _ -> createSliceSchema(SYSTEM_DB) }
                .await().indefinitely()
        }
    }

    fun initializePodSchema(podId: String): Uni<Void> {
        Log.debug("Making sure a Clickhouse schema exists for pod $podId...")
        val database = databaseFromPodId(podId)
        return createDatabase(database)
            .chain { _ -> createDataSchema(database) }
            .chain { _ -> createTimeseriesSchema(database) }
            .chain { _ -> createMetadataSchema(database) }
            .chain { _ -> createChangeLog(database) }
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
                timestamp DateTime64(3) Codec (DoubleDelta, LZ4),
                change_request_id String,
                sign  Int8
            ) ENGINE = ReplacingMergeTree
                PARTITION BY toYYYYMM(timestamp)
                ORDER BY (subject, predicate, object, datatype, language, graph, timestamp, change_request_id, sign);
        """.trimIndent()
        )
    }

    fun createTimeseriesSchema(database: String): Uni<Void> {
        // Main table
        return clickhouseClient.execute(
            """
            CREATE TABLE IF NOT EXISTS $database.observations (
                id                 String,
                graph              LowCardinality(String),
                series_id          UInt64 Codec (Delta, LZ4),
                change_request_id  String,
                change_request_ts  DateTime64(3) Codec (DoubleDelta, LZ4),
                timestamp          DateTime64(6) Codec (DoubleDelta, LZ4),
                value_number       Float64 Codec (Gorilla, LZ4),
                value_string       String Codec (LZ4HC),
                value_bool         Bool Codec (LZ4),
                value_datatype     LowCardinality(String),
                value_lang         LowCardinality(String),
                labels             Map(LowCardinality(String), String),
                PROJECTION series_id_proj (SELECT * ORDER BY (series_id, timestamp))
            ) ENGINE = ReplacingMergeTree()               
                PARTITION BY toYYYYMM(timestamp)
                ORDER BY (series_id, timestamp, id)
            SETTINGS deduplicate_merge_projection_mode = 'drop';
            """.trimIndent()
        )
            .chain { _ ->
                // Series table
                clickhouseClient.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $database.series
                    (
                        label_name_value LowCardinality(String),
                        series_id UInt64
                    ) ENGINE = ReplacingMergeTree()
                        ORDER BY (label_name_value, series_id);
                    """.trimIndent()
                )
            }
            .chain { _ ->
                // Series as view on observation table
                clickhouseClient.execute(
                    """
                    CREATE MATERIALIZED VIEW IF NOT EXISTS $database.series_mv TO $database.series AS
                        SELECT concat (tupleElement(label,1), '=', tupleElement(label, 2)) as label_name_value, series_id 
                        FROM $database.observations array join labels as label;
                """.trimIndent()
                )
            }
    }

    fun createChangeLog(database: String): Uni<Void> {
        return clickhouseClient.execute(
            """
        CREATE TABLE IF NOT EXISTS $database.changelog (
        id String,
        slice_id LowCardinality (String),
        timestamp DateTime64 (3) Codec (DoubleDelta, LZ4),
        nr_of_inserts Int64,
        nr_of_deletes Int64,
        status_lines String,
        ) ENGINE = ReplacingMergeTree()
        ORDER BY (slice_id, timestamp, id);
        """.trimIndent()
        )
    }

    fun createMetadataSchema(database: String): Uni<Void> {
        return clickhouseClient.execute(
            """
        CREATE TABLE IF NOT EXISTS $database.metadata (
        type_uri LowCardinality (String),
        property_uri LowCardinality (String),
        property_kind LowCardinality (String),
        property_ref LowCardinality (String)
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
        pod_id LowCardinality (String),
        timestamp DateTime64 (3) Codec (DoubleDelta, LZ4),
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
        id LowCardinality (String),
        timestamp DateTime64 (3),
        json String
        ) ENGINE = ReplacingMergeTree()
        ORDER BY (id);
        """.trimIndent()
        )
    }

}