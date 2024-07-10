package kvasir.plugins.kg.xtdb

import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.core.RDFDataset
import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*

@ApplicationScoped
class XtdbKnowledgeGraph(
    private val xtdbClient: XtdbClient
) : KnowledgeGraph {

    override fun process(request: ChangeRequest): Uni<Void> {
        require(request.where.isEmpty()) { "Where clause is currently not supported when processing changes." }
        require(request.deletes.isEmpty()) { "Deletes are currently not supported when processing changes." }
        val values = toStatements(request.inserts)
        val database = Hashing.farmHashFingerprint64().hashString(request.podId, Charsets.UTF_8)
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "INSERT INTO $database (_id, s, p, o, t) VALUES (?, ?, ?, ?, ?)",
                    values
                )
            )
        )
    }

    override fun query(request: QueryRequest): Uni<QueryResult> {
        val sql = GraphQLToSQL(request).toSQL()
        Log.debug("Xtdb query: $sql")
        return xtdbClient.query(SqlQuery(sql)).map { results ->
            QueryResult(data = results)
        }
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
                ).entries.filter { it.value != null }
                    .joinToString(",", prefix = "{", postfix = "}") { (k, v) -> "$k:'$v'" }
            )
        }
    }
}