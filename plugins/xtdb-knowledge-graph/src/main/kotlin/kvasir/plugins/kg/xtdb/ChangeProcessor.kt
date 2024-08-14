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
import kvasir.definitions.kg.changeops.InvalidTemplateException
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.definitions.reactive.skipToLast

class ChangeProcessor(
    private val request: ChangeRequest,
    private val parent: XtdbKnowledgeGraph,
    private val parallelism: Int
) {

    // Test the assertions, throw an exception if one fails
    fun executeAssertions(): Uni<Void> {
        return Multi.createFrom().iterable(request.assert)
            .onItem()
            .transformToUni { assertion ->
                val q = QueryRequest(
                    podId = request.podId,
                    graphQL = QueryUtils.parseQueryWithContext(assertion.queryStr, request.context)
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

    fun bindWhere(): Uni<QueryResult> {
        return if (request.where == null) {
            Uni.createFrom().item(QueryResult(data = emptyList()))
        } else {
            val q = QueryRequest(
                podId = request.podId,
                targetGraphs = setOf(request.graph),
                graphQL = QueryUtils.parseQueryWithContext(request.where!!, request.context)
            )
            parent.query(q)
                .onFailure().recoverWithItem { err ->
                    QueryResult(
                        data = emptyList(),
                        errors = listOf(mapOf("message" to (err.message ?: "")))
                    )
                }
        }
    }

    fun materializeRecords(records: List<Any>, bindings: QueryResult): List<List<Any?>> {
        return records.flatMap { record ->
            when (record) {
                is Map<*, *> -> toStatements(listOf(record as Map<String, Any>))
                is String -> {
                    if (record == "*") {
                        // Return bindings as is
                        toStatements(bindings.toJsonLD(request.context))
                    } else {
                        toStatements(transformTemplate(
                            record,
                            bindings.data
                        ).map { JsonLdHelper.toCompactFQForm(it.plus(JsonLdKeywords.context to request.context)) })
                    }
                }

                else -> throw InvalidTemplateException("Unsupported insert type: $record")
            }
        }
    }

    private fun transformTemplate(template: String, bindings: List<Map<String, Any>>): List<Map<String, Any>> {
        return when (val transformedData = jsonata(template).evaluate(bindings)) {
            is List<*> -> transformedData.map { it as Map<String, Any> }
            is Map<*, *> -> listOf(transformedData as Map<String, Any>)
            else -> throw InvalidTemplateException("Invalid template result: $transformedData")
        }
    }

    private fun toStatements(graphDoc: Map<String, Any>): List<List<Any?>> {
        val dataset = JsonLdProcessor.toRDF(graphDoc) as RDFDataset
        return dataset.getQuads("@default").map { quad ->
            listOf(
                getRecordId(quad),
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
                request.graph
            )
        }
    }

    private fun toStatements(docs: List<Map<String, Any>>) = toStatements(mapOf(JsonLdKeywords.graph to docs))

    fun getRecordIds(docs: List<Map<String, Any>>): List<List<Any?>> {
        val dataset = JsonLdProcessor.toRDF(mapOf("@graph" to docs)) as RDFDataset
        return dataset.getQuads("@default").map { quad ->
            listOf(
                getRecordId(quad)
            )
        }
    }

    private fun getRecordId(quad: RDFDataset.Quad) =
        "kvasir:" + Hashing.farmHashFingerprint64()
            .hashString("${request.graph}${quad.subject.value}${quad.predicate.value}${quad.`object`}", Charsets.UTF_8)

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