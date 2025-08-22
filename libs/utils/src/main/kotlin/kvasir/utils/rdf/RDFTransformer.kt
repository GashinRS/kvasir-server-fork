package kvasir.utils.rdf

import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.core.RDFDataset
import com.github.jsonldjava.utils.JsonUtils
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdKeywords
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.rio.WriterConfig
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings
import org.eclipse.rdf4j.rio.helpers.TurtleWriterSettings
import java.io.StringWriter

object RDFTransformer {

    private val factory = SimpleValueFactory.getInstance()

    fun asRDF4JStatement(statement: RDFStatement): Statement {
        val objVal = when {
            statement.language != null -> factory.createLiteral(statement.`object`.toString(), statement.language)
            statement.dataType == null -> factory.createIRI(statement.`object`.toString())
            else -> factory.createLiteral(statement.`object`.toString(), Values.iri(statement.dataType))
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
            val jsonld = JsonUtils.fromString(writer.toString())
            if (jsonld is Iterable<*>) {
                // If the result is a list, we wrap it in a map with a graph key
                mapOf(JsonLdKeywords.graph to jsonld)
            } else if (jsonld is Map<*, *>) {
                // If it's already a map, we return it directly
                jsonld as JSONObject
            } else {
                throw IllegalArgumentException("Unexpected JSON-LD format: $jsonld")
            }
        }
    }

    fun toStatements(graphDoc: Map<String, Any>): List<RDFStatement> {
        val dataset = JsonLdProcessor.toRDF(graphDoc) as RDFDataset
        return dataset.graphNames().flatMap { graph ->
            dataset.getQuads(graph).map { quad ->
                RDFStatement(
                    subject = ensureValidAbsoluteIri(quad.subject.value),
                    predicate = ensureValidAbsoluteIri(quad.predicate.value),
                    `object` = if (quad.`object`.isLiteral) (quad.`object` as RDFDataset.Literal).let {
                        RDFLiteralUtils.getCompatibleRawValue(
                            it.value,
                            it.datatype
                        )
                    } else ensureValidAbsoluteIri(
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

    fun ensureValidAbsoluteIri(iri: String): String {
        if (iri.indexOf(':') < 0) {
            throw IllegalArgumentException("Not a valid (absolute) IRI: '$iri'")
        }
        return iri
    }

}

fun Model.writeToString(format: RDFFormat): String {
    return StringWriter().use { writer ->
        val config = WriterConfig().set(BasicWriterSettings.INLINE_BLANK_NODES, true)
            .set(BasicWriterSettings.PRETTY_PRINT, true)
        Rio.write(this, writer, format, config)
        writer.toString()
    }
}