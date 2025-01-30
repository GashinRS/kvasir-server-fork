package kvasir.plugins.kg.clickhouse.backends

import graphql.language.Document
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeRequestTxBuffer
import kvasir.definitions.kg.changes.StorageBackend
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.specs.KGTypeQuerySpec
import kvasir.plugins.kg.clickhouse.specs.META_DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.MetadataInsertRecordSpec
import kvasir.plugins.kg.clickhouse.utils.databaseFromPodId
import java.time.Instant

@ApplicationScoped
class MetadataStorageBackend(
    private val clickhouseClient: ClickhouseClient
) : StorageBackend, TypeRegistry {
    override fun get(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>> {
        // Always returns empty page, as this backend never stores actual data (but type info metadata)
        return Uni.createFrom().item(PagedResult(items = emptyList()))
    }

    override fun stream(request: ChangeRecordRequest): Multi<ChangeRecord> {
        // Always returns empty stream, as this backend never stores actual data (but type info metadata)
        return Multi.createFrom().empty()
    }

    override fun rollback(request: ChangeRollbackRequest): Uni<Void> {
        // TODO: technically we should remove type info that was added by the change request.
        return Uni.createFrom().voidItem()
    }

    override fun prepareQuery(request: QueryRequest, queryDocument: Document): Document {
        return queryDocument
    }

    override fun datafetcher(podId: String, context: Map<String, Any>, atTimestamp: Instant?): DataFetcher<Any>? {
        return null
    }

    override fun count(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?,
        env: DataFetchingEnvironment
    ): Uni<Long> {
        return Uni.createFrom().item(0)
    }

    override fun process(buffer: ChangeRequestTxBuffer): Uni<Void> {
        val targetPodId = buffer.request.podId
        return buffer.stream().filter { it.type == ChangeRecordType.INSERT }.map { it.statement }.collect().asList()
            .chain { statements ->
                // Transform
                val typeUrisToSubjects =
                    statements.filter { it.predicate == RDFVocab.type }.groupBy { it.`object` as String }
                        .mapValues { statementsByType ->
                            statementsByType.component2().map { it.subject }
                        }
                // Reverse mapping
                val subjectsToTypeUris = statements.filter { it.predicate == RDFVocab.type }.groupBy { it.subject }
                    .mapValues { statementsBySubject ->
                        statementsBySubject.component2().map { it.`object` as String }
                    }

                // Add null key to typeUrisToSubjects for all subjects without an explicit type...
                val metadataEntries = typeUrisToSubjects.plus(
                    null to statements.map { it.subject }.toSet()
                        .minus(subjectsToTypeUris.keys)
                ).entries.flatMap { (typeUri, subjects) ->
                    // ... and then generate MetadataEntry instances
                    statements.filter { it.subject in subjects && it.predicate != RDFVocab.type }.distinct()
                        .flatMap { statement ->
                            val typeRefs = statement.dataType?.let { listOf(it) }
                                ?: subjectsToTypeUris[statement.`object` as String]?.toList()
                                ?: listOf(RDFSVocab.Resource)
                            typeRefs.map { typeRef ->
                                MetadataEntry(
                                    typeUri = typeUri ?: RDFSVocab.Resource,
                                    propertyUri = statement.predicate,
                                    propertyKind = if (statement.dataType != null) KGPropertyKind.Literal else KGPropertyKind.IRI,
                                    propertyRef = typeRef
                                )
                            }
                        }
                }
                clickhouseClient.insert(MetadataInsertRecordSpec(databaseFromPodId(targetPodId)), metadataEntries)
            }
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

}