package kvasir.plugins.kg.clickhouse.backends

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordRequest
import kvasir.definitions.kg.ChangeRollbackRequest
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.kg.changes.StorageBackend
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.utils.MAX_PAGE_SIZE_RECORDS
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.utils.cursors.OffsetBasedCursor

abstract class AbstractStorageBackend(
    protected val targetTable: String,
    protected val targetTableColumns: List<String>,
    protected val clickhouseClient: ClickhouseClient
) : StorageBackend {

    override fun get(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>> {
        val pageSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE_RECORDS)
        val offset = request.cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val whereClause = listOfNotNull(
            "change_request_id = '${request.changeRequestId}'"
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE (", ")") ?: ""
        val sql =
            "SELECT ${targetTableColumns.joinToString()} FROM ${databaseFromPodId(request.podId)}.$targetTable $whereClause LIMIT ${pageSize + 1} OFFSET $offset"
        return clickhouseClient.query(
            GenericQuerySpec(databaseFromPodId(request.podId), targetTable, targetTableColumns),
            sql
        )
            .map { results ->
                val processedResults = results.flatMap { resultToChangeRecord(it) }
                PagedResult(
                    items = processedResults.take(pageSize),
                    nextCursor = if (processedResults.size > pageSize) OffsetBasedCursor(offset + pageSize).encode() else null,
                    previousCursor = (offset - pageSize).takeIf { it >= 0 }?.let { OffsetBasedCursor(it).encode() }
                )
            }
    }

    override fun stream(request: ChangeRecordRequest): Multi<ChangeRecord> {
        return Multi.createBy().repeating().uni({ request }, { req ->
            get(req).map { result ->
                req.cursor = result.nextCursor
                result
            }
        })
            .whilst { it.nextCursor != null }
            .map { it.items }
            .onItem().disjoint()
    }

    override fun rollback(request: ChangeRollbackRequest): Uni<Void> {
        return clickhouseClient.execute(
            "ALTER TABLE ${databaseFromPodId(request.podId)}.$targetTable DELETE WHERE change_request_id = '${request.changeRequestId}'",
            databaseFromPodId(request.podId)
        )
    }

    protected abstract fun resultToChangeRecord(result: Map<String, Any>): List<ChangeRecord>

}