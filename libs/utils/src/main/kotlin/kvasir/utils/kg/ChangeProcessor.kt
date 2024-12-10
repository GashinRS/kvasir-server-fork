package kvasir.utils.kg

import com.dashjoin.jsonata.Jsonata.jsonata
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.core.RDFDataset
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.changeops.ChangeAssertionException
import kvasir.definitions.kg.changeops.InvalidTemplateException
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirNamedGraphs
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.definitions.reactive.skipToLast

class ChangeProcessor(
    private val request: ChangeRequest,
    private val parent: AbstractKnowledgeGraph,
    private val parallelism: Int
) {


    // Test the assertions, throw an exception if one fails
    fun executeAssertions(): Uni<Void> {
        return Multi.createFrom().iterable(request.assert)
            .onItem()
            .transformToUni { assertion ->
                val q = QueryRequest(
                    context = request.context,
                    podId = request.podId,
                    query = assertion.queryStr
                )
                parent.query(q)
                    .onFailure().recoverWithItem { err ->
                        QueryResult(
                            data = emptyMap(),
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
                                    when {
                                        result.data == null -> Uni.createFrom()
                                            .failure(IllegalArgumentException("Invalid assertion query: ${assertion.queryStr}"))

                                        result.data!!.isNotEmpty() -> Uni.createFrom()
                                            .failure(ChangeAssertionException("Assertion failed: results exists for '${assertion.queryStr}'"))

                                        else -> Uni.createFrom().voidItem()
                                    }
                                }

                                KvasirVocab.AssertNonEmptyResult -> {
                                    when {
                                        result.data == null -> Uni.createFrom()
                                            .failure(IllegalArgumentException("Invalid assertion query: ${assertion.queryStr}"))

                                        result.data!!.isEmpty() -> Uni.createFrom()
                                            .failure(ChangeAssertionException("Assertion failed: no results for '${assertion.queryStr}'"))

                                        else -> Uni.createFrom().voidItem()
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
        return if (request.with == null) {
            Uni.createFrom().item(QueryResult(data = emptyMap()))
        } else {
            val q = QueryRequest(
                context = request.context,
                podId = request.podId,
                targetGraphs = setOf(),
                query = request.with!!
            )
            parent.query(q)
                .onFailure().recoverWithItem { err ->
                    QueryResult(
                        data = emptyMap(),
                        errors = listOf(mapOf("message" to (err.message ?: "")))
                    )
                }
        }
    }

    fun materializeRecords(records: List<Any>, bindings: QueryResult): List<Map<String, Any>> {
        return records.flatMap { record ->
            when (record) {
                is Map<*, *> -> listOf(record as Map<String, Any>)
                is String -> {
                    if (record == "*") {
                        // Return bindings as is
                        bindings.toJsonLD(request.context)
                            .find { it[JsonLdKeywords.id] == KvasirNamedGraphs.queryResultDataGraph }?.let {
                            val graph = it[JsonLdKeywords.graph]
                            if (graph is List<*>) {
                                graph.map { it as Map<String, Any> }
                            } else {
                                listOf(graph as Map<String, Any>)
                            }
                        } ?: listOf()
                    } else {
                        transformTemplate(
                            record,
                            bindings.data ?: emptyMap()
                        ).map { JsonLdHelper.toCompactFQForm(it.plus(JsonLdKeywords.context to request.context)) }
                    }
                }

                else -> throw InvalidTemplateException("Unsupported insert type: $record")
            }
        }
    }

    private fun transformTemplate(template: String, bindings: Map<String, Any>): List<Map<String, Any>> {
        return when (val transformedData = jsonata(template).evaluate(bindings)) {
            is List<*> -> transformedData.map { it as Map<String, Any> }
            is Map<*, *> -> listOf(transformedData as Map<String, Any>)
            else -> throw InvalidTemplateException("Invalid template result: $transformedData")
        }
    }

    fun toStatements(graphDoc: Map<String, Any>): List<RDFStatement> {
        val dataset = JsonLdProcessor.toRDF(graphDoc) as RDFDataset
        return dataset.graphNames().flatMap { graph ->
            dataset.getQuads(graph).map { quad ->
                RDFStatement(
                    subject = ensureValidAbsoluteIri(quad.subject.value),
                    predicate = ensureValidAbsoluteIri(quad.predicate.value),
                    `object` = if (quad.`object`.isLiteral) getCompatibleRawValue(quad.`object` as RDFDataset.Literal) else ensureValidAbsoluteIri(
                        quad.`object`.value
                    ),
                    graph = quad.graph?.value?.let { ensureValidAbsoluteIri(it) } ?: "",
                    dataType = quad.`object`.datatype?.toString(),
                    language = quad.`object`.language?.toString()
                )
            }
        }
    }

    fun toStatements(docs: List<Map<String, Any>>): List<RDFStatement> {
        val defaultStatements =
            toStatements(mapOf(JsonLdKeywords.graph to docs.filterNot { it.containsKey(JsonLdKeywords.graph) }))
        val namedGraphStatements =
            docs.filter { it.containsKey(JsonLdKeywords.graph) }.flatMap { doc -> toStatements(doc) }
        return defaultStatements + namedGraphStatements
    }

    private fun ensureValidAbsoluteIri(iri: String): String {
        if (iri.indexOf(':') < 0) {
            throw IllegalArgumentException("Not a valid (absolute) IRI: '$iri'")
        }
        return iri
    }

    /**
     * Get the value of an RDF Literal as a Java compatible primitive (if not supported, the string representation is used).
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