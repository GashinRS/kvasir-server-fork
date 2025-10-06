package kvasir.baseimpl.kg

import com.dashjoin.jsonata.Jsonata.jsonata
import io.quarkus.arc.All
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.json.Json
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeProcessor
import kvasir.definitions.kg.changes.ChangeReportStatusEntry
import kvasir.definitions.kg.changes.ChangeRequestTxBuffer
import kvasir.definitions.kg.exceptions.ChangeAssertionException
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.kg.exceptions.InvalidTemplateException
import kvasir.definitions.kg.slices.SliceStoreFactory
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirNamedGraphs
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.reactive.skipToLast
import kvasir.utils.graphql.ChangeRequestValidator
import kvasir.utils.idgen.getTimestamp
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class EvaluateAssertions(
    private val parent: KnowledgeGraph,
    @ConfigProperty(name = "kvasir.changes.processing.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int,
) : ChangeProcessor {
    override fun process(buffer: ChangeRequestTxBuffer): Uni<ChangeReportStatusEntry?> {
        val startTs = System.currentTimeMillis()
        Log.debug("Evaluating assertions for change request ${buffer.request.id}...")
        val request = buffer.request
        return Multi.createFrom().iterable(request.assert)
            .onItem()
            .transformToUni { assertion ->
                val q = QueryRequest(
                    context = request.context,
                    requestingUser = buffer.request.requestingUser,
                    podId = request.podId,
                    sliceId = request.sliceId,
                    query = assertion.queryStr
                )
                parent.query(q).toUni()
                    .onFailure().recoverWithItem { err ->
                        QueryResult(
                            data = emptyMap(),
                            errors = listOf(mapOf("message" to (err.message ?: "")))
                        )
                    }
                    .chain { result ->
                        if (result.errors?.isNotEmpty() == true) {
                            Uni.createFrom()
                                .failure(ChangeAssertionException("Error executing assertion: ${result.errors}"))
                        } else {
                            when (assertion.type) {
                                KvasirVocab.AssertEmptyResult -> {
                                    when {
                                        result.data == null -> Uni.createFrom()
                                            .failure(IllegalArgumentException("Invalid assertion query: ${assertion.queryStr}"))

                                        result.data!!.isNotEmpty() -> Uni.createFrom()
                                            .failure(ChangeAssertionException("Assertion failed: results exists for '${assertion.queryStr}'"))

                                        else -> Uni.createFrom().voidItem()
                                    }
                                }

                                KvasirVocab.AssertNonEmptyResult -> {
                                    when {
                                        result.data == null -> Uni.createFrom()
                                            .failure(IllegalArgumentException("Invalid assertion query: ${assertion.queryStr}"))

                                        result.data!!.isEmpty() -> Uni.createFrom()
                                            .failure(ChangeAssertionException("Assertion failed: no results for '${assertion.queryStr}'"))

                                        else -> Uni.createFrom().voidItem()
                                    }
                                }

                                else -> Uni.createFrom()
                                    .failure(IllegalArgumentException("Unsupported assertion type: ${assertion.type}"))
                            }
                        }
                    }
            }
            .merge(assertionCheckingParallelism)
            .skipToLast()
            .map {
                val log =
                    "Finished evaluating assertions (${request.assert.size}) for change request ${buffer.request.id} in ${System.currentTimeMillis() - startTs} ms"
                Log.debug(log)
                ChangeReportStatusEntry(
                    code = ChangeStatusCode.PROCESSING,
                    message = log
                ).takeIf { request.assert.isNotEmpty() }
            }
    }

}

@ApplicationScoped
class MaterializeS3References(
    @All
    private val referenceLoaders: MutableList<ReferenceLoader>,
    @ConfigProperty(name = "kvasir.changes.processing.ref-handling-buffer", defaultValue = "50000")
    private val referenceHandlingBuffer: Int,
) : ChangeProcessor {
    override fun process(buffer: ChangeRequestTxBuffer): Uni<ChangeReportStatusEntry?> {
        val startTs = System.currentTimeMillis()
        Log.debug("Processing external references for change request ${buffer.request.id}...")
        val request = buffer.request
        val deletedS3Objects = mutableListOf<String>()
        val insertedS3Objects = mutableListOf<String>()
        return if (request.insertFromRefs.isNotEmpty() || request.deleteFromRefs.isNotEmpty()) {
            // Delete from external sources
            Multi.createFrom().iterable(request.deleteFromRefs)
                .onItem().transformToMultiAndConcatenate { ref ->
                    extractObject(ref)?.let { deletedS3Objects.add(it) }
                    loadReference(request.podId, ref)
                }
                .group().intoLists().of(referenceHandlingBuffer)
                .onItem().transformToUni { deleteTuples ->
                    buffer.add(
                        deleteTuples.map {
                            ChangeRecord(
                                request.id,
                                request.getTimestamp(),
                                ChangeRecordType.DELETE,
                                it
                            )
                        })
                }
                .concatenate()
                .skipToLast()
                .chain { _ ->
                    // Insert from external sources
                    Multi.createFrom().iterable(request.insertFromRefs)
                        .onItem().transformToMultiAndConcatenate { ref ->
                            extractObject(ref)?.let { insertedS3Objects.add(it) }
                            loadReference(request.podId, ref)
                        }
                        .group().intoLists().of(referenceHandlingBuffer)
                        .onItem().transformToUni { insertTuples ->
                            buffer.add(
                                insertTuples.map {
                                    ChangeRecord(
                                        request.id,
                                        request.getTimestamp(),
                                        ChangeRecordType.INSERT,
                                        it
                                    )
                                }
                            )
                        }
                        .concatenate()
                        .skipToLast()
                }
        } else {
            Uni.createFrom().voidItem()
        }
            .map {
                val report = Json.encode(mapOf("insert_refs" to insertedS3Objects, "delete_refs" to deletedS3Objects))
                val log =
                    "Finished processing external references for change request ${buffer.request.id} in ${System.currentTimeMillis() - startTs} ms. Details: $report"
                Log.debug(log)
                ChangeReportStatusEntry(
                    code = ChangeStatusCode.PROCESSING,
                    message = log
                ).takeIf { insertedS3Objects.isNotEmpty() || deletedS3Objects.isNotEmpty() }
            }
    }

    /**
     * Load a reference from an external source and return it as a Mutiny stream (Multi).
     */

    private fun loadReference(podId: String, reference: Map<String, Any>): Multi<RDFStatement> {
        return referenceLoaders.firstOrNull { loader -> loader.isSupported(reference) }
            ?.loadReference(podId, reference)
            ?: Multi.createFrom().failure(RuntimeException("Unsupported reference type: $reference"))
    }

    private fun extractObject(reference: Map<String, Any>): String? {
        return if (reference[JsonLdKeywords.type] == KvasirVocab.S3Reference) reference[KvasirVocab.key] as String? else null
    }

}

@ApplicationScoped
class MaterializeRecords(
    private val kg: KnowledgeGraph,
    private val sliceStoreFactory: Instance<SliceStoreFactory>
) : ChangeProcessor {
    override fun process(buffer: ChangeRequestTxBuffer): Uni<ChangeReportStatusEntry?> {
        val startTs = System.currentTimeMillis()
        Log.debug("Processing with clauses for change request ${buffer.request.id}...")
        // Process embedded inserts/deletes
        val request = buffer.request
        var deleteStatementsCount = 0
        var insertStatementsCount = 0
        return bindWhere(request)
            .chain { bindings ->
                // Delete the specified records
                val deleteJsonLd = materializeRecords(request, request.delete, bindings)
                val deleteStatements = RDFTransformer.toStatements(deleteJsonLd)
                deleteStatementsCount = deleteStatements.size
                buffer.add(
                    deleteStatements.map {
                        ChangeRecord(
                            request.id, request.getTimestamp(),
                            ChangeRecordType.DELETE, it
                        )
                    }
                )
                    .chain { _ ->
                        val insertJsonLd = materializeRecords(request, request.insert, bindings)
                        val insertStatements = RDFTransformer.toStatements(insertJsonLd)
                        insertStatementsCount = insertStatements.size
                        buffer.add(
                            insertStatements.map {
                                ChangeRecord(
                                    request.id, request.getTimestamp(),
                                    ChangeRecordType.INSERT, it
                                )
                            }
                        )
                    }
            }
            .map {
                val log =
                    "Finished processing with clauses for change request ${buffer.request.id} in ${System.currentTimeMillis() - startTs} ms. Materialized $insertStatementsCount inserts and $deleteStatementsCount deletes."
                Log.debug(log)
                ChangeReportStatusEntry(
                    code = ChangeStatusCode.PROCESSING,
                    message = log
                ).takeIf { insertStatementsCount + deleteStatementsCount > 0 }
            }
    }

    private fun bindWhere(request: ChangeRequest): Uni<QueryResult> {
        return if (request.with == null) {
            Uni.createFrom().item(QueryResult(data = emptyMap()))
        } else {
            // For change requests on a Slice, load the Slice schema
            (request.sliceId?.let { sliceId ->
                sliceStoreFactory.get().getSliceStore(sliceId).findById(sliceId)
                    .onItem().ifNull().failWith(IllegalArgumentException("Slice not found: $sliceId"))
                    .onItem().ifNotNull().transform { it!! }
            } ?: Uni.createFrom().nullItem())
                .chain { slice ->
                    val q = QueryRequest(
                        context = slice?.context ?: request.context,
                        requestingUser = request.requestingUser,
                        podId = request.podId,
                        sliceId = request.sliceId,
                        query = request.with!!,
                        predefinedSchema = slice?.schema
                    )
                    kg.query(q).toUni()
                        .onFailure().recoverWithItem { err ->
                            QueryResult(
                                data = emptyMap(),
                                errors = listOf(mapOf("message" to (err.message ?: "")))
                            )
                        }
                }
        }
    }

    private fun materializeRecords(
        request: ChangeRequest,
        records: List<Any>,
        bindings: QueryResult
    ): List<Map<String, Any>> {
        return records.flatMap { record ->
            when (record) {
                is Map<*, *> -> listOf(record as Map<String, Any>)
                is String -> {
                    if (record == "*") {
                        // Return bindings as is
                        bindings.toJsonLD(request.context)
                            .find { it[JsonLdKeywords.id] == KvasirNamedGraphs.queryResultDataGraph }?.let {
                                val graph = it[JsonLdKeywords.graph]
                                if (graph is List<*>) {
                                    graph.map { it as Map<String, Any> }
                                } else {
                                    listOf(graph as Map<String, Any>)
                                }
                            } ?: listOf()
                    } else {
                        transformTemplate(
                            record,
                            bindings.data ?: emptyMap()
                        ).map { JsonLdHelper.toCompactFQForm(it.plus(JsonLdKeywords.context to request.context)) }
                    }
                }

                else -> throw InvalidTemplateException("Unsupported insert type: $record")
            }
        }
    }

    private fun transformTemplate(template: String, bindings: Map<String, Any>): List<Map<String, Any>> {
        return when (val transformedData = jsonata(template).evaluate(bindings)) {
            is List<*> -> transformedData.map { it as Map<String, Any> }
            is Map<*, *> -> listOf(transformedData as Map<String, Any>)
            null -> emptyList()           // No Match
            else -> throw InvalidTemplateException("Invalid template result: $transformedData")
        }
    }
}

@ApplicationScoped
class SliceGraphQLBasedValidator(private val sliceStoreFactory: SliceStoreFactory) : ChangeProcessor {
    override fun process(buffer: ChangeRequestTxBuffer): Uni<ChangeReportStatusEntry?> {
        return buffer.request.sliceId?.let { sliceId ->
            val startTs = System.currentTimeMillis()
            Log.debug("Validating change request ${buffer.request.id} against Slice GraphQL schema...")
            // Load Slice schema
            sliceStoreFactory.getSliceStore(buffer.request.podId).findById(sliceId)
                .chain { sliceSpec ->
                    if (sliceSpec != null) {
                        buffer.stream().collect().asSet().chain { records ->
                            val validator = ChangeRequestValidator(records, sliceSpec.schema, sliceSpec.context)
                            try {
                                validator.validate()
                                Uni.createFrom().voidItem()
                            } catch (t: Throwable) {
                                Uni.createFrom().failure(t)
                            }
                        }
                    } else {
                        Uni.createFrom()
                            .failure(InvalidChangeRequestException("Cannot validate change request: no spec found for Slice '$sliceId'!"))
                    }
                }
                .map {
                    val log =
                        "Finished validating change request ${buffer.request.id} against Slice GraphQL schema in ${System.currentTimeMillis() - startTs} ms"
                    Log.debug(log)
                    ChangeReportStatusEntry(code = ChangeStatusCode.PROCESSING, message = log)
                }
        } ?: Uni.createFrom().nullItem()
    }

}