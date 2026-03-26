package kvasir.baseimpl.kg

import com.dashjoin.jsonata.Jsonata.jsonata
import io.quarkus.arc.All
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.Reference
import kvasir.definitions.kg.exceptions.ChangeAssertionException
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.kg.exceptions.InvalidTemplateException
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.*
import kvasir.definitions.reactive.skipToLast
import kvasir.utils.graphql.ChangeRequestValidator
import kvasir.utils.idgen.getTimestamp
import kvasir.utils.rdf.RDFTransformer

private const val ASSERTION_CHECKING_PARALLELISM = 4

@ApplicationScoped
class EvaluateAssertions(
    private val parent: KnowledgeGraph
) {
    fun process(request: ChangeRequest): Uni<Void> {
        val startTs = System.currentTimeMillis()
        Log.debug("Evaluating assertions for change request ${request.id}...")
        return Multi.createFrom().iterable(request.assert)
            .onItem()
            .transformToUni { assertion ->
                val q = QueryRequest(
                    context = request.context,
                    atChangeId = request.previousChangeId,
                    requestingUser = request.requestingUser,
                    podId = request.podId,
                    sliceId = request.sliceId,
                    query = assertion.query
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
                                            .failure(IllegalArgumentException("Invalid assertion query: ${assertion.query}"))

                                        result.data!!.isNotEmpty() -> Uni.createFrom()
                                            .failure(ChangeAssertionException("Assertion failed: results exists for '${assertion.query}'"))

                                        else -> Uni.createFrom().voidItem()
                                    }
                                }

                                KvasirVocab.AssertNonEmptyResult -> {
                                    when {
                                        result.data == null -> Uni.createFrom()
                                            .failure(IllegalArgumentException("Invalid assertion query: ${assertion.query}"))

                                        result.data!!.isEmpty() -> Uni.createFrom()
                                            .failure(ChangeAssertionException("Assertion failed: no results for '${assertion.query}'"))

                                        else -> Uni.createFrom().voidItem()
                                    }
                                }

                                else -> Uni.createFrom()
                                    .failure(IllegalArgumentException("Unsupported assertion type: ${assertion.type}"))
                            }
                        }
                    }
            }
            .merge(ASSERTION_CHECKING_PARALLELISM)
            .skipToLast()
            .invoke { _ ->
                Log.debug("Finished evaluating assertions (${request.assert.size}) for change request ${request.id} in ${System.currentTimeMillis() - startTs} ms")
            }
    }

}

@ApplicationScoped
class MaterializeS3References(
    @All
    private val referenceLoaders: MutableList<ReferenceLoader>
) {
    fun process(request: ChangeRequest): Multi<ChangeRecord> {
        val startTs = System.currentTimeMillis()
        Log.debug("Processing and storing external references for change request ${request.id}...")
        return if (request.insertFromRefs.isNotEmpty() || request.deleteFromRefs.isNotEmpty()) {
            // Concatenate RDF statement streams of...
            Multi.createBy().concatenating().streams(
                // ... delete refs
                Multi.createFrom().iterable(request.deleteFromRefs)
                    .onItem().transformToMultiAndConcatenate { ref -> loadReference(request.podId, ref) }
                    .map { statement ->
                        ChangeRecord(
                            request.changeId!!,
                            request.getTimestamp(),
                            ChangeRecordType.DELETE,
                            statement
                        )
                    },
                // ... insert refs
                Multi.createFrom().iterable(request.insertFromRefs)
                    .onItem().transformToMultiAndConcatenate { ref -> loadReference(request.podId, ref) }
                    .map { statement ->
                        ChangeRecord(
                            request.changeId!!,
                            request.getTimestamp(),
                            ChangeRecordType.INSERT,
                            statement
                        )
                    }
            )
        } else {
            Multi.createFrom().empty()
        }
            .onCompletion().invoke {
                Log.debug("Finished processing external references for change request ${request.id} in ${System.currentTimeMillis() - startTs} ms.")
            }
    }

    /**
     * Load a reference from an external source and return it as a Mutiny stream (Multi).
     */

    private fun loadReference(podId: String, reference: Reference): Multi<RDFStatement> {
        return referenceLoaders.firstOrNull { loader -> loader.isSupported(reference) }
            ?.loadReference(podId, reference)
            ?: Multi.createFrom().failure(RuntimeException("Unsupported reference type: $reference"))
    }

}

/**
 * Materializes records for a ChangeRequest (read from JSON-LD or generated via with-clauses).
 *
 * TODO: Not future proof, as it takes all records in-memory (or performs a large query for evaluating with-clauses)
 */
@ApplicationScoped
class MaterializeRecords(
    private val kg: KnowledgeGraph,
    private val repositoryFactory: RepositoryFactory
) {
    fun process(request: ChangeRequest): Uni<out List<ChangeRecord>> {
        val startTs = System.currentTimeMillis()
        Log.debug("Materializing change records for change request ${request.id}...")
        // Process embedded inserts/deletes
        val request = request
        var deleteStatementsCount = 0
        var insertStatementsCount = 0
        return bindWhere(request)
            .map { bindings ->
                val records = mutableListOf<ChangeRecord>()
                // Delete the specified records
                val deleteJsonLd = materializeRecords(request, request.delete, bindings)
                val deleteStatements = RDFTransformer.toStatements(deleteJsonLd, request.sliceId ?: request.podId)
                deleteStatementsCount = deleteStatements.size
                records.addAll(
                    deleteStatements.map {
                        ChangeRecord(
                            request.changeId!!, request.getTimestamp(),
                            ChangeRecordType.DELETE, it
                        )
                    }
                )
                val insertJsonLd = materializeRecords(request, request.insert, bindings)
                val insertStatements = RDFTransformer.toStatements(insertJsonLd, request.sliceId ?: request.podId)
                insertStatementsCount = insertStatements.size
                records.addAll(
                    insertStatements.map {
                        ChangeRecord(
                            request.changeId!!, request.getTimestamp(),
                            ChangeRecordType.INSERT, it
                        )
                    }
                )
                records
            }
            .invoke { _ ->
                Log.debug("Finished materializing change request ${request.id} in ${System.currentTimeMillis() - startTs} ms. Materialized $insertStatementsCount inserts and $deleteStatementsCount deletes.")
            }
    }

    private fun bindWhere(request: ChangeRequest): Uni<QueryResult> {
        return if (request.with == null) {
            Uni.createFrom().item(QueryResult(data = emptyMap()))
        } else {
            Log.debug("Binding 'with' clauses for change request ${request.id}...")
            // For change requests on a Slice, load the Slice schema
            (request.sliceId?.let { sliceId ->
                repositoryFactory.getRepository(Slice::class, request.podId).findById(sliceId)
                    .onItem().ifNull().failWith(IllegalArgumentException("Slice not found: $sliceId"))
                    .onItem().ifNotNull().transform { it!! }
            } ?: Uni.createFrom().nullItem())
                .chain { slice ->
                    val q = QueryRequest(
                        context = slice?.context ?: request.context,
                        atChangeId = request.previousChangeId,
                        requestingUser = request.requestingUser,
                        podId = request.podId,
                        sliceId = request.sliceId,
                        query = request.with!!,
                        predefinedSchema = slice?.schema
                    )
                    kg.query(q).toUni()
                        .chain { result ->
                            if (result.errors?.isNotEmpty() == true) {
                                Uni.createFrom()
                                    .failure(IllegalArgumentException("Error executing 'with' clause: ${result.errors}"))
                            } else {
                                Uni.createFrom().item(result)
                            }
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

/**
 * Validates a collection of change records (mutation) according to the Slice schema.
 *
 * TODO: Warning, this is not future-proof as the implementation must see the full recordset at once (so it needs to be loaded in-memory).
 * A stream capable implementation would be better.
 */
@ApplicationScoped
class SliceGraphQLBasedValidator(private val repositoryFactory: RepositoryFactory) {
    fun process(request: ChangeRequest, records: Collection<ChangeRecord>): Uni<Void> {
        return request.sliceId?.let { sliceId ->
            val startTs = System.currentTimeMillis()
            Log.debug("Validating change request ${request.id} against Slice GraphQL schema...")
            // Load Slice schema
            repositoryFactory.getRepository(Slice::class, request.podId).findById(sliceId)
                .chain { sliceSpec ->
                    if (sliceSpec != null) {

                        val validator = ChangeRequestValidator(records, sliceSpec.schema, sliceSpec.context)
                        try {
                            validator.validate()
                            Uni.createFrom().voidItem()
                        } catch (t: Throwable) {
                            Uni.createFrom().failure(t)
                        }
                    } else {
                        Uni.createFrom()
                            .failure(InvalidChangeRequestException("Cannot validate change request: no spec found for Slice '$sliceId'!"))
                    }
                }
                .invoke { _ ->
                    Log.debug("Finished validating change request ${request.id} against Slice GraphQL schema in ${System.currentTimeMillis() - startTs} ms")
                }
        } ?: Uni.createFrom().nullItem()
    }

}