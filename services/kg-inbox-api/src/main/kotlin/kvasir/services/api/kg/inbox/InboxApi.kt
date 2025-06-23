package kvasir.services.api.kg.inbox

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Response
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
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getChildUri
import kvasir.utils.http.getParentUri
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
    private val podStore: PodStore,
    private val uriInfo: KvasirUriInfo
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
        input: ChangeRequestInput
    ): Uni<Response> {
        val podUri = uriInfo.getResourceUri().getParentUri()
        val fqPodId = podUri.toString()
        return podStore.getById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transformToUni { pod ->
                val changeCommand = input.toChangeRequest(podUri, uriInfo)
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
        input: ChangeRequestInput
    ): Uni<Response> {
        val podUri = uriInfo.getResourceUri().getParentUri(3)
        val fqPodId = podUri.toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return sliceStore.getById(fqPodId, fqSliceId)
            .onItem().ifNull().failWith(NotFoundException("Slice not found"))
            .onItem().ifNotNull().transformToUni { slice ->
                if (slice!!.supportsChanges) {
                    val changeCommand = input.toChangeRequest(podUri, uriInfo, fqSliceId)
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
        description = "The JSON-LD context for the change request.",
        example = ApiDocConstants.JSON_LD_CONTEXT_EXAMPLE
    )
    @get:JsonProperty(JsonLdKeywords.context)
    val context: Map<String, Any> = emptyMap(),
    @get:Schema(
        description = "List of assertions to be checked before applying the change request."
    )
    @get:JsonProperty(KvasirVocab.assert)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val assert: List<Assertion> = emptyList(),
    @get:Schema(
        description = "Optional GraphQL query where matches are required to be found for the change request to be applied. Results are bound to the field names in the query and can be used in the insert and delete operations (via templates).",
        example = "ex_Person { id ex_givenName @filter(if: \"it==Bob\") }"
    )
    @get:JsonProperty(KvasirVocab.with)
    val with: String? = null,
    @get:Schema(
        description = "List of triples to be inserted, or a [JSONata](https://jsonata.org) template string to be applied to the results of the with-clause.",
        example = "[ { \"@id\": \"ex:123\", \"ex:givenName\": \"Bob\" } ]"
    )
    @get:JsonProperty(KvasirVocab.insert)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val insert: List<Any> = emptyList(),
    @get:Schema(
        description = "List of triples to be deleted, or a [JSONata](https://jsonata.org) template string to be applied to the results of the with-clause.",
        example = "[ { \"@id\": \"ex:123\", \"ex:givenName\": \"Alice\" } ]"
    )
    @get:JsonProperty(KvasirVocab.delete)
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

    fun toChangeRequest(podId: URI, uriInfo: KvasirUriInfo, sliceId: String? = null): ChangeRequest {
        return ChangeRequest(
            id = ChangeRequestId.generate(uriInfo.getResourceUri().toASCIIString()).encode(),
            context = context,
            podId = podId.toASCIIString(),
            sliceId = sliceId,
            assert = assert,
            with = with,
            insert = insert.map {
                if (it is Map<*, *>) assignIds(it as Map<String, Any>, podId) else it
            },
            delete = delete
        )
    }

    // Assigns a random UUID to the @id field of the entity and all its nested entities (if not already present).
    private fun assignIds(entity: Map<String, Any>, fqPodId: URI): Map<String, Any> {
        // If the entity is a literal, do not assign an id
        if (entity.containsKey(JsonLdKeywords.type) && XSDVocab.literalTypes.contains(entity[JsonLdKeywords.type])) {
            return entity
        }

        val id = (entity["@id"] as? String) ?: fqPodId.getChildUri("#${UUID.randomUUID()}").toString()
        return mapOf("@id" to id).plus(entity.entries.filterNot { (key, _) -> key == "@id" }.associate { (key, value) ->
            key to when (key) {
                JsonLdKeywords.reverse -> value.takeIf { it is Map<*, *> }
                    ?.let { (it as Map<*, *>).mapValues { it.value?.let { assignIdsMapValue(it, fqPodId) } } }
                    ?: throw IllegalArgumentException("@reverse property must be a map")

                else -> assignIdsMapValue(value, fqPodId)
            }
        })
    }

    private fun assignIdsMapValue(value: Any, fqPodId: URI): Any {
        return when (value) {
            is Map<*, *> -> assignIds(value as Map<String, Any>, fqPodId)
            is List<*> -> value.map { if (it is Map<*, *>) assignIds(it as Map<String, Any>, fqPodId) else it }
            else -> value
        }
    }

}
