package kvasir.plugins.kg.clickhouse.specs

import io.vertx.core.json.Json
import kvasir.definitions.persistence.PersistentEntity
import kvasir.plugins.kg.clickhouse.client.ClickhouseRecord
import kvasir.plugins.kg.clickhouse.client.InsertRecordSpec
import kvasir.plugins.kg.clickhouse.client.QuerySpec
import java.time.Instant
import kotlin.reflect.KClass

internal val COLUMN_NAMES = listOf(
    "id",
    "write_ts",
    "model_version",
    "json_representation",
)

internal val QUERY_COLUMN_NAMES = listOf(
    "id",
    "_write_ts",
    "_json_representation"
)

class RepositoryWriteSpec<T : PersistentEntity>(
    database: String,
    collectionName: String,
    private val modelVersion: String
) :
    InsertRecordSpec<T>(
        database,
        collectionName,
        COLUMN_NAMES
    ) {
    override fun toRecord(t: T): ClickhouseRecord {
        t.writeTs = Instant.now()
        return ClickhouseRecord(
            listOf(
                t.id,
                t.writeTs,
                modelVersion,
                Json.encode(t)
            )
        )
    }

}

class RepositoryQuerySpec<T : PersistentEntity>(
    val entityClass: KClass<T>,
    database: String,
    collectionName: String
) :
    QuerySpec<T, String>(database, collectionName, QUERY_COLUMN_NAMES) {
    override fun fromRecord(record: ClickhouseRecord): T {
        val jsonRep = record.getString(2)
        return Json.decodeValue(jsonRep, entityClass.java)
    }

}