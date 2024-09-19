package kvasir.plugins.kg.xtdb

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class XtdbClient(
    vertx: Vertx,
    @ConfigProperty(name = "xtdb.uri", defaultValue = "http://localhost:6543")
    private val uri: String
) {
    private val webClient = WebClient.create(vertx)

    fun execute(transaction: SqlTransaction): Uni<Void> {
        return webClient.postAbs("$uri/tx").sendJson(transaction)
            .chain { resp ->
                if (resp.statusCode() in 200..399) {
                    Uni.createFrom().voidItem()
                } else {
                    Uni.createFrom()
                        .failure { RuntimeException("Could not process transaction: \n${resp.bodyAsString()}") }
                }
            }
    }

    fun query(query: SqlQuery): Uni<List<Map<String, Any>>> {
        Log.debug("Querying xtdb with query: ${query.sql}")
        return webClient.postAbs("$uri/query").sendJson(query)
            .chain { resp ->
                if (resp.statusCode() in 200..399) {
                    Uni.createFrom().item { resp.bodyAsJsonArray().filterIsInstance<JsonObject>().map { it.map } }
                } else {
                    Uni.createFrom().failure { RuntimeException("Could not process query: \n${resp.bodyAsString()}") }
                }
            }
    }

}

data class SqlTransaction(val txOps: List<SqlOp>) {
    constructor(vararg txOps: SqlOp) : this(txOps.toList())
}

data class SqlOp(val sql: String, val argRows: List<List<Any?>>)
data class SqlQuery(val sql: String)