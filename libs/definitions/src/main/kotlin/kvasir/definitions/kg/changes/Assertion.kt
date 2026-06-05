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
        enumeration = [KvasirVocab.AssertEmptyResult, KvasirVocab.AssertNonEmptyResult, KvasirVocab.AssertCountBounds],
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
    val phase: AssertionPhase = AssertionPhase.PRE,
    @get:Schema(
        description = "Target field name for count-bound assertions (required when type is AssertCountBounds).",
        required = false,
        example = "ex_tags"
    )
    val fieldName: String? = null,
    @get:Schema(
        description = "Minimum number of values required for the target field (AssertCountBounds only).",
        required = false,
        minimum = "0",
        example = "1"
    )
    val minCount: Int? = null,
    @get:Schema(
        description = "Maximum number of values allowed for the target field (AssertCountBounds only).",
        required = false,
        minimum = "0",
        example = "5"
    )
    val maxCount: Int? = null
) {
    init {
        if (type == KvasirVocab.AssertCountBounds) {
            require(!fieldName.isNullOrBlank()) { "fieldName is required for AssertCountBounds assertions" }
            require(minCount != null || maxCount != null) { "At least one of minCount or maxCount is required for AssertCountBounds assertions" }
            minCount?.let { require(it >= 0) { "minCount must be >= 0" } }
            maxCount?.let { require(it >= 0) { "maxCount must be >= 0" } }
            if (minCount != null && maxCount != null) {
                require(minCount <= maxCount) { "minCount must be <= maxCount" }
            }
        }
    }
}
