package kvasir.utils.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.rdf.KvasirVocab
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import java.io.StringWriter
import java.util.UUID

object RDFTransformer {

    fun statementsToJsonLD(statements: List<RDFStatement>): Any {
        val rdf4jStatements = statements.map {
            val objVal = when {
                it.language != null -> Values.literal(it.`object`.toString(), it.language)
                it.dataType == null -> Values.iri(it.`object`.toString())
                else -> Values.literal(it.`object`.toString(), Values.iri(it.dataType))
            }
            if (it.graph.isBlank()) {
                Values.getValueFactory().createStatement(
                    Values.iri(it.subject),
                    Values.iri(it.predicate),
                    objVal
                )
            } else {
                Values.getValueFactory().createStatement(
                    Values.iri(it.subject),
                    Values.iri(it.predicate),
                    objVal,
                    Values.iri(it.graph)
                )
            }
        }
        return StringWriter().use { writer ->
            Rio.write(rdf4jStatements, writer, RDFFormat.JSONLD)
            println(writer.toString())
            JsonUtils.fromString(writer.toString())
        }
    }

}

fun main() {
    val statements = listOf(
        RDFStatement(
            subject = "http://example.org/change1",
            predicate = "http://example.org/transaction-id",
            `object` = UUID.randomUUID().toString(),
            language = "en"
        ),
        RDFStatement(
            subject = "http://example.org/change2",
            predicate = "http://example.org/transaction-id",
            `object` = UUID.randomUUID().toString(),
            language = "en"
        ),
        RDFStatement(
            subject = "http://example.org/alice",
            predicate = "http://example.org/name",
            `object` = "Alice",
            graph = "http://example.org/change1",
            language = "en"
        ),
        RDFStatement(
            subject = "http://example.org/bob",
            predicate = "http://example.org/name",
            `object` = "Bob",
            graph = "http://example.org/change2",
            language = "en"
        ),
    )
    val jsonLD = RDFTransformer.statementsToJsonLD(statements)
    val compactedJsonLd = JsonLdProcessor.compact(jsonLD, mapOf("kss" to KvasirVocab.baseUri), JsonLdOptions())
    println(JsonUtils.toString(compactedJsonLd))
}