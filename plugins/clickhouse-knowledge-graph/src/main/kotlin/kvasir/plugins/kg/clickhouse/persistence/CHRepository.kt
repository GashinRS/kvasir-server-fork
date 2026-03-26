package kvasir.plugins.kg.clickhouse.persistence

import cz.jirutka.rsql.parser.RSQLParser
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.persistence.*
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.RepositoryQuerySpec
import kvasir.plugins.kg.clickhouse.specs.RepositoryWriteSpec
import kvasir.plugins.kg.clickhouse.specs.SYSTEM_DB
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.plugins.kg.clickhouse.utils.parsePersistentAnnotation
import kvasir.utils.cursors.OffsetBasedCursor
import kotlin.reflect.KClass

@ApplicationScoped
class CHRepositoryFactory(private val clickhouseClient: ClickhouseClient) : RepositoryFactory {

    override fun <T : PersistentEntity> getRepository(
        entityClass: KClass<T>,
        podId: String?
    ): Repository<T> {
        val (storageLevel, collectionName, modelVersion) = parsePersistentAnnotation(entityClass.java)
        val database = when (storageLevel) {
            StorageLevel.SYSTEM -> SYSTEM_DB
            StorageLevel.PER_POD -> podId?.let { databaseFromPodId(it) }
                ?: throw IllegalArgumentException("Pod ID must be provided for per-pod storage level entity $entityClass")
        }
        return CHRepository(
            clickhouseClient,
            entityClass,
            database,
            collectionName,
            modelVersion
        )
    }

}

private const val DEFAULT_LIMIT = 250

class CHRepository<T : PersistentEntity>(
    val clickhouseClient: ClickhouseClient,
    val pojoClass: KClass<T>,
    val database: String,
    val collectionName: String,
    val modelVersion: String
) :
    Repository<T> {

    private val querySpec = RepositoryQuerySpec(pojoClass, database, collectionName)
    private val writeSpec = RepositoryWriteSpec<T>(database, collectionName, modelVersion)

    override fun findById(id: String): Uni<T?> {
        val sql =
            "SELECT id, max(write_ts) AS _write_ts, argMax(json_representation, write_ts) as _json_representation FROM ${querySpec.table} WHERE id = '$id' GROUP BY id LIMIT 1"
        return clickhouseClient.query(querySpec, sql)
            .map { results ->
                if (results.isEmpty()) null else results.first()
            }
    }

    override fun find(
        filter: String?,
        limit: Int?,
        cursor: String?,
        sort: Sort
    ): Uni<PagedResult<T>> {
        val offset = cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val pageSize = (limit ?: DEFAULT_LIMIT)
        val whereClause = listOfNotNull(
            filter?.let { CHFilterVisitor(pojoClass).visitNode(RSQLParser().parse(it)) }?.takeIf { it.isNotEmpty() }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = "WHERE ") ?: ""
        val sql =
            "SELECT id, max(write_ts) AS _write_ts, argMax(json_representation, write_ts) as _json_representation FROM ${querySpec.table} $whereClause GROUP BY id ${
                orderClause(
                    sort
                )
            } LIMIT ${pageSize + 1} OFFSET $offset"
        return clickhouseClient.query(querySpec, sql).map { results ->
            if (results.size > pageSize) {
                // If we have more results than the page size, we have a next cursor
                val nextCursor = OffsetBasedCursor(offset + pageSize).encode()
                PagedResult(results.take(pageSize), nextCursor)
            } else {
                // No next cursor, return all results
                PagedResult(results)
            }
        }
    }

    override fun persist(entity: T): Uni<Void> {
        return clickhouseClient.insert(writeSpec, listOf(entity))
    }

    override fun persist(entities: List<T>): Uni<Void> {
        return clickhouseClient.insert(writeSpec, entities)
    }

    override fun deleteById(id: String): Uni<Void> {
        val sql =
            "ALTER TABLE ${writeSpec.table} DELETE WHERE id = '$id' SETTINGS mutations_sync = 1"
        return clickhouseClient.execute(sql, writeSpec.database)
    }

    protected fun orderClause(sort: Sort): String {
        return if (sort.columns.isNotEmpty()) {
            "ORDER BY ${
                sort.columns.joinToString(", ") { sortColumn ->
                    val expr = mapSelector(pojoClass, sortColumn.name).let {
                        // Use aggregate safe version of non-id fields
                        when {
                            it == "write_ts" -> "_write_ts"
                            it.contains("json_representation") -> it.replaceFirst(
                                "json_representation",
                                "_json_representation"
                            )

                            else -> it
                        }
                    }
                    if (sortColumn.direction == SortOrder.DESC) "$expr DESC" else expr
                }
            }"
        } else {
            ""
        }
    }

}