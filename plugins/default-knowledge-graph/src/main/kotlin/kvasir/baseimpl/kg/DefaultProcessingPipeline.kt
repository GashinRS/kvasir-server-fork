package kvasir.baseimpl.kg

import com.dashjoin.jsonata.Jsonata.jsonata
import io.quarkus.arc.All
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeProcessor
import kvasir.definitions.kg.changes.ChangeRequestTxBuffer
import kvasir.definitions.kg.exceptions.ChangeAssertionException
import kvasir.definitions.kg.exceptions.InvalidTemplateException
import kvasir.definitions.kg.exceptions.SHACLValidationException
import kvasir.definitions.kg.slices.SliceStore
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirNamedGraphs
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.reactive.skipToLast
import kvasir.utils.rdf.RDFTransformer
import kvasir.utils.shacl.GraphQL2SHACL
import kvasir.utils.shacl.RDF4JSHACLValidator
import kvasir.utils.shacl.SHACLValidationFailure
import kvasir.utils.shacl.SHACLValidator
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class EvaluateAssertions(
    private val parent: KnowledgeGraph,
    @ConfigProperty(name = "kvasir.changes.processing.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int,
) : ChangeProcessor {
    override fun process(buffer: ChangeRequestTxBuffer): Uni<Void> {
        val request = buffer.request
        return Multi.createFrom().iterable(request.assert)
            .onItem()
            .transformToUni { assertion ->
                val q = QueryRequest(
                    context = request.context,
                    podId = request.podId,
                    sliceId = request.sliceId,
                    query = assertion.queryStr
                )
                parent.query(q)
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
    }

}

@ApplicationScoped
class MaterializeS3References(
    @All
    private val referenceLoaders: MutableList<ReferenceLoader>,
    @ConfigProperty(name = "kvasir.changes.processing.ref-handling-buffer", defaultValue = "50000")
    private val referenceHandlingBuffer: Int,
) : ChangeProcessor {
    override fun process(buffer: ChangeRequestTxBuffer): Uni<Void> {
        val request = buffer.request
        return if (request.insertFromRefs.isNotEmpty() || request.deleteFromRefs.isNotEmpty()) {
            // Delete from external sources
            Multi.createFrom().iterable(request.deleteFromRefs)
                .onItem().transformToMultiAndConcatenate { ref ->
                    loadReference(request.podId, ref)
                }
                .group().intoLists().of(referenceHandlingBuffer)
                .onItem().transformToUni { deleteTuples ->
                    buffer.add(
                        deleteTuples.map {
                            ChangeRecord(
                                request.id,
                                buffer.requestTimestamp,
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
                            loadReference(request.podId, ref)
                        }
                        .group().intoLists().of(referenceHandlingBuffer)
                        .onItem().transformToUni { insertTuples ->
                            buffer.add(
                                insertTuples.map {
                                    ChangeRecord(
                                        request.id,
                                        buffer.requestTimestamp,
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
    }

    /**
     * Load a reference from an external source and return it as a Mutiny stream (Multi).
     */

    private fun loadReference(podId: String, reference: Map<String, Any>): Multi<RDFStatement> {
        return referenceLoaders.firstOrNull { loader -> loader.isSupported(reference) }
            ?.loadReference(podId, reference)
            ?: Multi.createFrom().failure(RuntimeException("Unsupported reference type: $reference"))
    }

}

@ApplicationScoped
class MaterializeRecords(
    private val kg: KnowledgeGraph
) : ChangeProcessor {
    override fun process(buffer: ChangeRequestTxBuffer): Uni<Void> {
        // Process embedded inserts/deletes
        val request = buffer.request
        return bindWhere(request)
            .chain { bindings ->
                // Delete the specified records
                val deleteJsonLd = materializeRecords(request, request.delete, bindings)
                val deleteStatements = RDFTransformer.toStatements(deleteJsonLd)
                buffer.add(
                    deleteStatements.map {
                        ChangeRecord(
                            request.id, buffer.requestTimestamp,
                            ChangeRecordType.DELETE, it
                        )
                    }
                )
                    .chain { _ ->
                        val insertJsonLd = materializeRecords(request, request.insert, bindings)
                        val insertStatements = RDFTransformer.toStatements(insertJsonLd)
                        buffer.add(
                            insertStatements.map {
                                ChangeRecord(
                                    request.id, buffer.requestTimestamp,
                                    ChangeRecordType.INSERT, it
                                )
                            }
                        )
                    }
            }
    }

    private fun bindWhere(request: ChangeRequest): Uni<QueryResult> {
        return if (request.with == null) {
            Uni.createFrom().item(QueryResult(data = emptyMap()))
        } else {
            val q = QueryRequest(
                context = request.context,
                podId = request.podId,
                sliceId = request.sliceId,
                targetGraphs = setOf(),
                query = request.with!!
            )
            kg.query(q)
                .onFailure().recoverWithItem { err ->
                    QueryResult(
                        data = emptyMap(),
                        errors = listOf(mapOf("message" to (err.message ?: "")))
                    )
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
            else -> throw InvalidTemplateException("Invalid template result: $transformedData")
        }
    }
}

@ApplicationScoped
class SliceSHACLValidator(private val sliceStore: SliceStore) : ChangeProcessor {
    override fun process(buffer: ChangeRequestTxBuffer): Uni<Void> {
        return buffer.request.sliceId?.let { sliceId ->
            // Load Slice schema
            sliceStore.getById(buffer.request.podId, sliceId)
                .chain { sliceSpec ->
                    if (sliceSpec != null) {
                        val shaclGen = GraphQL2SHACL(sliceSpec.schema, sliceSpec.context)
                        val insertValidator = RDF4JSHACLValidator(shaclGen.getInsertSHACL())
                        val deleteValidator = RDF4JSHACLValidator(shaclGen.getDeleteSHACL())
                        // Validate inserts
                        buffer.stream(ChangeRecordType.INSERT).validate(insertValidator)
                            .chain { _ ->
                                // Validate deletes
                                buffer.stream(ChangeRecordType.DELETE).validate(deleteValidator)
                            }
                    } else {
                        Uni.createFrom().voidItem()
                    }
                }
        } ?: Uni.createFrom().voidItem()
    }

}

// TODO: this should work for large collections of change records as well
internal fun Multi<ChangeRecord>.validate(validator: SHACLValidator): Uni<Void> {
    return this.collect().asSet().chain { records ->
        try {
            if(records.isNotEmpty()) {
                validator.validate(records.map { it.statement })
            }
            Uni.createFrom().voidItem()
        } catch (e: SHACLValidationFailure) {
            Uni.createFrom()
                .failure(SHACLValidationException("Change request does not match the Slice input specification!", e))
        }
    }
}