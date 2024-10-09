package kvasir.services.api.kg.inbox

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.config.StaticBootstrapConfig
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.changeops.Assertion
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import org.apache.kafka.common.errors.RecordTooLargeException
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import java.util.UUID

@Tag(name = ApiDocTags.KNOWLEDGE_GRAPH_API)
class InboxApi(
    @Channel("change_requests_publish")
    private val changeEmitter: MutinyEmitter<ChangeRequest>,
    private val knowledgeGraph: KnowledgeGraph,
    private val podConfig: StaticBootstrapConfig
) {

    @Path("{podId}/kg/inbox")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Perform mutations on the KG.",
        description = "Post a change request, containing the requested mutations, to the inbox of the specified pod.",
    )
    @APIResponse(responseCode = "202", description = "Change request accepted.")
    fun processChangeRequest(
        @PathParam("podId") podId: String,
        @Context
        uriInfo: UriInfo,
        input: ChangeRequestInput
    ): Uni<Response> {
        if (podConfig.pods().none { it.name() == podId }) {
            return Uni.createFrom().item { Response.status(Response.Status.NOT_FOUND).build() }
        }
        val changeCommand = input.toChangeRequest(podId, uriInfo)
        return changeEmitter.sendMessage(KafkaRecord.of(podId, changeCommand))
            .map { _ -> Response.accepted().build() }
            .onFailure(RecordTooLargeException::class.java)
            .recoverWithItem { _ -> Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build() }
    }

    @Path("{podId}/kg/slices/{sliceId}/inbox")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Perform mutations on a specific slice of the KG.",
        description = "Post a change request, containing the requested mutations, to a slice inbox of the specified pod.",
    )
    @APIResponse(responseCode = "202", description = "Change request accepted.")
    fun processSliceChangeRequest(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @Context
        uriInfo: UriInfo,
        input: ChangeRequestInput
    ): Uni<Response> {
        TODO()
    }

}

data class ChangeRequestInput(
    @get:Schema(
        name = "@context",
        description = "The JSON-LD context for the change request.",
        example = ApiDocConstants.JSON_LD_CONTEXT_EXAMPLE_1
    )
    @JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any> = emptyMap(),
    @get:Schema(
        name = "kss:assert",
        description = "List of assertions to be checked before applying the change request."
    )
    @JsonProperty(KvasirVocab.assert)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val assert: List<Assertion> = emptyList(),
    @get:Schema(
        name = "kss:with",
        description = "Optional GraphQL query where matches are required to be found for the change request to be applied. Results are bound to the field names in the query and can be used in the insert and delete operations (via templates).",
        example = "{ id ex_givenName(_: \"Bob\") }"
    )
    @JsonProperty(KvasirVocab.with)
    val with: String? = null,
    @get:Schema(
        name = "kss:insert",
        description = "List of triples to be inserted, or a [JSONata](https://jsonata.org) template string to be applied to the results of the with-clause.",
        example = "[ { \"@id\": \"ex:123\", \"ex:givenName\": \"Bob\" } ]"
    )
    @JsonProperty(KvasirVocab.insert)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val insert: List<Any> = emptyList(),
    @get:Schema(
        name = "kss:insert",
        description = "List of triples to be deleted, or a [JSONata](https://jsonata.org) template string to be applied to the results of the with-clause.",
        example = "[ { \"@id\": \"ex:123\", \"ex:givenName\": \"Alice\" } ]"
    )
    @JsonProperty(KvasirVocab.delete)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val delete: List<Any> = emptyList(),
) {

    init {
        require(insert.isNotEmpty() || delete.isNotEmpty()) {
            "At least one of insert or delete properties must be provided"
        }
        require(insert.filterIsInstance<String>().isNotEmpty() && with == null) {
            "Insert templates require a with-clause"
        }
        require(delete.filterIsInstance<String>().isNotEmpty() && with == null) {
            "Delete templates require a with-clause"
        }
    }

    fun toChangeRequest(podId: String, uriInfo: UriInfo): ChangeRequest {
        return ChangeRequest(
            context = context,
            podId = podId,
            assert = assert,
            with = with,
            insert = insert.map {
                if (it is Map<*, *>) assignIds(it as Map<String, Any>, uriInfo) else it
            },
            delete = delete
        )
    }

    // Assigns a random UUID to the @id field of the entity and all its nested entities (if not already present).
    private fun assignIds(entity: Map<String, Any>, uriInfo: UriInfo): Map<String, Any> {
        val id = (entity["@id"] as? String) ?: uriInfo.requestUri.resolve("#${UUID.randomUUID()}").toString()
        return mapOf("@id" to id).plus(entity.entries.filterNot { (key, _) -> key == "@id" }.associate { (key, value) ->
            key to when (value) {
                is Map<*, *> -> assignIds(value as Map<String, Any>, uriInfo)
                is List<*> -> value.map { if (it is Map<*, *>) assignIds(it as Map<String, Any>, uriInfo) else it }
                else -> value
            }
        })
    }

}