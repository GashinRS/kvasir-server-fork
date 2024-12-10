package kvasir.plugins.kg.clickhouse

import com.google.common.hash.Hashing
import graphql.TypeResolutionEnvironment
import graphql.execution.instrumentation.Instrumentation
import graphql.schema.DataFetcher
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolver
import io.quarkus.arc.All
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.inject.Singleton
import kvasir.definitions.kg.*
import kvasir.definitions.messaging.Channels
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.graphql.ConvertToSQLResolver
import kvasir.plugins.kg.clickhouse.graphql.PaginationInstrumentation
import kvasir.plugins.kg.clickhouse.specs.*
import kvasir.utils.kg.AbstractKnowledgeGraph
import kvasir.utils.kg.KGPropertyKind
import kvasir.utils.kg.KGType
import kvasir.utils.kg.MetadataEntry
import kvasir.utils.string.decodeB64
import kvasir.utils.string.encodeB64
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import java.time.Instant

private const val MAX_PAGE_SIZE_RECORDS = 25000
private const val MAX_PAGE_SIZE_CHANGE_REPORTS = 250

@Singleton
class ClickhouseKnowledgeGraph(
    @All
    private val referenceLoaders: MutableList<ReferenceLoader>,
    private val clickhouseClient: ClickhouseClient,
    private val convertToSQLResolver: ConvertToSQLResolver,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.ref-handling-buffer", defaultValue = "50000")
    private val refHandlingBuffer: Int,
    @Channel(Channels.OUTBOX_PUBLISH)
    private val outboxEmitter: MutinyEmitter<ChangeReport>
) : AbstractKnowledgeGraph(referenceLoaders, assertionCheckingParallelism, refHandlingBuffer, outboxEmitter) {
    override fun persist(
        podId: String,
        records: List<ChangeRecord>
    ): Uni<Void> {
        return clickhouseClient.insert(RDFDatasetQuadInsertSpec(databaseFromPodId(podId)), records)
            .chain { _ ->
                syncMetadata(
                    podId,
                    records.filter { it.type == ChangeRecordType.INSERT }.map { it.statement })
            }
    }

    override fun logChangeReport(
        podId: String,
        changeReport: ChangeReport
    ): Uni<Void> {
        return clickhouseClient.insert(ChangelogInsertRecordSpec(databaseFromPodId(podId)), listOf(changeReport))
    }

    override fun deleteGraph(podId: String, graph: String): Uni<Void> {
        return clickhouseClient.execute(
            "ALTER TABLE $podId.data DELETE WHERE graph = '$graph'",
            databaseFromPodId(podId)
        )
    }

    override fun buildDatafetcher(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): DataFetcher<Any> {
        return convertToSQLResolver.getDatafetcher(podId, context, atTimestamp)
    }

    override fun buildUnionTypeResolver(
        podId: String,
        unionType: GraphQLUnionType,
        context: Map<String, Any>
    ): TypeResolver {
        return RDFClassTypeResolver(context)
    }

    override fun provideInstrumentation(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): Instrumentation {
        return PaginationInstrumentation(clickhouseClient, podId, context, atTimestamp)
    }

    override fun getTypeInfo(podId: String): Uni<List<KGType>> {
        return clickhouseClient.query(
            KGTypeQuerySpec(databaseFromPodId(podId)),
            "SELECT type_uri, ARRAY_AGG([property_uri, property_kind, property_ref]) AS properties FROM ${
                databaseFromPodId(
                    podId
                )
            }.$META_DATA_TABLE GROUP BY type_uri"
        )
    }

    private fun syncMetadata(podId: String, statements: List<RDFStatement>): Uni<Void> {
        // Transform
        val typeUrisToSubjects = statements.filter { it.predicate == RDFVocab.type }.groupBy { it.`object` as String }
            .mapValues { statementsByType ->
                statementsByType.component2().map { it.subject }
            }
        // Reverse mapping
        val subjectsToTypeUris = statements.filter { it.predicate == RDFVocab.type }.groupBy { it.subject }
            .mapValues { statementsBySubject ->
                statementsBySubject.component2().map { it.`object` as String }
            }

        val metadataEntries = typeUrisToSubjects.entries.flatMap { (typeUri, subjects) ->
            statements.filter { it.subject in subjects && it.predicate != RDFVocab.type }.distinct()
                .flatMap { statement ->
                    val typeRefs = statement.dataType?.let { listOf(it) }
                        ?: subjectsToTypeUris[statement.`object` as String]?.toList() ?: listOf(RDFSVocab.Resource)
                    typeRefs.map { typeRef ->
                        MetadataEntry(
                            typeUri = typeUri,
                            propertyUri = statement.predicate,
                            propertyKind = if (statement.dataType != null) KGPropertyKind.Literal else KGPropertyKind.IRI,
                            propertyRef = typeRef
                        )
                    }
                }
        }
        return clickhouseClient.insert(MetadataInsertRecordSpec(databaseFromPodId(podId)), metadataEntries)
    }

    override fun listChanges(request: ChangeHistoryRequest): Uni<PagedResult<ChangeReport>> {
        val pageSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE_CHANGE_REPORTS)
        val offset = request.cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val sql =
            "SELECT id, slice_id, timestamp, nr_of_inserts, nr_of_deletes, result_code, error_message FROM ${
                databaseFromPodId(
                    request.podId
                )
            }.$CHANGE_LOG_TABLE ORDER BY (timestamp, id) DESC LIMIT ${pageSize + 1} OFFSET $offset"
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

    override fun getChange(request: ChangeHistoryRequest): Uni<ChangeReport?> {
        val sql =
            "SELECT id, slice_id, timestamp, nr_of_inserts, nr_of_deletes, result_code, error_message FROM ${
                databaseFromPodId(
                    request.podId
                )
            }.$CHANGE_LOG_TABLE WHERE id = '${request.changeRequestId}'"
        return clickhouseClient.query(
            GenericQuerySpec(
                databaseFromPodId(request.podId), CHANGE_LOG_TABLE,
                CHANGE_LOG_COLUMNS
            ), sql
        ).map { results ->
            results.firstOrNull()?.let { resultToChangeReport(request.podId, it) }
        }
    }

    override fun getChangeRecords(request: ChangeHistoryRequest): Uni<PagedResult<ChangeRecord>> {
        val pageSize = request.pageSize.coerceAtMost(MAX_PAGE_SIZE_RECORDS)
        val offset = request.cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val whereClause = listOfNotNull(
            "change_request_id = '${request.changeRequestId}'"
        ).takeIf { it.isNotEmpty() }?.joinToString(" AND ", "WHERE (", ")") ?: ""
        val sql =
            "SELECT * FROM ${databaseFromPodId(request.podId)}.$DATA_TABLE $whereClause LIMIT ${pageSize + 1} OFFSET $offset"
        return clickhouseClient.query(GenericQuerySpec(databaseFromPodId(request.podId), DATA_TABLE, DATA_COLUMNS), sql)
            .map { results ->
                val processedResults = results.map { resultToChangeRecord(it) }
                PagedResult(
                    items = processedResults.take(pageSize),
                    nextCursor = if (processedResults.size > pageSize) OffsetBasedCursor(offset + pageSize).encode() else null,
                    previousCursor = (offset - pageSize).takeIf { it >= 0 }?.let { OffsetBasedCursor(it).encode() }
                )
            }
    }

    override fun streamChangeRecords(request: ChangeHistoryRequest): Multi<ChangeRecord> {
        return Multi.createBy().repeating().uni({ request }, { request ->
            getChangeRecords(request).map { result ->
                request.copy(cursor = result.nextCursor)
                result
            }
        })
            .whilst { it.nextCursor != null }
            .map { it.items }
            .onItem().disjoint()
    }

    override fun rollback(request: ChangeRollbackRequest): Uni<Void> {
        return clickhouseClient.execute(
            "ALTER TABLE ${databaseFromPodId(request.podId)}.$DATA_TABLE DELETE WHERE change_request_id = '${request.changeRequestId}'",
            databaseFromPodId(request.podId)
        )
    }

    private fun resultToChangeReport(podId: String, result: Map<String, Any>): ChangeReport {
        return ChangeReport(
            id = result["id"] as String,
            podId = podId,
            sliceId = (result["slice_id"] as String).takeIf { it.isNotBlank() },
            timestamp = Instant.parse(result["timestamp"] as String),
            nrOfInserts = result["nr_of_inserts"].toString().toLong(),
            nrOfDeletes = result["nr_of_deletes"].toString().toLong(),
            resultCode = ChangeResultCode.valueOf(result["result_code"] as String),
            errorMessage = (result["error_message"] as String).takeIf { it.isNotBlank() }
        )
    }

    private fun resultToChangeRecord(result: Map<String, Any>): ChangeRecord {
        return ChangeRecord(
            statement = RDFStatement(
                subject = result["subject"] as String,
                predicate = result["predicate"] as String,
                `object` = result["object"]!!,
                dataType = (result["datatype"] as String).takeIf { it.isNotBlank() },
                language = (result["language"] as String).takeIf { it.isNotBlank() },
                graph = result["graph"] as String
            ),
            timestamp = Instant.parse(result["timestamp"] as String),
            changeRequestId = result["change_request_id"] as String,
            type = if (result["sign"] as Int == 1) ChangeRecordType.INSERT else ChangeRecordType.DELETE
        )
    }

}

internal fun databaseFromPodId(podId: String): String {
    return Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8).toString()
}

class RDFClassTypeResolver(private val context: Map<String, Any>) : TypeResolver {
    override fun getType(env: TypeResolutionEnvironment): GraphQLObjectType {
        val target = env.getObject<Target>()
        // TODO: Implement type resolution, for now just return the first type in the list
        val prefixedTypeName = JsonLdHelper.compactUri(target.types.first(), context, "_")
        return env.schema.getObjectType(prefixedTypeName)
    }
}

data class Target(val id: String, val types: List<String>)

// TODO: replace with a generic ordering-based implementation (to optimize for performance)
class OffsetBasedCursor(val offset: Long) {

    companion object {

        fun fromString(cursor: String): OffsetBasedCursor? {
            return cursor.decodeB64().toLongOrNull()?.let { OffsetBasedCursor(it) }
        }

    }

    fun encode(): String {
        return offset.toString().encodeB64()
    }
}