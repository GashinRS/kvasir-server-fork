package kvasir.plugins.kg.xtdb

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changeops.ChangeAssertionException
import kvasir.definitions.kg.changeops.InvalidTemplateException
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.kg.xtdb.changes.ChangeProcessor
import kvasir.plugins.kg.xtdb.changes.MetaStore
import kvasir.plugins.kg.xtdb.changes.ReferenceHandler
import kvasir.plugins.kg.xtdb.query.GraphQLResolver
import kvasir.plugins.kg.xtdb.query.GraphQLToSQL
import org.eclipse.microprofile.config.inject.ConfigProperty

private val SCALAR_TYPE_TEMPLATE = mapOf(
    "interfaces" to emptyList<Map<String, Any>>(),
    "inputFields" to null,
    "enumValues" to null,
    "possibleTypes" to null,
    "kind" to "SCALAR",
    "fields" to emptyList<Map<String, Any>>()
)

@ApplicationScoped
class XtdbKnowledgeGraph(
    private val xtdbClient: XtdbClient,
    private val referenceHandler: ReferenceHandler,
    private val graphQLResolver: GraphQLResolver,
    private val metaStore: MetaStore,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.ref-handling-buffer", defaultValue = "50000")
    private val refHandlingBuffer: Int
) : KnowledgeGraph {

    // TODO: this feature need a proper implementation
    private val namespaces: Map<String, String> = mutableMapOf()

    override fun process(request: ChangeRequest): Uni<Void> {
        val start = System.currentTimeMillis()
        val changeProcessor = ChangeProcessor(request, this, assertionCheckingParallelism)
        val database = dbNameForPod(request.podId)
        return changeProcessor.executeAssertions()
            .chain { _ ->
                if (request.insertFromRefs.isNotEmpty() || request.deleteFromRefs.isNotEmpty()) {
                    // Delete from external sources
                    referenceHandler.handleReferences(request.deleteFromRefs, request.podId, request.graph)
                        .group().intoLists().of(refHandlingBuffer)
                        .onItem().transformToUni { deleteTuples ->
                            deleteStatements(database, deleteTuples)
                        }
                        .concatenate()
                        .skipToLast()
                        .chain { _ ->
                            // Insert from external sources
                            referenceHandler.handleReferences(request.insertFromRefs, request.podId, request.graph)
                                .group().intoLists().of(refHandlingBuffer)
                                .onItem().transformToUni { insertTuples ->
                                    insertStatements(
                                        database,
                                        insertTuples
                                    ).chain { _ -> metaStore.syncMetaInfo(request.podId, insertTuples) }
                                }
                                .concatenate()
                                .skipToLast()
                        }
                } else {
                    // Execute embedded inserts/deletes
                    changeProcessor.bindWhere()
                        .chain { bindings ->
                            if (request.delete.contains("*") && request.with == null) {
                                // Delete the entire graph
                                deleteGraph(database, request.graph)
                            } else {
                                // Delete the specified records
                                deleteStatements(
                                    database,
                                    changeProcessor.materializeRecords(request.delete, bindings).map { it.take(1) })
                            }
                                .chain { _ ->
                                    val insertTuples = changeProcessor.materializeRecords(request.insert, bindings)
                                    insertStatements(
                                        database,
                                        insertTuples
                                    ).chain { _ -> metaStore.syncMetaInfo(request.podId, insertTuples) }
                                }
                        }
                }
            }
            .onItem()
            .invoke { _ -> Log.debug("Processed change request with id '${request.id}' in ${System.currentTimeMillis() - start} ms.") }
            .onFailure(ChangeAssertionException::class.java).recoverWithUni { e ->
                Log.warn("Failed to process change request due to assertion error: $request", e)
                Uni.createFrom().voidItem()
            }
            .onFailure(InvalidTemplateException::class.java).recoverWithUni { e ->
                Log.warn("Failed to process change request due to invalid template expression: $request", e)
                Uni.createFrom().voidItem()
            }
    }

    fun insertStatements(database: String, insertTuples: List<List<Any?>>): Uni<Void> {
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "INSERT INTO $database (_id, s, p, o, t, g) VALUES (?, ?, ?, ?, ?, ?)",
                    insertTuples
                )
            )
        )
    }

    fun deleteStatements(database: String, deleteTuples: List<List<Any?>>): Uni<Void> {
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "DELETE FROM $database WHERE _id = ?",
                    deleteTuples
                )
            )
        )
    }

    fun deleteGraph(database: String, graph: String): Uni<Void> {
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "DELETE FROM $database WHERE g = ?",
                    listOf(listOf(graph))
                )
            )
        )
    }

    override fun query(request: QueryRequest): Uni<QueryResult> {
//        val queryMapping = GraphQLToSQL(request)
//        val sql = queryMapping.toSQL()
//        Log.debug("Xtdb query: $sql")
//        return xtdbClient.query(SqlQuery(sql)).map { results ->
//            QueryResult(data = results.map {
//                postProcessIntrospectionResults(it, queryMapping, request)
//            }.firstOrNull() ?: emptyMap())
//        }
        return graphQLResolver.resolve(request)
    }

    override fun history(request: HistoryRequest): Uni<HistoryResult> {
        TODO("Not yet implemented")
    }
}

// Fixes null array values in the output (Xtdb quirk) and resets the mapped fields to their original names
internal fun processOutput(queryMapping: GraphQLToSQL, result: Any): Any {
    return when (result) {
        is List<*> -> if (result.size == 1 && result[0] == null) emptyList() else result.map {
            processOutput(
                queryMapping,
                it!!
            )
        }

        is Map<*, *> -> result.mapValues { value ->
            value.value?.let { processOutput(queryMapping, it) }
        }
            .mapKeys { queryMapping.fieldMapping[it.key] ?: it.key }

        else -> result
    }
}

// TODO: look for a way to split introspection handling from the main query handling (then we also wouldn't need further postprocessing in the Query API).
private fun postProcessIntrospectionResults(
    result: Map<String, Any>,
    queryMapping: GraphQLToSQL,
    request: QueryRequest
): Map<String, Any> {
    return if (result.containsKey("__schema")) {
        // Augment types with Scalars
        val schema = result["__schema"] as Map<String, Any>
        if (schema.containsKey("types")) {
            val types = (schema["types"] as List<Map<String, Any>>)
            val scalars = listOf(
                SCALAR_TYPE_TEMPLATE.plus("name" to "ID"),
                SCALAR_TYPE_TEMPLATE.plus("name" to "http://www.w3.org/2001/XMLSchema#string"),
                SCALAR_TYPE_TEMPLATE.plus("name" to "http://www.w3.org/2001/XMLSchema#integer"),
                SCALAR_TYPE_TEMPLATE.plus("name" to "http://www.w3.org/2001/XMLSchema#float"),
                SCALAR_TYPE_TEMPLATE.plus("name" to "http://www.w3.org/2001/XMLSchema#boolean"),
                mapOf(
                    "name" to RDFSVocab.Resource,
                    "kind" to "OBJECT",
                    "interfaces" to emptyList<Map<String, Any>>(),
                    "inputFields" to null,
                    "enumValues" to null,
                    "possibleTypes" to null,
                    "fields" to types.flatMap { (it["fields"] as List<Map<String, Any>>?) ?: emptyList() }
                        .distinctBy { it["name"] }
                )
            )
            processOutput(queryMapping, result.filterNot { it.key == "__schema" } + mapOf(
                "__schema" to schema + mapOf(
                    "types" to types + scalars
                )
            )) as Map<String, Any>
        } else {
            processOutput(queryMapping, result) as Map<String, Any>
        }
    } else {
        processOutput(queryMapping, result) as Map<String, Any>
    }
}

internal fun dbNameForPod(podId: String): String {
    return podId
    //return "kvasir_" + Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8)
}

internal fun metaDbNameForPod(podId: String): String {
    return dbNameForPod(podId) + "_meta"
}