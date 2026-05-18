package kvasir.utils.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.core.RDFDataset
import com.github.jsonldjava.utils.JsonUtils
import io.smallrye.mutiny.Multi
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFStatement
import kvasir.utils.http.getChildUri
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import java.io.InputStream
import java.io.StringWriter
import java.net.URI
import java.util.*
import kotlin.jvm.optionals.getOrNull

object RDFTransformer {

    private val factory = SimpleValueFactory.getInstance()

    // TODO: make configurable?
    private val ACCEPTED_SCHEMES = setOf("http", "https", "urn", "mailto", "ftp", "sftp", "tel")

    fun asRDF4JStatement(statement: RDFStatement): Statement {
        val objVal = when {
            statement.language != null -> factory.createLiteral(statement.`object`, statement.language)
            statement.dataType == null -> factory.createIRI(statement.`object`)
            else -> factory.createLiteral(statement.`object`, Values.iri(statement.dataType))
        }
        return if (statement.graph.isBlank()) {
            factory.createStatement(
                Values.iri(statement.subject),
                Values.iri(statement.predicate),
                objVal
            )
        } else {
            factory.createStatement(
                Values.iri(statement.subject),
                Values.iri(statement.predicate),
                objVal,
                Values.iri(statement.graph)
            )
        }
    }

    fun statementsToJsonLD(statements: List<RDFStatement>): JSONObject {
        val rdf4jStatements = statements.map { asRDF4JStatement(it) }
        return StringWriter().use { writer ->
            Rio.write(rdf4jStatements, writer, RDFFormat.JSONLD)
            when (val jsonld = JsonUtils.fromString(writer.toString())) {
                // If the result is a list, we wrap it in a map with a graph key
                is Iterable<*> -> mapOf(JsonLdKeywords.graph to jsonld)
                // If it's already a map, we return it directly
                is Map<*, *> -> jsonld as JSONObject
                else -> throw IllegalArgumentException("Unexpected JSON-LD format: $jsonld")
            }
        }
    }

    fun toStatements(graphDoc: Map<String, Any>, docBaseUri: String? = null): List<RDFStatement> {
        // Use JsonLdProcessor.toRDF() directly on the already-parsed Map to avoid
        // the expensive Map→JSON-bytes→Rio.parse(JSONLD) round-trip.
        val options = JsonLdOptions().also { if (docBaseUri != null) it.base = docBaseUri }
        @Suppress("UNCHECKED_CAST")
        val dataset = JsonLdProcessor.toRDF(graphDoc, options) as RDFDataset
        val bNodeIdMap = mutableMapOf<String, String>()
        val results = dataset.graphNames().flatMap { graphName ->
            val graphIri = if (graphName == "@default") ""
            else resolveJsonLdNode(graphName, graphName.startsWith("_:"), docBaseUri, bNodeIdMap)
            dataset.getQuads(graphName).map { quad ->
                val subject = resolveJsonLdNode(quad.subject.value, quad.subject.isBlankNode, docBaseUri, bNodeIdMap)
                val predicate = ensureValidAbsoluteIri(quad.predicate.value)
                val obj = quad.`object`
                if (obj.isLiteral) {
                    RDFStatement(subject, predicate, obj.value, graphIri, obj.datatype, obj.language?.takeIf { it.isNotEmpty() })
                } else {
                    RDFStatement(subject, predicate, resolveJsonLdNode(obj.value, obj.isBlankNode, docBaseUri, bNodeIdMap), graphIri, null, null)
                }
            }
        }
        return results
    }

    fun toStatements(inputStream: InputStream, contentType: String, docBaseUri: String? = null): Multi<RDFStatement> {
        val bNodeIdMap = mutableMapOf<BNode, String>()
        return ReactiveRDFParser.parseRdf(inputStream, parseLang(contentType), docBaseUri)
            .map { mapRioStatement(it, docBaseUri, bNodeIdMap) }
    }

    fun toStatements(docs: List<Map<String, Any>>, docBaseUri: String? = null): List<RDFStatement> {
        val defaultStatements =
            toStatements(
                mapOf(JsonLdKeywords.graph to docs.filterNot { it.containsKey(JsonLdKeywords.graph) }),
                docBaseUri
            )
        val namedGraphStatements =
            docs.filter { it.containsKey(JsonLdKeywords.graph) }.flatMap { doc -> toStatements(doc, docBaseUri) }
        return defaultStatements + namedGraphStatements
    }

    fun isValidAbsoluteIri(iri: String): Boolean {
        try {
            ensureValidAbsoluteIri(iri)
            return true
        } catch (ex: IllegalArgumentException) {
            return false
        }
    }

    fun ensureValidAbsoluteIri(iri: String): String {
        try {
            factory.createIRI(iri)
            val scheme = iri.substringBefore(":")
            if (scheme !in ACCEPTED_SCHEMES) {
                throw IllegalArgumentException("IRI uses an unsupported scheme: '$scheme'")
            }
            return iri
        } catch (e: Exception) {
            throw IllegalArgumentException("IRI is not a valid absolute IRI: '$iri'", e)
        }
    }

    private fun resolveJsonLdNode(
        value: String,
        isBlankNode: Boolean,
        docBaseUri: String?,
        bNodeIdMap: MutableMap<String, String>
    ): String {
        return if (isBlankNode) {
            bNodeIdMap.getOrPut(value) {
                docBaseUri?.let { URI.create(it).getChildUri(UUID.randomUUID().toString(), "#").toString() }
                    ?: "urn:uuid:${UUID.randomUUID()}"
            }
        } else {
            ensureValidAbsoluteIri(value)
        }
    }

    private fun processedNonLiteralValue(
        rdfValue: Value,
        docBaseUri: String?,
        bNodeIdMap: MutableMap<BNode, String>
    ): String {
        return if (rdfValue.isBNode) {
            val proposedId =
                docBaseUri?.let { URI.create(it).getChildUri(UUID.randomUUID().toString(), "#").toString() }
                    ?: "urn:uuid:${UUID.randomUUID()}"
            bNodeIdMap.getOrPut(rdfValue as BNode) { proposedId }
        } else {
            ensureValidAbsoluteIri(rdfValue.stringValue())
        }
    }

    private fun parseLang(rawContentType: String): RDFFormat {
        return when (val contentType = MediaType.valueOf(rawContentType).let { "${it.type}/${it.subtype}" }) {
            "text/turtle" -> RDFFormat.TURTLE
            "text/n3" -> RDFFormat.N3
            "application/n-triples" -> RDFFormat.NTRIPLES
            "application/ld+json" -> RDFFormat.JSONLD
            else -> throw IllegalArgumentException("Unsupported content type: $contentType")
        }
    }

    private fun mapRioStatement(
        statement: Statement,
        docBaseUri: String?,
        bNodeIdMap: MutableMap<BNode, String>
    ): RDFStatement {
        return RDFStatement(
            processedNonLiteralValue(statement.subject, docBaseUri, bNodeIdMap),
            ensureValidAbsoluteIri(statement.predicate.stringValue()),
            if (statement.`object`.isLiteral) statement.`object`.stringValue() else processedNonLiteralValue(
                statement.`object`,
                docBaseUri,
                bNodeIdMap
            ),
            statement.context?.let { processedNonLiteralValue(it, docBaseUri, bNodeIdMap) } ?: "",
            statement.`object`.takeIf { it.isLiteral }?.let { it as Literal }?.datatype?.stringValue(),
            statement.`object`.takeIf { it.isLiteral }?.let { it as Literal }?.language?.getOrNull(),
        )
    }

}