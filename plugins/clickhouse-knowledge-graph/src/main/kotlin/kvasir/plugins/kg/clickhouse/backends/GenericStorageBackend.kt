package kvasir.plugins.kg.clickhouse.backends

import graphql.language.Document
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import io.smallrye.mutiny.Uni
import jakarta.inject.Singleton
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.changes.ChangeRequestTxBuffer
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.graphql.ConvertToSQLResolver
import kvasir.plugins.kg.clickhouse.graphql.SQLConvertor
import kvasir.plugins.kg.clickhouse.graphql.SQLConvertorMode
import kvasir.plugins.kg.clickhouse.specs.DATA_COLUMNS
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.plugins.kg.clickhouse.specs.RDFDatasetQuadInsertSpec
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant

@Singleton
class GenericStorageBackend(
    clickhouseClient: ClickhouseClient,
    private val convertToSQLResolver: ConvertToSQLResolver,
    @ConfigProperty(name = "kvasir.plugins.kg.clickhouse.storage-buffer", defaultValue = "100000")
    private val bufferSize: Int,
) : AbstractStorageBackend(DATA_TABLE, DATA_COLUMNS, clickhouseClient) {


    override fun process(buffer: ChangeRequestTxBuffer): Uni<Void> {
        return buffer.stream().group().intoLists().of(bufferSize)
            .onItem().transformToUniAndConcatenate { records ->
                clickhouseClient.insert(RDFDatasetQuadInsertSpec(databaseFromPodId(buffer.request.podId)), records)
                    .chain { _ ->
                        // Remove the stored records from the tx buffer
                        buffer.remove(records, true)
                    }
            }
            .skipToLast()
    }

    override fun datafetcher(podId: String, context: Map<String, Any>, atTimestamp: Instant?): DataFetcher<Any>? {
        return convertToSQLResolver.getDatafetcher(podId, context, atTimestamp)
    }

    override fun count(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?,
        env: DataFetchingEnvironment
    ): Uni<Long> {
        val databaseName = databaseFromPodId(podId)
        val sqlConvertor = SQLConvertor(
            context,
            atTimestamp,
            databaseName,
            DATA_TABLE,
            env.field,
            env.fieldDefinition,
            env,
            SQLConvertorMode.COUNT
        )
        val (sql, _) = sqlConvertor.toSQL()
        return clickhouseClient.query(GenericQuerySpec(databaseName, DATA_TABLE, listOf("totalCount")), sql)
            .map { result ->
                result[0]["totalCount"].toString().toLong()
            }
    }

    override fun resultToChangeRecord(result: Map<String, Any>): List<ChangeRecord> {
        return listOf(
            ChangeRecord(
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
        )
    }

    override fun prepareQuery(request: QueryRequest, queryDocument: Document): Document {
        // As this storage backend is the default one, no query document modifications are required.
        return queryDocument
    }
}