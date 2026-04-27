package kvasir.plugins.kg.clickhouse.persistence

import cz.jirutka.rsql.parser.RSQLParser
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.persistence.*
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.*
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.plugins.kg.clickhouse.utils.parsePersistentAnnotation
import kvasir.utils.cursors.OffsetBasedCursor
import kotlin.reflect.KClass

internal const val MAIN_BRANCH = "main"

// ─────────────────────────────────────────────────────────────────────────────
// Factory
// ─────────────────────────────────────────────────────────────────────────────

@ApplicationScoped
class CHRepositoryFactory(private val clickhouseClient: ClickhouseClient) : RepositoryFactory {

    override fun <T : PersistentEntity> getRepository(
        entityClass: KClass<T>,
        podId: String?
    ): Repository<T> {
        val (storageLevel, collectionName, modelVersion, versioned) = parsePersistentAnnotation(entityClass.java)
        if (versioned) {
            throw IllegalStateException("A non-versioned repository was requested for versioned entity '${entityClass.simpleName}'")
        }
        val database = resolveDatabase(storageLevel, entityClass, podId)
        return CHRepository(clickhouseClient, entityClass, database, collectionName, modelVersion)
    }

    override fun <T : PersistentEntity> getVersionedRepository(
        entityClass: KClass<T>,
        podId: String?
    ): VersionedRepository<T> {
        val (storageLevel, collectionName, modelVersion, versioned) = parsePersistentAnnotation(entityClass.java)
        if (!versioned) {
            throw IllegalStateException("A versioned repository was requested for a non-versioned entity '${entityClass.simpleName}'")
        }
        val database = resolveDatabase(storageLevel, entityClass, podId)
        return CHVersionedRepository(clickhouseClient, entityClass, database, collectionName, modelVersion)
    }

    private fun resolveDatabase(storageLevel: StorageLevel, entityClass: KClass<*>, podId: String?): String =
        when (storageLevel) {
            StorageLevel.SYSTEM -> SYSTEM_DB
            StorageLevel.PER_POD -> podId?.let { databaseFromPodId(it) }
                ?: throw IllegalArgumentException("Pod ID required for per-pod entity $entityClass")
        }
}

private const val DEFAULT_LIMIT = 250

// ─────────────────────────────────────────────────────────────────────────────
// Abstract base — common CRUD logic shared by both repository variants
// ─────────────────────────────────────────────────────────────────────────────

abstract class AbstractCHRepository<T : PersistentEntity>(
    val clickhouseClient: ClickhouseClient,
    val pojoClass: KClass<T>,
    val database: String,
    val collectionName: String,
    val modelVersion: String
) : Repository<T> {

    protected val querySpec = RepositoryQuerySpec(pojoClass, database, collectionName)
    protected open val writeSpec: RepositoryWriteSpec<T> =
        RepositoryWriteSpec(database, collectionName, modelVersion)

    /** Fully-qualified table name for use in SQL */
    protected val table = "`$database`.$collectionName"

    /**
     * Subclasses may return a fixed SQL condition (no WHERE prefix) that is always
     * AND-ed into every query — e.g. "branch = 'main'" for the versioned repository.
     */
    protected open fun defaultFilter(): String? = null

    override fun findById(id: String): Uni<T?> {
        val where = buildWhere("id = '$id'")
        val sql = """
            SELECT id,
                   max(revision_id)                         AS _revision_id,
                   argMax(json_representation, revision_id) AS _json_representation
            FROM $table
            $where
            GROUP BY id
            LIMIT 1
        """.trimIndent()
        return clickhouseClient.query(querySpec, sql).map { it.firstOrNull() }
    }

    override fun find(
        filter: String?,
        limit: Int?,
        cursor: String?,
        sort: Sort
    ): Uni<PagedResult<T>> {
        val offset = cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val pageSize = limit ?: DEFAULT_LIMIT
        val userFilter = filter
            ?.let { CHFilterVisitor(pojoClass).visitNode(RSQLParser().parse(it)) }
            ?.takeIf { it.isNotEmpty() }
        val where = buildWhere(userFilter)
        val sql = """
            SELECT id,
                   max(revision_id)                         AS _revision_id,
                   argMax(json_representation, revision_id) AS _json_representation
            FROM $table
            $where
            GROUP BY id
            ${orderClause(sort)}
            LIMIT ${pageSize + 1} OFFSET $offset
        """.trimIndent()
        return clickhouseClient.query(querySpec, sql).map { results ->
            if (results.size > pageSize)
                PagedResult(results.take(pageSize), OffsetBasedCursor(offset + pageSize).encode())
            else
                PagedResult(results)
        }
    }

    override fun persist(entity: T): Uni<Void> =
        clickhouseClient.insert(writeSpec, listOf(entity))

    override fun persist(entities: List<T>): Uni<Void> =
        clickhouseClient.insert(writeSpec, entities)

    override fun deleteById(id: String): Uni<Void> =
        clickhouseClient.execute(
            "ALTER TABLE $table DELETE WHERE id = '$id' SETTINGS mutations_sync = 1",
            database
        )

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a WHERE clause combining [defaultFilter] and any additional [conditions].
     * Null / blank conditions are skipped.
     */
    protected fun buildWhere(vararg conditions: String?): String {
        val parts = listOfNotNull(defaultFilter(), *conditions).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "" else parts.joinToString(" AND ", "WHERE ")
    }

    protected fun orderClause(sort: Sort): String {
        if (sort.columns.isEmpty()) return ""
        return "ORDER BY " + sort.columns.joinToString(", ") { col ->
            val expr = mapSelector(pojoClass, col.name).let { raw ->
                when {
                    raw == "revision_id" -> "_revision_id"
                    raw.contains("json_representation") -> raw.replace("json_representation", "_json_representation")
                    else -> raw
                }
            }
            if (col.direction == SortOrder.DESC) "$expr DESC" else expr
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Non-versioned repository — single collection table, no branch column
// ─────────────────────────────────────────────────────────────────────────────

class CHRepository<T : PersistentEntity>(
    clickhouseClient: ClickhouseClient,
    pojoClass: KClass<T>,
    database: String,
    collectionName: String,
    modelVersion: String
) : AbstractCHRepository<T>(clickhouseClient, pojoClass, database, collectionName, modelVersion)

// ─────────────────────────────────────────────────────────────────────────────
// Versioned repository — branch-aware main table + tags table
//
// The main timeline always lives on the "main" branch.  Tags in the tags table
// hold a revision_id pointer that can reference any branch, enabling a tagged
// revision to evolve on its own branch (via parent_revision_id) without
// affecting the main timeline.
// ─────────────────────────────────────────────────────────────────────────────

class CHVersionedRepository<T : PersistentEntity>(
    clickhouseClient: ClickhouseClient,
    pojoClass: KClass<T>,
    database: String,
    collectionName: String,
    modelVersion: String
) : AbstractCHRepository<T>(clickhouseClient, pojoClass, database, collectionName, modelVersion),
    VersionedRepository<T> {

    /** Restrict the main timeline queries to the main branch only */
    override fun defaultFilter() = "branch = '$MAIN_BRANCH'"

    override val writeSpec: RepositoryWriteSpec<T> =
        VersionedRepositoryWriteSpec(database, collectionName, modelVersion)

    private val tagsTable = "`$database`.${collectionName}_tags"
    private val tagsQuerySpec = EntityTagQuerySpec(database, collectionName)

    // ── VersionedRepository ───────────────────────────────────────────────────

    /**
     * Find the revision currently linked to [tag].
     *
     * The tags table is the authority; we join back to the collection so we can
     * materialise the entity regardless of which branch the revision lives on.
     */
    override fun findById(id: String, tag: String): Uni<T?> {
        val sql = """
            SELECT c.id,
                   c.revision_id         AS _revision_id,
                   c.json_representation AS _json_representation
            FROM $table c
            INNER JOIN (
                SELECT revision_id
                FROM $tagsTable
                WHERE id = '$id' AND tag_name = '$tag'
                ORDER BY created_at DESC
                LIMIT 1
            ) t ON c.revision_id = t.revision_id
            WHERE c.id = '$id'
            LIMIT 1
        """.trimIndent()
        return clickhouseClient.query(querySpec, sql).map { it.firstOrNull() }
    }

    override fun findDefaultForId(id: String): Uni<T?> {
        // Try the explicit "default" tag first; if absent fall back to the
        // latest revision on the main branch (same as findById(id)).
        return findById(id, "default").chain { tagged: T? ->
            if (tagged != null) Uni.createFrom().item(tagged)
            else findById(id)
        }
    }

    /**
     * Persist [entity] on the main branch and immediately link it to each tag in [tags].
     */
    override fun persist(entity: T, tags: Set<String>): Uni<Void> {
        // branch falls back to MAIN_BRANCH inside VersionedRepositoryWriteSpec
        return persist(entity).chain { _: Void? ->
            val revisionId = entity.revisionId
                ?: error("revisionId must be set by the write spec during persist")
            val records = tags.map { TagRecord(entity.id, it, revisionId, entity.createdBy) }
            clickhouseClient.insert(TagInsertSpec(database, collectionName), records)
        }
    }

    /**
     * Add [entityTag] for entity [id], overwriting any prior pointer for that tag name.
     */
    override fun addTag(id: String, entityTag: EntityTag): Uni<Void> {
        return clickhouseClient.insert(
            TagInsertSpec(database, collectionName),
            listOf(TagRecord(id, entityTag.tag, entityTag.revisionId, entityTag.createdBy))
        )
    }

    /**
     * Remove [tag] from entity [id].
     */
    override fun removeTag(id: String, tag: String): Uni<Void> {
        val sql = """
            ALTER TABLE $tagsTable DELETE
            WHERE id = '$id'
              AND tag_name = '$tag'
            SETTINGS mutations_sync = 1
        """.trimIndent()
        return clickhouseClient.execute(sql, database)
    }

    /**
     * List all tags for [id], one [EntityTag] per tag name, sorted by [sortByTimestamp].
     */
    override fun listTags(
        id: String,
        limit: Int?,
        cursor: String?,
        sortByTimestamp: SortOrder
    ): Uni<PagedResult<EntityTag>> {
        val offset = cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val pageSize = limit ?: DEFAULT_LIMIT
        val order = if (sortByTimestamp == SortOrder.DESC) "DESC" else "ASC"
        val sql = """
            SELECT revision_id,
                   tag_name,
                   created_at,
                   created_by
            FROM $tagsTable FINAL
            WHERE id = '$id'
            ORDER BY created_at $order
            LIMIT ${pageSize + 1} OFFSET $offset
        """.trimIndent()
        return clickhouseClient.query(tagsQuerySpec, sql).map { results ->
            if (results.size > pageSize)
                PagedResult(results.take(pageSize), OffsetBasedCursor(offset + pageSize).encode())
            else
                PagedResult(results)
        }
    }

    override fun getLineage(entities: List<T>): Uni<Map<T, RevisionLineage?>> {
        if (entities.isEmpty()) return Uni.createFrom().item(emptyMap())

        // Build an IN-list of (id, revision_id) pairs for all input entities.
        val inList = entities.joinToString(", ") { e ->
            "('${e.id}', '${e.revisionId}')"
        }

        // For entities whose branch is null we fall back to the main branch.
        val sql = """
            WITH
            input AS (
                SELECT id,
                       revision_id AS current_rev,
                       if(branch = '', '$MAIN_BRANCH', branch) AS branch
                FROM (SELECT id, revision_id, branch FROM $table FINAL)
                WHERE (id, revision_id) IN ($inList)
            ),
            tagged_on_branch AS (
                SELECT t.id,
                       t.tag_name,
                       t.revision_id AS tagged_rev,
                       if(c.branch = '', '$MAIN_BRANCH', c.branch) AS branch
                FROM (SELECT id, tag_name, revision_id FROM $tagsTable FINAL) t
                INNER JOIN (SELECT id, revision_id, branch FROM $table FINAL) c
                    ON c.id = t.id AND c.revision_id = t.revision_id
            ),
            best_tagged AS (
                SELECT i.id,
                       i.current_rev,
                       i.branch,
                       max(tb.tagged_rev) AS best_tagged_rev
                FROM input i
                INNER JOIN tagged_on_branch tb
                    ON tb.id = i.id AND tb.branch = i.branch
                WHERE tb.tagged_rev <= i.current_rev
                GROUP BY i.id, i.current_rev, i.branch
            ),
            tags_for_best AS (
                SELECT bt.id,
                       bt.current_rev,
                       bt.branch,
                       bt.best_tagged_rev,
                       groupArray(tb.tag_name) AS tags
                FROM best_tagged bt
                INNER JOIN tagged_on_branch tb
                    ON tb.id = bt.id AND tb.tagged_rev = bt.best_tagged_rev
                GROUP BY bt.id, bt.current_rev, bt.branch, bt.best_tagged_rev
            ),
            lineage AS (
                SELECT tf.id,
                       tf.current_rev,
                       tf.tags,
                       tf.best_tagged_rev,
                       countIf(
                           c.revision_id > tf.best_tagged_rev AND c.revision_id <= tf.current_rev
                       ) AS n_ahead
                FROM tags_for_best tf
                INNER JOIN (SELECT id, revision_id, branch FROM $table FINAL) c
                    ON c.id = tf.id
                WHERE if(c.branch = '', '$MAIN_BRANCH', c.branch) = tf.branch
                GROUP BY tf.id, tf.current_rev, tf.tags, tf.best_tagged_rev
            )
            SELECT id, current_rev, tags, best_tagged_rev, n_ahead
            FROM lineage
        """.trimIndent()

        val lineageSpec = object : kvasir.plugins.kg.clickhouse.client.QuerySpec<RevisionLineage, String>(
            database, collectionName, listOf("id", "current_rev", "tags", "best_tagged_rev", "n_ahead")
        ) {
            override fun fromRecord(record: kvasir.plugins.kg.clickhouse.client.ClickhouseRecord): RevisionLineage {
                val id = record.getString(0)
                val currentRev = record.getString(1)
                val tags = record.getJsonArray(2).map { it.toString() }.toSet()
                val taggedRev = record.getString(3)
                val nAhead = record.getValue(4).let {
                    when (it) {
                        is Number -> it.toInt()
                        is String -> it.toIntOrNull() ?: 0
                        else -> 0
                    }
                }
                return RevisionLineage(id, currentRev, tags, taggedRev, nAhead)
            }
        }

        return clickhouseClient.query(lineageSpec, sql).map { lineageRows ->
            val lineageById = lineageRows.associateBy { it.revisionId }
            entities.associateWith { entity -> lineageById[entity.revisionId] }
        }
    }

}