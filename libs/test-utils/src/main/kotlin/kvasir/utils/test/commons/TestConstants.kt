package kvasir.utils.test.commons

import kvasir.definitions.config.PodConfig
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.SAREFVocab

object TestConstants {

    val CONTEXT = mapOf(
        "ex" to "http://example.org/",
        "so" to "http://schema.org/",
        "saref" to SAREFVocab.baseUri,
        "children" to mapOf(JsonLdKeywords.reverse to "http://example.org/parent"),
        "hasMeasurement" to mapOf(JsonLdKeywords.reverse to SAREFVocab.measurementMadeBy),
        "rdfs" to RDFSVocab.baseUri
    )

}