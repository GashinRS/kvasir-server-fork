package kvasir.utils.shacl

import com.github.jsonldjava.utils.JsonUtils
import org.eclipse.rdf4j.common.exception.ValidationException
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.vocabulary.RDF4J
import org.eclipse.rdf4j.repository.RepositoryException
import org.eclipse.rdf4j.repository.sail.SailRepository
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
        val shaclSail = ShaclSail(MemoryStore())
        try {
            shaclSail.isDashDataShapes = true
            SailRepository(shaclSail).connection.use { connection ->
                // add shape model
                connection.begin()
                connection.add(shapeModel, RDF4J.SHACL_SHAPE_GRAPH)

                try {
                    // add JSON-LD instance
                    val dataModel = Rio.parse(StringReader(JsonUtils.toString(jsonLdInstance)), "", RDFFormat.JSONLD)
                    require(dataModel.isNotEmpty()) { "The JSON-LD instance is empty" }

                    connection.add(dataModel)
                    // commit transaction to trigger validation
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


fun main() {
    val shacl = """
        @prefix kss: <https://kvasir.discover.ilabt.imec.be/vocab#> .
@prefix schema: <http://schema.org/> .
@prefix ex: <http://example.org/> .
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix dash: <http://datashapes.org/dash#> .

ex:Cat a sh:NodeShape;
  sh:closed true;
  sh:ignoredProperties [ a <http://www.w3.org/1999/02/22-rdf-syntax-ns#List>;
      <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>;
      <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> <http://www.w3.org/1999/02/22-rdf-syntax-ns#nil>
    ];
  sh:property [ a sh:PropertyShape;
      sh:path schema:givenName;
      sh:minCount "0"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:datatype <http://www.w3.org/2001/XMLSchema#string>
    ], [ a sh:PropertyShape;
      sh:path ex:owner;
      sh:minCount "0"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:node schema:Person
    ] .

schema:Person a sh:NodeShape;
  sh:closed true;
  sh:ignoredProperties [ a <http://www.w3.org/1999/02/22-rdf-syntax-ns#List>;
      <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>;
      <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> <http://www.w3.org/1999/02/22-rdf-syntax-ns#nil>
    ];
  sh:property [ a sh:PropertyShape;
      sh:path schema:givenName;
      sh:minCount "0"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:datatype <http://www.w3.org/2001/XMLSchema#string>
    ], [ a sh:PropertyShape;
      sh:path schema:lastName;
      sh:minCount "0"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:datatype <http://www.w3.org/2001/XMLSchema#string>
    ], [ a sh:PropertyShape;
      sh:path schema:knows;
      sh:minCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:node schema:Person
    ], [ a sh:PropertyShape;
      sh:path <ex:owner>;
      sh:name "ownsCat";
      sh:minCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:node ex:Cat
    ] .

<kvasir:shapes:53c10178-2a13-4c88-93ba-bd0cf17d1038:MustHaveTypeShape> a sh:NodeShape;
  sh:target dash:AllSubjects;
  sh:property [ a sh:PropertyShape;
      sh:minCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
      sh:path <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>;
      sh:in (ex:Cat schema:Person)
    ] .
    """.trimIndent()

    val jsonLd = """
        {
          "@id": "http://example.org/alice",
            "@type": "http://schema.org/Person",
          "http://schema.org/givenName": "Alice"
        }
    """.trimIndent()

    val instance = JsonUtils.fromString(jsonLd) as Map<String, Any>

    val validator = RDF4JSHACLValidator.fromTurtleString(shacl)
    validator.validate(instance)
}