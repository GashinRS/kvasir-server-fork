package kvasir.utils.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.rdf.KvasirVocab
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import java.io.StringWriter
import java.util.*

object RDFTransformer {

    fun statementsToJsonLD(statements: List<RDFStatement>): Any {
        val factory = SimpleValueFactory.getInstance()
        val rdf4jStatements = statements.map {
            val objVal = when {
                it.language != null -> factory.createLiteral(it.`object`.toString(), it.language)
                it.dataType == null -> factory.createIRI(it.`object`.toString())
                else -> factory.createLiteral(it.`object`.toString(), Values.iri(it.dataType))
            }
            if (it.graph.isBlank()) {
                factory.createStatement(
                    Values.iri(it.subject),
                    Values.iri(it.predicate),
                    objVal
                )
            } else {
                factory.createStatement(
                    Values.iri(it.subject),
                    Values.iri(it.predicate),
                    objVal,
                    Values.iri(it.graph)
                )
            }
        }
        return StringWriter().use { writer ->
            Rio.write(rdf4jStatements, writer, RDFFormat.JSONLD)
            JsonUtils.fromString(writer.toString())
        }
    }

}