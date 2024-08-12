package kvasir.definitions.kg.changeops

import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab

/**
 * Models InsertTemplate or DeleteTemplate
 */
data class DataTemplate(
    @JsonProperty(JsonLdKeywords.type)
    val type: String,
    @JsonProperty(KvasirVocab.query)
    val query: String,
    @JsonProperty(KvasirVocab.outputMediaType)
    val outputMediaType: String = "application/json+ld",
    @JsonProperty(KvasirVocab.transform)
    val transform: String
)