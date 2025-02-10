package kvasir.plugins.kg.clickhouse

import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonArray
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.kg.changes.ChangeHistory
import kvasir.definitions.kg.changes.ChangeHistoryRequest
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.kg.changes.ChangeReportStatusEntry
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.CHANGE_LOG_COLUMNS
import kvasir.plugins.kg.clickhouse.specs.CHANGE_LOG_TABLE
import kvasir.plugins.kg.clickhouse.specs.ChangelogInsertRecordSpec
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.utils.ClickhouseUtils
import kvasir.plugins.kg.clickhouse.utils.MAX_PAGE_SIZE_CHANGE_REPORTS
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.utils.cursors.OffsetBasedCursor

@ApplicationScoped
class ClickhouseChangeHistory(
    private val clickhouseClient: ClickhouseClient
) : ChangeHistory {
    override fun register(report: ChangeReport): Uni<Void> {
        return clickhouseClient.insert(ChangelogInsertRecordSpec(databaseFromPodId(report.podId)), listOf(report))
    }

    override fun list(request: ChangeHistoryRequest): Uni<PagedResult<ChangeReport>> {
        val pageSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE_CHANGE_REPORTS)
        val offset = request.cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val sql =
            "SELECT id, slice_id, timestamp, nr_of_inserts, nr_of_deletes, status_lines FROM ${
                databaseFromPodId(
                    request.podId
                )
            }.$CHANGE_LOG_TABLE ${whereClause(request)} ORDER BY (timestamp, id) DESC LIMIT ${pageSize + 1} OFFSET $offset"
        return clickhouseClient.query(
            GenericQuerySpec(
                databaseFromPodId(request.podId), CHANGE_LOG_TABLE,
                CHANGE_LOG_COLUMNS
            ), sql
        ).map { results ->
            val processedResults = results.map { resultToChangeReport(request.podId, it) }
            PagedResult(
                items = processedResults.take(pageSize),
                nextCursor = if (processedResults.size > pageSize) OffsetBasedCursor(offset + pageSize).encode() else null,
                previousCursor = (offset - pageSize).takeIf { it >= 0 }?.let { OffsetBasedCursor(it).encode() }
            )
        }
    }

    private fun whereClause(request: ChangeHistoryRequest) = listOfNotNull(
        request.changeRequestId?.let { "id = '$it'" },
        request.sliceId?.let { "slice_id = '$it'" },
        request.fromTimestamp?.let { "timestamp >= ${ClickhouseUtils.convertInstant(it)}" },
        request.toTimestamp?.let { "timestamp < ${ClickhouseUtils.convertInstant(it)}" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE ")

    override fun get(request: ChangeHistoryRequest): Uni<ChangeReport?> {
        val sql =
            "SELECT id, slice_id, timestamp, nr_of_inserts, nr_of_deletes, status_lines FROM ${
                databaseFromPodId(
                    request.podId
                )
            }.$CHANGE_LOG_TABLE ${whereClause(request)}"
        return clickhouseClient.query(
            GenericQuerySpec(
                databaseFromPodId(request.podId), CHANGE_LOG_TABLE,
                CHANGE_LOG_COLUMNS
            ), sql
        ).map { results ->
            results.firstOrNull()?.let { resultToChangeReport(request.podId, it) }
        }
    }

    private fun resultToChangeReport(podId: String, result: Map<String, Any>): ChangeReport {
        return ChangeReport(
            id = result["id"] as String,
            podId = podId,
            statusEntry = JsonArray(result["status_lines"] as String).let {
                val tmp = mutableListOf<ChangeReportStatusEntry>()
                for (i in 0 until it.size()) {
                    tmp.add(it.getJsonObject(i).mapTo(ChangeReportStatusEntry::class.java))
                }
                tmp
            },
            sliceId = (result["slice_id"] as String).takeIf { it.isNotBlank() },
            nrOfInserts = result["nr_of_inserts"].toString().toLong(),
            nrOfDeletes = result["nr_of_deletes"].toString().toLong()
        )
    }
}