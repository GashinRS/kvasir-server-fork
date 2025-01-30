package kvasir.utils.rdf

import com.github.jsonldjava.utils.JsonUtils
import kvasir.definitions.kg.RDFStatement
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
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

    fun statementsToJsonLD(statements: List<RDFStatement>): Any {
        val rdf4jStatements = statements.map { asRDF4JStatement(it) }
        return StringWriter().use { writer ->
            Rio.write(rdf4jStatements, writer, RDFFormat.JSONLD)
            JsonUtils.fromString(writer.toString())
        }
    }

}