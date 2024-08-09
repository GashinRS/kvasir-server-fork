package kvasir.plugins.kg.xtdb

import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.core.RDFDataset
import com.google.common.hash.Hashing
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.rdf.XSDVocab

@ApplicationScoped
class XtdbKnowledgeGraph(
    private val xtdbClient: XtdbClient
) : KnowledgeGraph {

    override fun process(request: ChangeRequest): Uni<Void> {
        require(request.where.isEmpty()) { "Where clause is currently not supported when processing changes." }
        require(request.deletes.isEmpty()) { "Deletes are currently not supported when processing changes." }
        val values = toStatements(request.graph, request.inserts)
        val database = dbNameForPod(request.podId)
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "INSERT INTO $database (_id, s, p, o, t, g) VALUES (?, ?, ?, ?, ?, ?)",
                    values
                )
            )
        )
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

    private fun toStatements(graph: String, docs: List<Map<String, Any>>): List<List<Any?>> {
        val dataset = JsonLdProcessor.toRDF(mapOf("@graph" to docs)) as RDFDataset
        return dataset.getQuads("@default").map { quad ->
            listOf(
                "kvasir:" + Hashing.farmHashFingerprint64()
                    .hashString("${graph}${quad.subject.value}${quad.predicate.value}${quad.`object`}", Charsets.UTF_8),
                quad.subject.value,
                quad.predicate.value,
                if (quad.`object`.isLiteral) getCompatibleRawValue(quad.`object` as RDFDataset.Literal) else quad.`object`.value,
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
                    .joinToString(",", prefix = "{", postfix = "}") { (k, v) -> "$k:'$v'" },
                graph
            )
        }
    }

    /**
     * Get the value of an RDF Literal as a database compatible primitive (if not supported, the string representation is used).
     */
    private fun getCompatibleRawValue(literalNode: RDFDataset.Literal): Any {
        return when (literalNode.datatype) {
            XSDVocab.int, XSDVocab.integer -> literalNode.value.toIntOrNull()
            XSDVocab.double -> literalNode.value.toDoubleOrNull()
            XSDVocab.long -> literalNode.value.toLongOrNull()
            XSDVocab.boolean -> literalNode.value.toBooleanStrictOrNull()
            else -> null
        } ?: literalNode.value
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