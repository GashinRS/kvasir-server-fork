package kvasir.services.api.kg.inbox

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.PodStore
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.slices.SliceStore
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.idgen.ChangeRequestId
import org.apache.kafka.common.errors.RecordTooLargeException
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import java.net.URI
import java.util.*

@Tag(name = ApiDocTags.KG_CHANGES_API)
@Path("")
class InboxApi(
    @Channel("change_requests_publish")
    private val changeEmitter: MutinyEmitter<ChangeRequest>,
    private val sliceStore: SliceStore,
    private val podStore: PodStore
) {

    @Path("{podId}/changes")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Perform mutations on the KG.",
        description = "Post a change request, containing the requested mutations, to the inbox of the specified pod.",
    )
    @APIResponse(responseCode = "201", description = "Change request created.")
    fun processChangeRequest(
        @PathParam("podId") podId: String,
        @Context
        uriInfo: UriInfo,
        input: ChangeRequestInput
    ): Uni<Response> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/changes")
        return podStore.getById(fqPodId).onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transformToUni { pod ->
                val changeCommand = input.toChangeRequest(fqPodId, uriInfo)
                changeEmitter.sendMessage(KafkaRecord.of(fqPodId, changeCommand))
                    .map { _ -> Response.created(URI.create(changeCommand.id)).build() }
                    .onFailure(RecordTooLargeException::class.java)
                    .recoverWithItem { _ -> Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build() }
            }
    }

    @Path("{podId}/slices/{sliceId}/changes")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Perform mutations on a specific slice of the KG.",
        description = "Post a change request, containing the requested mutations, to a slice inbox of the specified pod.",
    )
    @APIResponse(responseCode = "201", description = "Change request created.")
    fun processSliceChangeRequest(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        @Context
        uriInfo: UriInfo,
        input: ChangeRequestInput
    ): Uni<Response> {
        val fqPodId = uriInfo.absolutePath.toString().substringBefore("/slices/$sliceId/changes")
        val fqSliceId = uriInfo.absolutePath.toString().substringBefore("/changes")
        return sliceStore.getById(fqPodId, fqSliceId)
            .onItem().ifNull().failWith(NotFoundException("Slice not found"))
            .onItem().ifNotNull().transformToUni { slice ->
                if (slice!!.supportsChanges) {
                    val changeCommand = input.toChangeRequest(fqPodId, uriInfo, fqSliceId)
                    // Publish the change request
                    changeEmitter.sendMessage(KafkaRecord.of(fqPodId, changeCommand))
                        .map { _ -> Response.created(URI.create(changeCommand.id)).build() }
                        .onFailure(RecordTooLargeException::class.java)
                        .recoverWithItem { _ -> Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build() }
                } else {
                    Uni.createFrom().item(Response.status(Response.Status.METHOD_NOT_ALLOWED).build())
                }
            }
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
        name = "kss:delete",
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
        require(insert.filterIsInstance<String>().isEmpty() || with != null) {
            "Insert templates require a with-clause"
        }
        require(delete.filterIsInstance<String>().isEmpty() || with != null) {
            "Delete templates require a with-clause"
        }
    }

    fun toChangeRequest(podId: String, uriInfo: UriInfo, sliceId: String? = null): ChangeRequest {
        return ChangeRequest(
            id = ChangeRequestId.generate(uriInfo.absolutePath.toString()).encode(),
            context = context,
            podId = podId,
            sliceId = sliceId,
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
        // If the entity is a literal, do not assign an id
        if (entity.containsKey(JsonLdKeywords.type) && XSDVocab.literalTypes.contains(entity[JsonLdKeywords.type])) {
            return entity
        }

        val id = (entity["@id"] as? String) ?: uriInfo.requestUri.resolve("#${UUID.randomUUID()}").toString()
        return mapOf("@id" to id).plus(entity.entries.filterNot { (key, _) -> key == "@id" }.associate { (key, value) ->
            key to when (key) {
                JsonLdKeywords.reverse -> value.takeIf { it is Map<*, *> }
                    ?.let { (it as Map<*, *>).mapValues { it.value?.let { assignIdsMapValue(it, uriInfo) } } }
                    ?: throw IllegalArgumentException("@reverse property must be a map")

                else -> assignIdsMapValue(value, uriInfo)
            }
        })
    }

    private fun assignIdsMapValue(value: Any, uriInfo: UriInfo): Any {
        return when (value) {
            is Map<*, *> -> assignIds(value as Map<String, Any>, uriInfo)
            is List<*> -> value.map { if (it is Map<*, *>) assignIds(it as Map<String, Any>, uriInfo) else it }
            else -> value
        }
    }

}