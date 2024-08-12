package kvasir.plugins.kg.xtdb

import com.dashjoin.jsonata.Jsonata.jsonata
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.core.RDFDataset
import com.google.common.hash.Hashing
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.graphql.QueryUtils
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.changeops.ChangeAssertionException
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.definitions.reactive.skipToLast

class ChangeProcessor(private val request: ChangeRequest, private val parent: XtdbKnowledgeGraph) {

    // TODO: rewrite as a streaming implementation (result of InsertTemplate should not be collected in-memory)
    fun getInsertTuples(): Uni<List<List<Any?>>> {
        return Multi.createFrom().iterable(request.inserts)
            .flatMap { doc ->
                if (KvasirVocab.InsertTemplate == doc[JsonLdKeywords.type]) {
                    // Materialize template by executing query and performing JSONata transformation
                    materializeTemplate(doc)
                } else {
                    Multi.createFrom().item(doc)
                }
            }
            .collect().asList().map { docs ->
                toStatements(request.graph, docs)
            }
    }

    fun getDeleteIds(): Uni<List<List<Any?>>> {
        return Multi.createFrom().iterable(request.deletes)
            .flatMap { doc ->
                if (KvasirVocab.DeleteTemplate == doc[JsonLdKeywords.type] || KvasirVocab.DeleteByQuery == doc[JsonLdKeywords.type]) {
                    // Materialize template by executing query and performing JSONata transformation
                    materializeTemplate(doc)
                } else {
                    Multi.createFrom().item(doc)
                }
            }
            .collect().asList().map { docs ->
                getRecordIds(request.graph, docs)
            }
    }

    // Test the assertions, throw an exception if one fails
    fun executeAssertions(request: ChangeRequest, parallelism: Int): Uni<Void> {
        return Multi.createFrom().iterable(request.assertions)
            .onItem()
            .transformToUni { assertion ->
                val q = QueryRequest(
                    podId = request.podId,
                    graphQL = QueryUtils.parseQueryWithContext(assertion.queryStr, request.userProvidedContext)
                )
                parent.query(q)
                    .onFailure().recoverWithItem { err ->
                        QueryResult(
                            data = emptyList(),
                            errors = listOf(mapOf("message" to (err.message ?: "")))
                        )
                    }
                    .chain { result ->
                        if (result.errors?.isNotEmpty() == true) {
                            Uni.createFrom()
                                .failure(ChangeAssertionException("Error executing assertion: ${result.errors}"))
                        } else {
                            when (assertion.type) {
                                KvasirVocab.AssertEmptyResult -> {
                                    if (result.data.isNotEmpty()) {
                                        Uni.createFrom()
                                            .failure(ChangeAssertionException("Assertion failed: results exists for '${assertion.queryStr}'"))
                                    } else {
                                        Uni.createFrom().voidItem()
                                    }
                                }

                                KvasirVocab.AssertNonEmptyResult -> {
                                    if (result.data.isEmpty()) {
                                        Uni.createFrom()
                                            .failure(ChangeAssertionException("Assertion failed: no results for '${assertion.queryStr}'"))
                                    } else {
                                        Uni.createFrom().voidItem()
                                    }
                                }

                                else -> Uni.createFrom()
                                    .failure(IllegalArgumentException("Unsupported assertion type: ${assertion.type}"))
                            }
                        }
                    }
            }
            .merge(parallelism)
            .skipToLast()
    }

    private fun materializeTemplate(doc: Map<String, Any>): Multi<Map<String, Any>> {
        val q = QueryRequest(
            podId = request.podId,
            targetGraphs = setOf(request.graph),
            graphQL = QueryUtils.parseQueryWithContext(doc[KvasirVocab.query] as String, request.userProvidedContext)
        )
        return parent.query(q)
            .onFailure().recoverWithItem { err ->
                QueryResult(
                    data = emptyList(),
                    errors = listOf(mapOf("message" to (err.message ?: "")))
                )
            }
            .onItem().transformToMulti { result ->
                val jsonLDResult =
                    result.toJsonLD(request.userProvidedContext).filterNot { it.key == JsonLdKeywords.context }
                val transformedResult = if (doc[JsonLdKeywords.type] == KvasirVocab.DeleteByQuery) {
                    jsonLDResult
                } else {
                    val transformExpr = jsonata(doc[KvasirVocab.transform] as String)
                    transformExpr.evaluate(jsonLDResult)
                }
                when {
                    transformedResult is Map<*, *> && transformedResult.containsKey(JsonLdKeywords.graph) -> Multi.createFrom()
                        .iterable(transformedResult[JsonLdKeywords.graph] as List<Map<String, Any>>)

                    transformedResult is Map<*, *> -> Multi.createFrom().item(transformedResult as Map<String, Any>)
                    transformedResult is List<*> -> Multi.createFrom()
                        .iterable(transformedResult.map { it as Map<String, Any> })

                    else -> Multi.createFrom().empty()
                }.map { JsonLdHelper.toCompactFQForm(it, request.userProvidedContext) }
            }
    }

    private fun toStatements(graph: String, docs: List<Map<String, Any>>): List<List<Any?>> {
        val dataset = JsonLdProcessor.toRDF(mapOf("@graph" to docs)) as RDFDataset
        return dataset.getQuads("@default").map { quad ->
            listOf(
                getRecordId(graph, quad),
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

    private fun getRecordIds(graph: String, docs: List<Map<String, Any>>): List<List<Any?>> {
        val dataset = JsonLdProcessor.toRDF(mapOf("@graph" to docs)) as RDFDataset
        return dataset.getQuads("@default").map { quad ->
            listOf(
                getRecordId(graph, quad)
            )
        }
    }

    private fun getRecordId(graph: String, quad: RDFDataset.Quad) =
        "kvasir:" + Hashing.farmHashFingerprint64()
            .hashString("${graph}${quad.subject.value}${quad.predicate.value}${quad.`object`}", Charsets.UTF_8)

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