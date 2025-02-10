package kvasir.utils.shacl

import com.github.jsonldjava.utils.JsonUtils
import kvasir.utils.rdf.RDFTransformer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class GraphQL2SHACLTest {

    companion object {
        val context = mapOf(
            "so" to "http://schema.org/", "ex" to "http://example.org/"
        )
    }

    @Test
    fun testSimpleSchema() {
        val graphqlSchema = """
            type Query {
              persons: [ex_Person]
            }
            
            type Mutation {
              insert(persons: [PersonInput]): ID!
            }
            
            type ex_Person {
              id: ID!
              so_givenName: String!
              so_familyName: String!
              so_email: [String!]
            }
            
            input PersonInput @class(iri: "ex:Person") {
              id: ID!
              so_givenName: String! @shape(minLength: 2)
              so_familyName: String!
              so_email: [String!]
            }
        """.trimIndent()

        val validJsonLd = JsonUtils.fromString(
            """
            {
              "@context": {
                "so": "http://schema.org/",
                "ex": "http://example.org/"
              },
              "@id": "ex:jdoe",
              "@type": "ex:Person",
              "so:givenName": "John",
              "so:familyName": "Doe",
              "so:email": "jdoe@example.org"
            }
        """.trimIndent()
        ) as Map<String, Any>

        // The following instance is missing the required relation "email"
        val invalidJsonLd = JsonUtils.fromString(
            """
            {
              "@context": {
                "so": "http://schema.org/",
                "ex": "http://example.org/"
              },
              "@id": "ex:jdoe",
              "@type": "ex:Person",
              "so:givenName": "John"
            }
        """.trimIndent()
        ) as Map<String, Any>

        val convertor = GraphQL2SHACL(graphqlSchema, context)
        val insertSHACLModel = convertor.getInsertSHACL()
        val validator = RDF4JSHACLValidator(insertSHACLModel)
        assertDoesNotThrow { validator.validate(validJsonLd) }
        assertThrows<SHACLValidationFailure> { validator.validate(invalidJsonLd) }

        // Try to pass along the json-ld data as statements
        val validStatemetents = RDFTransformer.toStatements(validJsonLd)
        assertDoesNotThrow { validator.validate(validStatemetents) }
        val invalidStatements = RDFTransformer.toStatements(invalidJsonLd)
        assertThrows<SHACLValidationFailure> { validator.validate(invalidStatements) }
    }

    @Test
    fun testSHACL() {
        val shacl = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <http://schema.org/> .
            @prefix ex: <http://example.org/> .
            @prefix dash: <http://datashapes.org/dash#> . 
            
            schema:Person
              a rdfs:Class, sh:NodeShape ;
              sh:closed true ;
              sh:ignoredProperties ( rdf:type ) ;
              sh:property
                [
                  sh:path schema:givenName ;
                  sh:minCount 1 ;
                  sh:maxCount 1 ;
                  sh:datatype xsd:string ;
                ],
                [
                  sh:path schema:familyName ;
                  sh:minCount 1 ;
                  sh:maxCount 1 ;
                  sh:datatype xsd:string ;
                ] ;
            .
            
            ex:DefaultShape a sh:NodeShape ;
              sh:target dash:AllSubjects ;
              sh:property [
                sh:path rdf:type ;
                sh:minCount 1 ;
                sh:in ( schema:Person ) ;
              ] .
        """.trimIndent()

        val validJsonLd = JsonUtils.fromString(
            """
            {
              "@context": {
                "so": "http://schema.org/",
                "ex": "http://example.org/"
              },
              "@id": "ex:jdoe",
              "@type": "so:Person",
              "so:givenName": "John",
              "so:familyName": "Doe"
            }
        """.trimIndent()
        ) as Map<String, Any>

        // The following instance is missing the required relation "familyName"
        val invalidJsonLd = JsonUtils.fromString(
            """
            {
              "@context": {
                "so": "http://schema.org/",
                "ex": "http://example.org/"
              },
              "@id": "ex:jdoe",
              "@type": "so:Person",
              "so:givenName": "John"
            }
        """.trimIndent()
        ) as Map<String, Any>

        val validator = RDF4JSHACLValidator.fromTurtleString(shacl)
        assertDoesNotThrow { validator.validate(validJsonLd) }
        assertThrows<SHACLValidationFailure> { validator.validate(invalidJsonLd) }
    }

}