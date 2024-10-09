package kvasir.utils.graphql2shacl

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

object SHACLValidator {

    fun validate(shapes: Model, jsonLdInput: String) {
        val shaclSail = ShaclSail(MemoryStore())
        shaclSail.isEclipseRdf4jShaclExtensions = true
        SailRepository(shaclSail).connection.use { connection ->
            // add shapes
            connection.begin()
            connection.add(shapes, RDF4J.SHACL_SHAPE_GRAPH)

            try {
                connection.add(StringReader(jsonLdInput), "", RDFFormat.JSONLD)
                connection.commit()
            } catch (e: RepositoryException) {
                if (e.cause is ValidationException) {
                    val model = (e.cause as ValidationException).validationReportAsModel();
                    Rio.write(model, System.out, RDFFormat.TURTLE);
                }
            }

            connection.close()
        }
    }

    fun filter(shapes: Model, jsonLdInput: String): Boolean {
        val shaclSail = ShaclSail(MemoryStore())
        shaclSail.isEclipseRdf4jShaclExtensions = true
        shaclSail.isDashDataShapes = true
        SailRepository(shaclSail).connection.use { connection ->
            // add shapes
            connection.begin()
            connection.add(shapes, RDF4J.SHACL_SHAPE_GRAPH)

            try {
                connection.add(StringReader(jsonLdInput), "", RDFFormat.JSONLD)
                connection.commit()
            } catch (e: RepositoryException) {
                if (e.cause is ValidationException) {
                    val model = (e.cause as ValidationException).validationReportAsModel()
                    Rio.write(model, System.out, RDFFormat.TURTLE)
                    return false
                }
            }
            return true
        }
    }

}

fun main() {
    val shacl = """
        @prefix ex: <http://example.org/> .
        @prefix schema: <http://schema.org/> .
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix dash: <http://datashapes.org/dash#> .
        
        ex:CatShape a sh:NodeShape;
            sh:targetClass ex:Cat;
              sh:closed true;
              sh:ignoredProperties (rdf:type);
            sh:property [
                sh:path schema:givenName;
                sh:minCount 1;
                sh:maxCount 1;
                sh:datatype xsd:string;
                ] .
        
<kvasir:shapes:3485db0d-f96f-40a5-a520-c6c93453700e:FilterShape> a sh:NodeShape;
  sh:targetNode ex:risto;
  sh:property [
      sh:path rdf:type;
      sh:minCount 1;
      sh:in (ex:Cat ex:Human) ;
    ] .

    """.trimIndent()
    val turtle = """
{
 "@context": {
   "schema": "http://schema.org/",
   "ex": "http://example.org/"
 },
 "@id" : "ex:risto",
 "@type" : "ex:Cat",
 "schema:givenName" : "Risto",
    "schema:familyName" : "Doe"
}
    """.trimIndent()


    // SHACL was niet valid omdat te algemene constraints ook al gaan gelden op de SHACL Shape Resources zelf
    val shaclSail = ShaclSail(MemoryStore())
    shaclSail.isEclipseRdf4jShaclExtensions = true
    SailRepository(shaclSail).connection.use { connection ->
        // add shapes
        connection.begin()
        connection.add(StringReader(shacl), "", RDFFormat.TURTLE, RDF4J.SHACL_SHAPE_GRAPH)

        try {
            connection.add(StringReader(turtle), "", RDFFormat.JSONLD)
            connection.commit()
        } catch (e: RepositoryException) {
            if (e.cause is ValidationException) {
                val model = (e.cause as ValidationException).validationReportAsModel();
                Rio.write(model, System.out, RDFFormat.TURTLE);
            }
        }

        connection.close()
    }
}