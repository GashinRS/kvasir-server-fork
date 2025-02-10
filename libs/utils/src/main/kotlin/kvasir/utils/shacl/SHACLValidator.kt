package kvasir.utils.shacl

import com.github.jsonldjava.utils.JsonUtils
import kvasir.definitions.kg.RDFStatement
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.rdf4j.common.exception.ValidationException
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.vocabulary.RDF4J
import org.eclipse.rdf4j.repository.RepositoryException
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.sail.shacl.ShaclSail
import java.io.StringReader
import java.io.StringWriter
import kotlin.use

interface SHACLValidator {

    /**
     * Validate the given JSON-LD instance against this validator instance.
     * @param jsonLdInstance the JSON-LD instance to validate
     * @throws SHACLValidationFailure if the instance does not conform to the SHACL shapes
     */
    fun validate(jsonLdInstance: Map<String, Any>)

    fun validate(statements: List<RDFStatement>)

    /**
     * Filter the given JSON-LD instance against this validator instance.
     * @param jsonLdInstance the JSON-LD instance to filter
     * @param validationFailureConsumer an optional consumer for the validation failure report
     * @return true if the instance conforms to the SHACL shapes, false otherwise
     */
    fun filter(
        jsonLdInstance: Map<String, Any>,
        validationFailureConsumer: (SHACLValidationFailure) -> Unit = {}
    ): Boolean

}

class SHACLValidationFailure(val report: String, val contentType: String = "text/turtle") :
    IllegalArgumentException("The data does not conform to the supplied SHACL shapes")

class RDF4JSHACLValidator(private val shapeModel: Model) : SHACLValidator {

    companion object {

        fun fromTurtleString(shapes: String): SHACLValidator {
            val model = Rio.parse(StringReader(shapes), "", RDFFormat.TURTLE)
            return RDF4JSHACLValidator(model)
        }

    }

    override fun validate(jsonLdInstance: Map<String, Any>) {
        validate { connection ->
            // add JSON-LD instance
            val dataModel = Rio.parse(StringReader(JsonUtils.toString(jsonLdInstance)), "", RDFFormat.JSONLD)
            require(dataModel.isNotEmpty()) { "The JSON-LD instance is empty" }
            connection.add(dataModel)
        }
    }

    override fun validate(statements: List<RDFStatement>) {
        validate { connection ->
            val rdf4jStatements = statements.map { RDFTransformer.asRDF4JStatement(it) }
            require(rdf4jStatements.isNotEmpty()) { "The statements list is empty" }
            connection.add(rdf4jStatements)
        }
    }

    private fun validate(dataLoader: (SailRepositoryConnection) -> Unit) {
        val shaclSail = ShaclSail(MemoryStore())
        try {
            shaclSail.isDashDataShapes = true
            SailRepository(shaclSail).connection.use { connection ->
                // add shape model
                connection.begin()
                connection.add(shapeModel, RDF4J.SHACL_SHAPE_GRAPH)

                try {
                    // Load the to be validated data
                    dataLoader.invoke(connection)

                    // Commit to trigger the validation
                    connection.commit()
                } catch (e: RepositoryException) {
                    if (e.cause is ValidationException) {
                        val reportModel = (e.cause as ValidationException).validationReportAsModel()
                        val report = StringWriter().use { writer ->
                            Rio.write(reportModel, writer, RDFFormat.TURTLE)
                            writer.toString()
                        }
                        throw SHACLValidationFailure(report)
                    }
                }
            }
        } finally {
            shaclSail.shutDown()
        }
    }

    override fun filter(
        jsonLdInstance: Map<String, Any>,
        validationFailureConsumer: (SHACLValidationFailure) -> Unit
    ): Boolean {
        try {
            validate(jsonLdInstance)
            return true
        } catch (_: SHACLValidationFailure) {
            return false
        }
    }
}