package kvasir.definitions.kg.changes

import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.rdf.KvasirVocab
import org.eclipse.microprofile.openapi.annotations.media.Schema

enum class AssertionPhase {
    PRE, POST
}

@GenerateNoArgConstructor
data class Assertion(
    @get:Schema(
        description = "The type of the assertion.",
        required = true,
        enumeration = [KvasirVocab.AssertEmptyResult, KvasirVocab.AssertNonEmptyResult],
        example = KvasirVocab.AssertEmptyResult
    )
    val type: String,
    @get:Schema(
        description = "The GraphQL query string to be executed.",
        required = true,
        example = "ex_Person { id ex_givenName @filter(if: \"it==Bob\") }"
    )
    val query: String,
    @get:Schema(
        description = "The phase at which the assertion is evaluated. PRE assertions are checked before the change is applied, POST assertions are checked after (with rollback on failure).",
        required = false,
        enumeration = ["PRE", "POST"],
        example = "PRE"
    )
    val phase: AssertionPhase = AssertionPhase.PRE
)