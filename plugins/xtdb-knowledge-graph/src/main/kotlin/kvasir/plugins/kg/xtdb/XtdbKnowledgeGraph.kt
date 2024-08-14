package kvasir.plugins.kg.xtdb

import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changeops.ChangeAssertionException
import kvasir.definitions.kg.changeops.InvalidTemplateException
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class XtdbKnowledgeGraph(
    private val xtdbClient: XtdbClient,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int
) : KnowledgeGraph {

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
                        insertStatements(database, changeProcessor.materializeRecords(request.insert, bindings))
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
            QueryResult(data = results.map { processOutput(queryMapping, it) as Map<String, Any> })
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

        is Map<*, *> -> result.mapValues { processOutput(queryMapping, it.value!!) }
            .mapKeys { queryMapping.fieldMapping[it.key] ?: it.key }

        else -> result
    }
}

internal fun dbNameForPod(podId: String): String {
    return "kvasir_" + Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8)
}