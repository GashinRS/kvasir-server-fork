package kvasir.plugins.kg.clickhouse

import graphql.ExceptionWhileDataFetching
import graphql.ExecutionResult
import graphql.schema.DataFetcher
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolver
import io.quarkus.arc.All
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.json.JsonObject
import jakarta.inject.Singleton
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.ChangeResult
import kvasir.definitions.kg.HistoryRequest
import kvasir.definitions.kg.HistoryResult
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.ReferenceLoader
import kvasir.definitions.messaging.Channels
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.graphql.ConvertToSQLResolver
import kvasir.plugins.kg.clickhouse.graphql.NoResultsException
import kvasir.plugins.kg.clickhouse.graphql.RDFClassTypeResolver
import kvasir.plugins.kg.clickhouse.specs.KGTypeQuerySpec
import kvasir.plugins.kg.clickhouse.specs.META_DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.MetadataInsertRecordSpec
import kvasir.plugins.kg.clickhouse.specs.RDFDatasetQuadInsertSpec
import kvasir.utils.kg.AbstractKnowledgeGraph
import kvasir.utils.kg.KGPropertyKind
import kvasir.utils.kg.KGType
import kvasir.utils.kg.MetadataEntry
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import kotlin.collections.component1
import kotlin.collections.component2

@Singleton
class ClickhouseKnowledgeGraph(
    @All
    private val referenceLoaders: MutableList<ReferenceLoader>,
    private val clickhouseClient: ClickhouseClient,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.ref-handling-buffer", defaultValue = "50000")
    private val refHandlingBuffer: Int,
    @Channel(Channels.OUTBOX_PUBLISH)
    private val outboxEmitter: MutinyEmitter<ChangeResult>
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

    override fun deleteGraph(podId: String, graph: String): Uni<Void> {
        return clickhouseClient.execute(
            "ALTER TABLE $podId.data DELETE WHERE graph = '$graph'",
            databaseFromPodId(podId)
        )
    }

//    override fun buildDataLoaderRegistry(podId: String, context: Map<String, Any>): DataLoaderRegistry {
//        return SubOptimalResolver.getDataLoaderRegistry(podId, clickhouseClient, context)
//    }

    override fun buildDatafetcher(
        podId: String,
        context: Map<String, Any>
    ): DataFetcher<Any> {
        return ConvertToSQLResolver.getDatafetcher(clickhouseClient, podId, context)
    }

    override fun buildUnionTypeResolver(
        podId: String,
        unionType: GraphQLUnionType,
        context: Map<String, Any>
    ): TypeResolver {
        return RDFClassTypeResolver(context)
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

    override fun mapExecutionResult(request: QueryRequest, result: ExecutionResult): QueryResult {
        // Use NonNullableValueCoercedAsNullException to filter out paths that have no results
        val skipPositions = result.errors
            .filter { it is ExceptionWhileDataFetching && it.exception is NoResultsException }
            .groupBy { it.path.first() }
            .mapValues { err -> err.value.map { it.path.drop(1).first() }.toSet() }
        val filteredData = result?.getData<Map<String, Any>>()?.mapValues { entryPoint ->
            skipPositions[entryPoint.key]?.let { positions ->
                val values = entryPoint.value as List<Any>
                values.mapIndexed { index, any -> if (index in positions) null else any }
                    .filterNotNull()
            } ?: entryPoint.value
        }
        return QueryResult(
            data = filteredData,
            errors = result.errors.filterNot { it is ExceptionWhileDataFetching && it.exception is NoResultsException }
                .map { JsonObject.mapFrom(it).map }.takeIf { it.isNotEmpty() }
        )
    }

    override fun history(request: HistoryRequest): Uni<HistoryResult> {
        TODO("Not yet implemented")
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

}

internal fun databaseFromPodId(podId: String): String {
    return podId
}

