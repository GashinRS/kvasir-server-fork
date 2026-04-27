package kvasir.plugins.kg.clickhouse.specs

import com.github.f4b6a3.uuid.UuidCreator
import io.vertx.core.json.Json
import kvasir.definitions.persistence.EntityTag
import kvasir.definitions.persistence.PersistentEntity
import kvasir.plugins.kg.clickhouse.client.ClickhouseRecord
import kvasir.plugins.kg.clickhouse.client.InsertRecordSpec
import kvasir.plugins.kg.clickhouse.client.QuerySpec
import kvasir.plugins.kg.clickhouse.persistence.MAIN_BRANCH
import java.time.Instant
import kotlin.reflect.KClass

internal val REPOSITORY_COLUMN_NAMES = listOf(
    "id",
    "revision_id",
    "created_by",
    "model_version",
    "json_representation",
)

internal val VERSIONED_REPOSITORY_COLUMN_NAMES = REPOSITORY_COLUMN_NAMES + listOf(
    "parent_revision_id",
    "branch"
)

internal val QUERY_COLUMN_NAMES = listOf(
    "id",
    "_revision_id",
    "_json_representation"
)

internal val TAGS_COLUMN_NAMES = listOf(
    "id",
    "tag_name",
    "revision_id",
    "created_by",
    "created_at"
)

open class RepositoryWriteSpec<T : PersistentEntity>(
    database: String,
    collectionName: String,
    private val modelVersion: String,
    columns: List<String> = REPOSITORY_COLUMN_NAMES
) :
    InsertRecordSpec<T>(
        database,
        collectionName,
        columns
    ) {
    override fun toRecord(t: T): ClickhouseRecord {
        t.revisionId = UuidCreator.getTimeOrderedEpoch().toString()
        return ClickhouseRecord(
            mutableListOf(
                t.id,
                t.revisionId,
                t.createdBy,
                modelVersion,
                Json.encode(t)
            )
        )
    }

}

class VersionedRepositoryWriteSpec<T : PersistentEntity>(
    database: String,
    collectionName: String,
    private val modelVersion: String
) : RepositoryWriteSpec<T>(
    database,
    collectionName,
    modelVersion,
    VERSIONED_REPOSITORY_COLUMN_NAMES
) {
    override fun toRecord(t: T): ClickhouseRecord {
        return super.toRecord(t)
            .add(t.parentRevisionId ?: "")
            .add(t.branch ?: MAIN_BRANCH)
    }

}

class RepositoryQuerySpec<T : PersistentEntity>(
    val entityClass: KClass<T>,
    database: String,
    collectionName: String
) :
    QuerySpec<T, String>(database, collectionName, QUERY_COLUMN_NAMES) {
    override fun fromRecord(record: ClickhouseRecord): T {
        val revisionId = record.getString(1)         // _revision_id
        val jsonRep = record.getString(2)             // _json_representation
        return Json.decodeValue(jsonRep, entityClass.java).also { it.revisionId = revisionId }
    }
}

// ── Tags support ──────────────────────────────────────────────────────────────

data class TagRecord(
    val id: String,
    val tagName: String,
    val revisionId: String,
    val createdBy: String
)

class TagInsertSpec(database: String, collectionName: String) :
    InsertRecordSpec<TagRecord>(
        database,
        "${collectionName}_tags",
        listOf("id", "tag_name", "revision_id", "created_by") // created_at uses DEFAULT now64()
    ) {
    override fun toRecord(t: TagRecord): ClickhouseRecord =
        ClickhouseRecord(listOf(t.id, t.tagName, t.revisionId, t.createdBy))
}

class EntityTagQuerySpec(database: String, collectionName: String) :
    QuerySpec<EntityTag, String>(
        database,
        "${collectionName}_tags",
        listOf("revision_id", "tag_name", "created_at", "created_by")
    ) {
    override fun fromRecord(record: ClickhouseRecord): EntityTag {
        val revisionId = record.getString(0)
        val tag = record.getString(1)
        val createdAt = Instant.parse(record.getString(2))
        val createdBy = record.getString(3)
        return EntityTag(revisionId, tag, createdAt, createdBy)
    }
}