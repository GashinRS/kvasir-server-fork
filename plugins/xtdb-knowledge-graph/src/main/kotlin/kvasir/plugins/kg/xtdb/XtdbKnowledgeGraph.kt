package kvasir.plugins.kg.xtdb

import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changeops.ChangeAssertionException
import kvasir.definitions.kg.changeops.InvalidTemplateException
import kvasir.definitions.rdf.RDFSVocab
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
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int
) : KnowledgeGraph {

    // TODO: this feature need a proper implementation
    private val namespaces: Map<String, String> = mutableMapOf()

    override fun process(request: ChangeRequest): Uni<Void> {
        val changeProcessor = ChangeProcessor(request, this, assertionCheckingParallelism)
        val database = dbNameForPod(request.podId)
        return changeProcessor.executeAssertions()
            .chain { _ ->
                changeProcessor.bindWhere()
            }
            .chain { bindings ->
                if (request.delete.contains("*") && request.where == null) {
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
                        insertStatements(database, insertTuples)
                    }
            }
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
        val queryMapping = GraphQLToSQL(request)
        val sql = queryMapping.toSQL()
        Log.debug("Xtdb query: $sql")
        return xtdbClient.query(SqlQuery(sql)).map { results ->
            QueryResult(data = results.map {
                postProcessIntrospectionResults(it, queryMapping, request)
            }.firstOrNull() ?: emptyMap())
        }
    }

    override fun history(request: HistoryRequest): Uni<HistoryResult> {
        TODO("Not yet implemented")
    }
}

// Fixes null array values in the output (Xtdb quirk) and resets the mapped fields to their original names
private fun processOutput(queryMapping: GraphQLToSQL, result: Any): Any {
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
                    "fields" to types.flatMap { (it["fields"] as List<Map<String, Any>>?)?: emptyList() }.distinctBy { it["name"] }
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
    return "kvasir_" + Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8)
}