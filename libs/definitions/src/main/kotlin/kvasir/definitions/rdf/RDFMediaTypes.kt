package kvasir.definitions.rdf

object RDFMediaTypes {

    const val JSON_LD = "application/ld+json"
    const val TURTLE = "text/turtle"
    const val N3 = "text/n3"
    const val N_TRIPLES = "application/n-triples"

    val supportedTypes = setOf(JSON_LD, TURTLE, N3, N_TRIPLES)
}