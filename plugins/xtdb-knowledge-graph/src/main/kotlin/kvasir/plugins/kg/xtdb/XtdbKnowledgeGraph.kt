package kvasir.plugins.kg.xtdb

import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changeops.ChangeAssertionException
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class XtdbKnowledgeGraph(
    private val xtdbClient: XtdbClient,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int
) : KnowledgeGraph {

    override fun process(request: ChangeRequest): Uni<Void> {
        val changeProcessor = ChangeProcessor(request, this)
        val database = dbNameForPod(request.podId)
        return changeProcessor.executeAssertions(request, assertionCheckingParallelism)
            .chain { _ ->
                changeProcessor.getDeleteIds()
            }
            .chain { deleteTuples ->
                xtdbClient.execute(
                    SqlTransaction(
                        SqlOp(
                            "DELETE FROM $database WHERE _id = ?",
                            deleteTuples
                        )
                    )
                )
            }
            .chain { _ ->
                changeProcessor.getInsertTuples()
            }
            .chain { insertTuples ->
                xtdbClient.execute(
                    SqlTransaction(
                        SqlOp(
                            "INSERT INTO $database (_id, s, p, o, t, g) VALUES (?, ?, ?, ?, ?, ?)",
                            insertTuples
                        )
                    )
                )
            }
            .onFailure(ChangeAssertionException::class.java).recoverWithUni { e ->
                Log.warn("Failed to process change request due to assertion error: $request", e)
                Uni.createFrom().voidItem()
            }
    }

    override fun query(request: QueryRequest): Uni<QueryResult> {
        val sql = GraphQLToSQL(request).toSQL()
        Log.debug("Xtdb query: $sql")
        return xtdbClient.query(SqlQuery(sql)).map { results ->
            QueryResult(data = results.map { fixNullArrays(it) as Map<String, Any> })
        }
    }

    override fun history(request: HistoryRequest): Uni<HistoryResult> {
        TODO("Not yet implemented")
    }
}

private fun fixNullArrays(result: Any): Any {
    return when (result) {
        is List<*> -> if (result.size == 1 && result[0] == null) emptyList() else result.map { fixNullArrays(it!!) }
        is Map<*, *> -> result.mapValues { fixNullArrays(it.value!!) }
        else -> result
    }
}

internal fun dbNameForPod(podId: String): String {
    return "kvasir_" + Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8)
}