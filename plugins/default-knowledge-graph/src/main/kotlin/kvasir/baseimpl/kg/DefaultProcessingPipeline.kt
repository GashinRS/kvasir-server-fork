package kvasir.baseimpl.kg

import com.dashjoin.jsonata.Jsonata.jsonata
import io.quarkus.arc.All
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.changes.AssertionPhase
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.Reference
import kvasir.definitions.kg.exceptions.ChangeAssertionException
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.kg.exceptions.InvalidTemplateException
import kvasir.definitions.kg.slices.Slice
import kvasir.definitions.kg.slices.tryReadingEmbeddedSDL
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.*
import kvasir.definitions.reactive.skipToLast
import kvasir.utils.graphql.ChangeRequestValidator
import kvasir.utils.idgen.getTimestamp
import kvasir.utils.rdf.RDFTransformer

private const val ASSERTION_CHECKING_PARALLELISM = 4
private const val PAGINATION_EXTENSION_ID = "pagination"

@ApplicationScoped
class EvaluateAssertions(
    private val parent: KnowledgeGraph,
    private val repositoryFactory: RepositoryFactory
) {
    fun process(request: ChangeRequest, phase: AssertionPhase = AssertionPhase.PRE): Uni<Void> {
        val assertions = request.assert.filter { it.phase == phase }
        if (assertions.isEmpty()) return Uni.createFrom().voidItem()
        val startTs = System.currentTimeMillis()
        Log.debug("Evaluating ${phase.name} assertions for change request ${request.id}...")
        // For change requests on a Slice, load the Slice schema
        return (request.sliceId?.let { sliceId ->
            repositoryFactory.getVersionedRepository(Slice::class, request.podId)
                .run { request.sliceTag?.let { this.findById(sliceId, it) } ?: this.findDefaultForId(sliceId) }
                .onItem().ifNull().failWith(IllegalArgumentException("Slice not found: $sliceId"))
                .onItem().ifNotNull().transform { it!! }
        } ?: Uni.createFrom().nullItem())
            .chain { slice ->
                // PRE assertions query before the change; POST assertions query at the current changeId (includes just-written data)
                val atChangeId = if (phase == AssertionPhase.PRE) request.previousChangeId else request.id
                Multi.createFrom().iterable(assertions)
                    .onItem()
                    .transformToUni { assertion ->
                        val q = QueryRequest(
                            context = slice?.context ?: request.context,
                            atChangeId = atChangeId,
                            requestingUser = request.requestingUser,
                            podId = request.podId,
                            sliceId = request.sliceId,
                            sliceTag = request.sliceTag,
                            predefinedSchema = slice?.schema?.tryReadingEmbeddedSDL(),
                            query = assertion.query
                        )
                        parent.query(q).toUni()
                            .onFailure().recoverWithItem { err ->
                                Log.warn("Unexpected exception while evaluating assertion for change request ${request.id}: ${err.message}", err)
                                QueryResult(
                                    data = emptyMap(),
                                    errors = listOf(mapOf("message" to (err.message ?: "")))
                                )
                            }
                            .chain { result -> evaluateAssertion(assertion, result) }
                    }
                    .merge(ASSERTION_CHECKING_PARALLELISM)
                    .skipToLast()
                    .invoke { _ ->
                        Log.debug("Finished evaluating ${phase.name} assertions (${assertions.size}) for change request ${request.id} in ${System.currentTimeMillis() - startTs} ms")
                    }
            }
    }

    private fun evaluateAssertion(assertion: Assertion, result: QueryResult): Uni<Void> {
        if (result.errors?.isNotEmpty() == true) {
            return Uni.createFrom()
                .failure(RuntimeException("Error executing assertion: ${result.errors}"))
        }
        return when (assertion.type) {
            KvasirVocab.AssertEmptyResult -> {
                when {
                    result.data == null -> Uni.createFrom()
                        .failure(IllegalArgumentException("Invalid assertion query: ${assertion.query}"))

                    // The assertion should fail if any top-level field returns a result or a non-empty collection.
                    result.data!!.any { (_, value) ->
                        // Not sure if the GraphQL resolver always returns non-null results (e.g. in case of fragments), so I'm doing a null-check here anyway.
                        value != null && (value !is Iterable<*> || value.count() > 0)
                    } -> Uni.createFrom()
                        .failure(ChangeAssertionException("Assertion failed: results exists for '${assertion.query}'"))

                    else -> Uni.createFrom().voidItem()
                }
            }

            KvasirVocab.AssertNonEmptyResult -> {
                when {
                    result.data == null -> Uni.createFrom()
                        .failure(IllegalArgumentException("Invalid assertion query: ${assertion.query}"))

                    // The assertion should fail if any top-level field returns null or an empty collection.
                    result.data!!.any { (_, value) ->
                        // Not sure if the GraphQL resolver always returns non-null results (e.g. in case of fragments), so I'm doing a null-check here anyway.
                        value == null || (value is Iterable<*> && value.count() == 0)
                    } -> Uni.createFrom()
                        .failure(ChangeAssertionException("Assertion failed: no results for '${assertion.query}'"))

                    else -> Uni.createFrom().voidItem()
                }
            }

            KvasirVocab.AssertCountBounds -> {
                when {
                    result.data == null -> Uni.createFrom()
                        .failure(IllegalArgumentException("Invalid assertion query: ${assertion.query}"))

                    else -> {
                        val fieldName = assertion.fieldName
                            ?: return Uni.createFrom()
                                .failure(IllegalArgumentException("Invalid count-bounds assertion: missing fieldName"))
                        val minCount = assertion.minCount
                        val maxCount = assertion.maxCount
                        val counts = extractPaginationCounts(result, fieldName).ifEmpty {
                            result.data!!.values
                                .flatMap { flattenToRowMaps(it) }
                                .map { row -> countFieldValues(row[fieldName]).toLong() }
                        }
                        if (counts.isEmpty()) {
                            Uni.createFrom()
                                .failure(ChangeAssertionException("Assertion failed: no results for '${assertion.query}'"))
                        } else {
                            val violation = counts.map { count ->
                                when {
                                    minCount != null && count < minCount ->
                                        "field '$fieldName' has $count value(s), expected at least $minCount"
                                    maxCount != null && count > maxCount ->
                                        "field '$fieldName' has $count value(s), expected at most $maxCount"
                                    else -> null
                                }
                            }.firstOrNull { it != null }

                            if (violation != null) {
                                Uni.createFrom()
                                    .failure(ChangeAssertionException("Assertion failed: $violation for '${assertion.query}'"))
                            } else {
                                Uni.createFrom().voidItem()
                            }
                        }
                    }
                }
            }

            else -> Uni.createFrom()
                .failure(IllegalArgumentException("Unsupported assertion type: ${assertion.type}"))
        }
    }

    private fun extractPaginationCounts(result: QueryResult, fieldName: String): List<Long> {
        val paginationEntries = (result.extensions?.get(PAGINATION_EXTENSION_ID) as? Iterable<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?: return emptyList()
        return paginationEntries
            .filter { entry -> entry["path"]?.toString()?.endsWith("/$fieldName") == true }
            .mapNotNull { entry ->
                when (val totalCount = entry["totalCount"]) {
                    is Number -> totalCount.toLong()
                    is String -> totalCount.toLongOrNull()
                    else -> null
                }
            }
    }

    private fun flattenToRowMaps(value: Any?): List<Map<String, Any?>> {
        return when (value) {
            is Map<*, *> -> listOf(value.entries.associate { it.key.toString() to it.value })
            is Iterable<*> -> value.flatMap { flattenToRowMaps(it) }
            else -> emptyList()
        }
    }

    private fun countFieldValues(value: Any?): Int {
        return when (value) {
            null -> 0
            is Iterable<*> -> value.count()
            else -> 1
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
            .map { (bindings, schema) ->
                val records = mutableListOf<ChangeRecord>()
                // Delete the specified records
                val deleteJsonLd = materializeRecords(request, request.delete, bindings, schema)
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
                val insertJsonLd = materializeRecords(request, request.insert, bindings, schema)
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

    private fun bindWhere(request: ChangeRequest): Uni<Pair<QueryResult, String?>> {
        return if (request.with == null) {
            Uni.createFrom().item(QueryResult(data = emptyMap()) to null)
        } else {
            Log.debug("Binding 'with' clauses for change request ${request.id}...")
            // For change requests on a Slice, load the Slice schema
            (request.sliceId?.let { sliceId ->
                repositoryFactory.getVersionedRepository(Slice::class, request.podId)
                    .run { request.sliceTag?.let { this.findById(sliceId, it) } ?: this.findDefaultForId(sliceId) }
                    .onItem().ifNull().failWith(IllegalArgumentException("Slice not found: $sliceId"))
                    .onItem().ifNotNull().transform { it!! }
            } ?: Uni.createFrom().nullItem())
                .chain { slice ->
                    val schema = try { slice?.schema?.tryReadingEmbeddedSDL() } catch (_: RuntimeException) { null }
                    val q = QueryRequest(
                        context = slice?.context ?: request.context,
                        atChangeId = request.previousChangeId,
                        requestingUser = request.requestingUser,
                        podId = request.podId,
                        sliceId = request.sliceId,
                        sliceTag = request.sliceTag,
                        query = request.with!!,
                        predefinedSchema = schema
                    )
                    kg.query(q).toUni()
                        .chain { result ->
                            if (result.errors?.isNotEmpty() == true) {
                                Uni.createFrom()
                                    .failure(IllegalArgumentException("Error executing 'with' clause: ${result.errors}"))
                            } else {
                                Uni.createFrom().item(result to schema)
                            }
                        }
                }
        }
    }

    private fun materializeRecords(
        request: ChangeRequest,
        records: List<Any>,
        bindings: QueryResult,
        schema: String? = null
    ): List<Map<String, Any>> {
        return records.flatMap { record ->
            when (record) {
                is Map<*, *> -> listOf(record as Map<String, Any>)
                is String -> {
                    if (record == "*") {
                        // Return bindings as is
                        bindings.toJsonLD(request.context, schema)
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
    fun process(request: ChangeRequest, records: Collection<ChangeRecord>): Uni<Collection<ChangeRecord>> {
        return request.sliceId?.let { sliceId ->
            val startTs = System.currentTimeMillis()
            Log.debug("Validating change request ${request.id} against Slice GraphQL schema...")
            // Load Slice schema
            repositoryFactory.getVersionedRepository(Slice::class, request.podId).findById(sliceId)
                .chain { sliceSpec ->
                    if (sliceSpec != null) {

                        val validator = ChangeRequestValidator(
                            records,
                            sliceSpec.schema.tryReadingEmbeddedSDL(),
                            sliceSpec.context,
                            request
                        )
                        try {
                            validator.validate()
                            Uni.createFrom().item(filterRedundantPairs(records))
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
        } ?: Uni.createFrom().item(records)
    }

    /**
     * Filters out change records where the exact same RDF statement appears in both
     * INSERT and DELETE records within the same change request. Such pairs are no-ops
     * in terms of net state and should not be persisted.
     *
     * This handles the `rdf:type` INSERT+DELETE pairs emitted by [UpdateMutationCompiler]
     * (needed for update detection by the validator) as well as any other accidental no-op pairs.
     */
    private fun filterRedundantPairs(records: Collection<ChangeRecord>): Collection<ChangeRecord> {
        val insertStatements = records.filter { it.type == ChangeRecordType.INSERT }.map { it.statement }.toSet()
        val deleteStatements = records.filter { it.type == ChangeRecordType.DELETE }.map { it.statement }.toSet()
        val redundant = insertStatements intersect deleteStatements
        return if (redundant.isEmpty()) records else records.filterNot { it.statement in redundant }
    }

}
