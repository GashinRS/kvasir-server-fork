package kvasir.definitions.kg.changes

import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import org.eclipse.microprofile.openapi.annotations.media.Schema


data class Assertion(
    @JsonProperty(JsonLdKeywords.type)
    @get:Schema(
        name = "@type",
        description = "The type of the assertion.",
        required = true,
        enumeration = [KvasirVocab.AssertEmptyResult, KvasirVocab.AssertNonEmptyResult],
        example = "kss:AssertEmptyResult"
    )
    val type: String,
    @JsonProperty(KvasirVocab.query)
    @get:Schema(
        name = "kss:query",
        description = "The GraphQL query string to be executed.",
        required = true,
        example = "{ id ex_givenName(_: \"Bob\") }"
    )
    val queryStr: String
)