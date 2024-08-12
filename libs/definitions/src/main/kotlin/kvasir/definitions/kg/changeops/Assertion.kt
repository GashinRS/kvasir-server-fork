package kvasir.definitions.kg.changeops

import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab


data class Assertion(
    @JsonProperty(JsonLdKeywords.type)
    val type: String,
    @JsonProperty(KvasirVocab.query)
    val queryStr: String
)

class ChangeAssertionException(message: String) : RuntimeException(message)