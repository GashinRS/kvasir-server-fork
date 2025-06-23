package kvasir.definitions.kg.changes

import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import org.eclipse.microprofile.openapi.annotations.media.Schema

@GenerateNoArgConstructor
data class Assertion(
    @get:JsonProperty(JsonLdKeywords.type)
    @get:Schema(
        description = "The type of the assertion.",
        required = true,
        enumeration = [KvasirVocab.AssertEmptyResult, KvasirVocab.AssertNonEmptyResult],
        example = KvasirVocab.AssertEmptyResult
    )
    val type: String,
    @get:JsonProperty(KvasirVocab.query)
    @get:Schema(
        description = "The GraphQL query string to be executed.",
        required = true,
        example = "ex_Person { id ex_givenName @filter(if: \"it==Bob\") }"
    )
    val queryStr: String
)