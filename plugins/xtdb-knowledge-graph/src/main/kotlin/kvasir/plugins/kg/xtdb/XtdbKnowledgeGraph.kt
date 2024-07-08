package kvasir.plugins.kg.xtdb

import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.core.RDFDataset
import com.github.jsonldjava.core.RDFDatasetUtils
import com.google.common.hash.Hashing
import io.quarkus.logging.Log
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
            val values = toStatements(request.inserts)
            xtdb.executeTx(
                TxOp.Sql("INSERT INTO ${request.podId} (_id, s, p, o, t) VALUES (?, ?, ?, ?, ?)", values)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.replaceWithVoid()

    override fun query(request: QueryRequest): Uni<QueryResult> {
        TODO()
    }

//    override fun rawQuery(q: String): Uni<QueryResult> = vertx.executeBlocking {
//        xtdb.openQuery(q).use { results ->
//            val dataset = RDFDataset()
//            results.forEach { record ->
//                val type = record["t"] as Map<String, Any?>
//                val quad = RDFDataset.Quad(
//                    RDFDataset.IRI(record["s"] as String),
//                    RDFDataset.IRI(record["p"] as String),
//                    when (type["type"]) {
//                        "IRI" -> RDFDataset.IRI(record["o"] as String)
//                        "Literal" -> RDFDataset.Literal(
//                            record["o"] as String,
//                            type["datatype"] as String?,
//                            type["language"] as String?
//                        )
//
//                        else -> throw IllegalArgumentException("Unknown type: ${type["type"]}")
//                    },
//                    "@default"
//                )
//                dataset.getQuads("@default").add(quad)
//            }
//            val jsonLd = JsonLdProcessor.fromRDF(RDFDatasetUtils.toNQuads(dataset))
//            QueryResult(jsonLd as List<Map<String, Any>>)
//        }
//    }

    // Only return targets that are effectively used in the projection
    private fun getTargets(request: QueryRequest): List<Map<String, Any>> {
        TODO()
    }

    private fun loadProjection(select: List<Map<String, Any>>, targets: List<Map<String, Any>>): QueryResult {
        TODO()
    }

    override fun history(request: HistoryRequest): Uni<HistoryResult> {
        TODO("Not yet implemented")
    }

    private fun toStatements(docs: List<Map<String, Any>>): List<List<Any?>> {
        val dataset = JsonLdProcessor.toRDF(mapOf("@graph" to docs)) as RDFDataset
        return dataset.getQuads("@default").map { quad ->
            listOf(
                "kvasir:" + Hashing.farmHashFingerprint64()
                    .hashString("${quad.subject.value}${quad.predicate.value}${quad.`object`}", Charsets.UTF_8),
                quad.subject.value,
                quad.predicate.value,
                quad.`object`.value,
                mapOf(
                    "type" to when {
                        quad.`object`.isIRI -> "IRI"
                        quad.`object`.isBlankNode -> "BlankNode"
                        quad.`object`.isLiteral -> "Literal"
                        else -> "Unknown"
                    },
                    "datatype" to quad.`object`.datatype?.toString(),
                    "language" to quad.`object`.language?.toString()
                )
            )
        }
    }
}