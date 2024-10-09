package kvasir.utils.graphql2shacl

import org.apache.jena.graph.NodeFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.shacl.ShaclValidator
import org.apache.jena.shacl.Shapes
import org.apache.jena.sparql.graph.GraphFactory
import java.io.StringReader
import java.io.StringWriter

fun main() {
    val shapesStr = """
        @prefix ex: <http://example.org/> .
        @prefix schema: <http://schema.org/> .
        @prefix sh: <http://www.w3.org/ns/shacl#> .

        ex:Cat a sh:NodeShape, <http://www.w3.org/2000/01/rdf-schema#Class>;
          sh:property [ a sh:PropertyShape;
              sh:path schema:givenName;
              sh:minCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
              sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
              sh:datatype <http://www.w3.org/2001/XMLSchema#string>;
              sh:hasValue "Risto"
            ] .

        ex:Human a sh:NodeShape, <http://www.w3.org/2000/01/rdf-schema#Class>;
          sh:property [ a sh:PropertyShape;
              sh:path schema:givenName;
              sh:minCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
              sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
              sh:datatype <http://www.w3.org/2001/XMLSchema#string>;
              sh:pattern "^Bob.*${'$'}"
            ], [ a sh:PropertyShape;
              sh:path schema:familyName;
              sh:minCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
              sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
              sh:datatype <http://www.w3.org/2001/XMLSchema#string>
            ], [ a sh:PropertyShape;
              sh:path schema:age;
              sh:minCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
              sh:maxCount "1"^^<http://www.w3.org/2001/XMLSchema#int>;
              sh:datatype <http://www.w3.org/2001/XMLSchema#integer>;
              sh:minInclusive 18;
              sh:maxInclusive 120
            ] .

<kvasir:shapes:b0c8c655-40b3-4c6d-9af7-7ce8d0edab4e:FilterShape> a sh:NodeShape;
  sh:or [ a <http://www.w3.org/1999/02/22-rdf-syntax-ns#List>;
      <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> ex:Cat;
      <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> (ex:Human)
    ];
  sh:targetNode <ex:risto>, <ex:bob>, <ex:alice> .
    """.trimIndent()

    val resourceStr = """
                {
                  "@context" : {
                    "schema" : "http://schema.org/",
                    "ex" : "http://example.org/"
                  },
                    "@graph" : [ {
                        "@id" : "ex:risto",
                        "@type" : "ex:Cat",
                        "schema:givenName" : "Risto"
                    }, {
                        "@id" : "ex:bob",
                        "@type" : "ex:Human",
                        "schema:givenName" : "Bob",
                        "schema:familyName" : "Smith",
                        "schema:age" : 19
                    }, {
                        "@id" : "ex:alice",
                        "schema:givenName" : "Alice",
                        "schema:familyName" : "Smith",
                        "schema:age" : 17
                    }]
                }
    """.trimIndent()
    val shapesGraph = GraphFactory.createDefaultGraph()
    RDFDataMgr.read(shapesGraph, StringReader(shapesStr), null, Lang.TURTLE)
    val shapes = Shapes.parse(shapesGraph)

    val resourceGraph = GraphFactory.createDefaultGraph()
    RDFDataMgr.read(resourceGraph, StringReader(resourceStr), null, Lang.JSONLD)

    val report = ShaclValidator.get().validate(shapes, resourceGraph)

    println(report.conforms())
    StringWriter().use { writer ->
        RDFDataMgr.write(writer, report.model, Lang.TURTLE)
        println(writer.toString())
    }
}