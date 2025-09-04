package kvasir.plugins.kg.clickhouse.persistence

import cz.jirutka.rsql.parser.RSQLParser
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.Repository
import kvasir.definitions.persistence.Sort
import kvasir.definitions.persistence.SortOrder
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.EntityQuerySpec
import kvasir.plugins.kg.clickhouse.specs.EntityWriteSpec
import kvasir.plugins.kg.clickhouse.utils.toSnakeCase
import kvasir.utils.cursors.OffsetBasedCursor

private const val DEFAULT_LIMIT = 250

/**
 * Abstract base class for ClickHouse-based repositories managing PersistentEntity instances.
 *
 * @param T The type of PersistentEntity managed by the repository.
 * @property querySpec Specification for querying entities from ClickHouse.
 * @property insertSpec Specification for inserting entities into ClickHouse.
 * @property contextFilter A context-specific filter applied to all queries (e.g., multi-tenancy).
 */
abstract class AbstractRepository<T : PersistentEntity>(
    protected val clickhouseClient: ClickhouseClient,
    protected val querySpec: EntityQuerySpec<T>,
    protected val insertSpec: EntityWriteSpec<T>,
    contextFilter: String? = null
) : Repository<T> {

    protected val contextFilterExpr =
        contextFilter?.let { CHFilterVisitor(insertSpec).visitNode(RSQLParser().parse(it)) }

    override fun findById(id: String): Uni<T?> {
        val whereClause = listOfNotNull(
            "id = '$id'",
            contextFilterExpr
        ).joinToString(separator = " AND ", prefix = "WHERE ")
        val sql =
            "SELECT ${getSelectFields().joinToString(", ")} FROM ${querySpec.table} $whereClause GROUP BY id LIMIT 1"
        return clickhouseClient.query(querySpec, sql)
            .map { results ->
                if (results.isEmpty()) null else results.first()
            }
    }

    override fun find(filter: String?, limit: Int?, cursor: String?, sort: Sort): Uni<PagedResult<T>> {
        val offset = cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val pageSize = (limit ?: DEFAULT_LIMIT)
        val whereClause = listOfNotNull(
            contextFilterExpr,
            filter?.let { CHFilterVisitor(insertSpec).visitNode(RSQLParser().parse(it)) }?.takeIf { it.isNotEmpty() }
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = "WHERE ") ?: ""
        val sql =
            "SELECT ${getSelectFields().joinToString(", ")} FROM ${querySpec.table} $whereClause GROUP BY id ${
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

    private fun getSelectFields(): List<String> {
        return listOf("id") + querySpec.selectedFields.filterNot { it == "id" }.map {
            if (it == "write_ts") "anyLast(write_ts) AS _write_ts" else "argMax($it, write_ts)"
        }
    }

    override fun persist(entity: T): Uni<Void> {
        return clickhouseClient.insert(insertSpec, listOf(entity))
    }

    override fun deleteById(id: String): Uni<Void> {
        val sql =
            "ALTER TABLE ${insertSpec.table} DELETE WHERE id = '$id' SETTINGS mutations_sync = 1"
        return clickhouseClient.execute(sql, insertSpec.database)
    }

    protected fun orderClause(sort: Sort): String {
        return if (sort.columns.isNotEmpty()) {
            "ORDER BY ${
                sort.columns.joinToString(", ") { sortColumn ->
                    val expr = toSnakeCase(sortColumn.name).let {
                        // Use aggregate safe version of write_ts
                        if (it == "write_ts") "_write_ts" else it
                    }
                    if (sortColumn.direction == SortOrder.DESC) "$expr DESC" else expr
                }
            }"
        } else {
            ""
        }
    }

}