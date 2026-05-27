package kvasir.services.api.kg.changes

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.idgen.ChangeRequestId
import org.eclipse.microprofile.openapi.annotations.media.Schema

data class ChangeRequestInput(
    @get:Schema(
        description = "The JSON-LD context for the change request.",
        example = ApiDocConstants.JSON_LD_CONTEXT_EXAMPLE
    )
    val context: Map<String, Any> = emptyMap(),
    @get:Schema(
        description = "List of assertions to be checked before (PRE) or after (POST) applying the change request. POST assertions trigger a rollback on failure."
    )
    @get:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val assert: List<Assertion> = emptyList(),
    @get:Schema(
        description = "Optional GraphQL query where matches are required to be found for the change request to be applied. Results are bound to the field names in the query and can be used in the insert and delete operations (via templates).",
        example = "ex_Person { id ex_givenName @filter(if: \"it==Bob\") }"
    )
    val with: String? = null,
    @get:Schema(
        description = "List of triples to be inserted, or a [JSONata](https://jsonata.org) template string to be applied to the results of the with-clause.",
        example = "[ { \"@id\": \"ex:123\", \"ex:givenName\": \"Bob\" } ]"
    )
    @get:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val insert: List<Any> = emptyList(),
    @get:Schema(
        description = "List of triples to be deleted, or a [JSONata](https://jsonata.org) template string to be applied to the results of the with-clause.",
        example = "[ { \"@id\": \"ex:123\", \"ex:givenName\": \"Alice\" } ]"
    )
    @get:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val delete: List<Any> = emptyList(),
) {

    fun toChangeRequest(
        fqPodId: String,
        principal: String,
        sliceId: String? = null,
        sliceTag: String? = null
    ): ChangeRequest {
        // Validate
        require(insert.isNotEmpty() || delete.isNotEmpty()) {
            "At least one of insert or delete properties must be provided"
        }
        require(insert.filterIsInstance<String>().isEmpty() || with != null) {
            "Insert templates require a with-clause"
        }
        require(delete.filterIsInstance<String>().isEmpty() || with != null) {
            "Delete templates require a with-clause"
        }
        
        return ChangeRequest(
            id = ChangeRequestId.generate(principal).encode(),
            context = context,
            requestingUser = principal,
            podId = fqPodId,
            sliceId = sliceId,
            sliceTag = sliceTag,
            assert = assert,
            with = with,
            insert = insert,
            delete = delete
        )
    }

}

// This type is only needed to generate OpenAPI documentation.
@GenerateNoArgConstructor
data class ChangeReportGraph(
    @get:JsonProperty(JsonLdKeywords.graph)
    val graph: List<ProcessedChange>
)
