package kvasir.plugins.kg.xtdb

import com.google.common.hash.Hashing
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import org.eclipse.microprofile.config.inject.ConfigProperty
import xtdb.api.XtdbClient
import xtdb.api.tx.TxOp
import java.net.URI

@ApplicationScoped
class XtdbKnowledgeGraph(
    private val vertx: Vertx, @ConfigProperty(name = "xtdb.uri", defaultValue = "http://localhost:6543")
    private val uri: String
) : KnowledgeGraph {

    private val xtdb = XtdbClient.openClient(URI.create(uri).toURL())

    override fun process(request: ChangeRequest): Uni<Void> = vertx.executeBlocking {
        require(request.where.isEmpty()) { "Where clause is currently not supported when processing changes." }
        require(request.deletes.isEmpty()) { "Deletes are currently not supported when processing changes." }
        try {
            xtdb.executeTx(
                TxOp.Sql(
                    "INSERT INTO ${request.podId} (_id, s, p, o) VALUES (?, ?, ?, ?)",
                    request.inserts.flatMap { toStatements(it).map { triple -> triple.toRecord() } })
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.replaceWithVoid()

    override fun query(request: QueryRequest): Uni<QueryResult> = vertx.executeBlocking {
        xtdb.openQuery("SELECT * FROM ${request.podId}").use { results ->
            val resultsList = results.map { it.mapValues { value -> value } }.toList()
            QueryResult(resultsList, null)
        }
    }

    override fun history(request: HistoryRequest): Uni<HistoryResult> {
        TODO("Not yet implemented")
    }

    private fun toStatements(doc: Map<String, Any>): List<RDFTriple> {
        val id = doc["@id"]
        return doc.entries.filterNot { it.key == "@id" }.flatMap { (key, value) ->
            when (value) {
                is Map<*, *> -> toStatements(value as Map<String, Any>)
                is List<*> -> value.flatMap { listEntry ->
                    if (listEntry is Map<*, *>) toStatements(listEntry as Map<String, Any>)
                    else listOf(RDFTriple(id.toString(), key, listEntry!!))
                }

                else -> listOf(RDFTriple(id.toString(), key, value))
            }
        }
    }
}

data class RDFTriple(val s: String, val p: String, val o: Any) {
    fun toRecord(): List<Any> {
        return listOf(
            "kvasir:" + Hashing.farmHashFingerprint64().hashString("$s$p$o", Charsets.UTF_8).toString(),
            s,
            p,
            o
        )
    }
}