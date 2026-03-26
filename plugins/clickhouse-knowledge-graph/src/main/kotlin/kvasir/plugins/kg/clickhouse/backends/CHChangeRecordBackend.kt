package kvasir.plugins.kg.clickhouse.backends

import graphql.execution.instrumentation.Instrumentation
import graphql.schema.DataFetcher
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.inject.Singleton
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeRecordBackend
import kvasir.definitions.rdf.RDFStatement
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.graphql.CHDataFetcher
import kvasir.plugins.kg.clickhouse.graphql.PaginationInstrumentation
import kvasir.plugins.kg.clickhouse.specs.DATA_COLUMNS
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.specs.RDFDatasetQuadInsertSpec
import kvasir.plugins.kg.clickhouse.utils.MAX_PAGE_SIZE_RECORDS
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import kvasir.utils.cursors.OffsetBasedCursor
import java.time.Instant

@Singleton
class CHChangeRecordBackend(
    private val clickhouseClient: ClickhouseClient,
    private val chDataFetcher: CHDataFetcher
) : ChangeRecordBackend {

    override val preferredBatchSize: Int
        get() = 100_000

    override fun write(podId: String, changeRecords: Collection<ChangeRecord>): Uni<Void> {
        // Sort the changeRecords by subject, predicate, object, datatype, language, graph, change_id, sign (type DELETE before INSERT)
        // (Same as the table sort key: this is a recommend optimization when writing to ClickHouse)
        val sortedChangeRecords = changeRecords.sortedWith(
            compareBy(
                { it.statement.subject },
                { it.statement.predicate },
                { it.statement.`object`.toString() },
                { it.statement.dataType ?: "" },
                { it.statement.language ?: "" },
                { it.statement.graph },
                { it.changeId },
                { if (it.type == ChangeRecordType.DELETE) 0 else 1 }
            ))
        return clickhouseClient.insert(RDFDatasetQuadInsertSpec(databaseFromPodId(podId)), sortedChangeRecords)
    }

    override fun get(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>> {
        val pageSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE_RECORDS)
        val offset = request.cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val whereClause = listOfNotNull(
            "change_id = '${request.changeId}'",
            generateFilters(request)
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE (", ")") ?: ""
        val sql =
            "SELECT ${DATA_COLUMNS.joinToString()} FROM ${databaseFromPodId(request.podId)}.$DATA_TABLE $whereClause LIMIT ${pageSize + 1} OFFSET $offset"
        return clickhouseClient.query(
            GenericQuerySpec(databaseFromPodId(request.podId), DATA_TABLE, DATA_COLUMNS),
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
            "ALTER TABLE ${databaseFromPodId(request.podId)}.$DATA_TABLE DELETE WHERE change_id = '${request.changeId}'",
            databaseFromPodId(request.podId)
        )
    }

    override fun datafetcher(podId: String, context: Map<String, Any>, atChangeId: String?): DataFetcher<Any> {
        return DataFetcher { env ->
            chDataFetcher.fetchData(env, podId, context, atChangeId).convert().toCompletionStage()
        }
    }

    override fun instrumentation(
        podId: String,
        context: Map<String, Any>,
        atChangeId: String?
    ): Instrumentation {
        return PaginationInstrumentation(podId, context)
    }

    private fun resultToChangeRecord(result: Map<String, Any>): List<ChangeRecord> {
        return listOf(
            ChangeRecord(
                statement = RDFStatement(
                    subject = result["subject"] as String,
                    predicate = result["predicate"] as String,
                    `object` = result["object"] as String,
                    dataType = (result["datatype"] as String).takeIf { it.isNotBlank() },
                    language = (result["language"] as String).takeIf { it.isNotBlank() },
                    graph = result["graph"] as String
                ),
                timestamp = Instant.parse(result["timestamp"] as String),
                changeId = result["change_id"] as String,
                type = if (result["sign"] as Int == 1) ChangeRecordType.INSERT else ChangeRecordType.DELETE
            )
        )
    }

    private fun generateFilters(request: ChangeRecordRequest): String? {
        return listOfNotNull(
            request.subjectIn?.takeIf { it.isNotEmpty() }
                ?.let { subjects -> "subject IN ${subjects.joinToString(", ", "(", ")") { "'$it'" }}" },
            request.predicateIn?.takeIf { it.isNotEmpty() }?.let { predicates ->
                "predicate IN ${
                    predicates.joinToString(
                        ", ",
                        "(",
                        ")"
                    ) { "'$it'" }
                }"
            },
            request.objectIn?.takeIf { it.isNotEmpty() }?.let { objects ->
                "toString(object) IN ${
                    objects.joinToString(
                        ", ",
                        "(",
                        ")"
                    ) { "'$it'" }
                }"
            },
            request.graphIn?.takeIf { it.isNotEmpty() }
                ?.let { graphs -> "graph IN ${graphs.joinToString(", ", "(", ")") { "'$it'" }}" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }
}