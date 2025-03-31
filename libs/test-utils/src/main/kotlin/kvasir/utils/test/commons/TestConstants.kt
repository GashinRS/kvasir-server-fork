package kvasir.utils.test.commons

import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.SAREFVocab

object TestConstants {

    val TEST_POD_1_ID = "test1"
    val TEST_POD_2_ID = "test2"
    val TEST_POD_3_ID = "test3"

    val CONTEXT = mapOf(
        "ex" to "http://example.org/",
        "so" to "http://schema.org/",
        "saref" to SAREFVocab.baseUri,
        "children" to mapOf(JsonLdKeywords.reverse to "http://example.org/parent"),
        "hasMeasurement" to mapOf(JsonLdKeywords.reverse to SAREFVocab.measurementMadeBy),
        "rdfs" to RDFSVocab.baseUri
    )

}