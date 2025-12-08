package kvasir.services.api.kg.inbox

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Response
import kvasir.definitions.auth.AuthConstants
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.PodStoreFactory
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.slices.SliceStoreFactory
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.http.KvasirUriInfo
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
    private val sliceStoreFactory: SliceStoreFactory,
    private val podStoreFactory: PodStoreFactory,
    private val uriInfo: KvasirUriInfo,
    private val securityIdentity: Instance<SecurityIdentity>
) {

    @Path("{podId}/changes")
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Operation(
        summary = "Perform mutations on the KG.",
        description = "Post a change request, containing the requested mutations, to the inbox of the specified pod.",
    )
    @APIResponse(responseCode = "201", description = "Change request created.")
    @OpenFgaPolicyEnforcer
    fun processChangeRequest(
        @PathParam("podId") podId: String,
        input: ChangeRequestInput
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toString()
        return podStoreFactory.createPodStore().findById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transformToUni { pod ->
                val changeCommand = input.toChangeRequest(
                    fqPodId,
                    uriInfo,
                    securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                        ?: AuthConstants.ANONYMOUS_USERNAME
                )
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
    @OpenFgaPolicyEnforcer
    fun processSliceChangeRequest(
        @PathParam("podId") podId: String,
        @PathParam("sliceId") sliceId: String,
        input: ChangeRequestInput
    ): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(3).toASCIIString()
        val fqSliceId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return sliceStoreFactory.getSliceStore(fqPodId).findById(fqSliceId)
            .onItem().ifNull().failWith(NotFoundException("Slice not found"))
            .onItem().ifNotNull().transformToUni { slice ->
                if (slice!!.supportsChanges) {
                    val changeCommand = input.toChangeRequest(
                        fqPodId,
                        uriInfo,
                        securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                            ?: AuthConstants.ANONYMOUS_USERNAME,
                        fqSliceId
                    )
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

    fun toChangeRequest(
        fqPodId: String,
        uriInfo: KvasirUriInfo,
        principal: String,
        sliceId: String? = null
    ): ChangeRequest {
        val bNodeIdMap = mutableMapOf<String, String>()
        return ChangeRequest(
            id = ChangeRequestId.generate(uriInfo.getResourceUri().toASCIIString()).encode(),
            context = context,
            requestingUser = principal,
            podId = fqPodId,
            sliceId = sliceId,
            assert = assert,
            with = with,
            insert = insert.map {
                if (it is Map<*, *>) assignIds(it as Map<String, Any>, fqPodId, bNodeIdMap) else it
            },
            delete = delete
        )
    }

    // Assigns a random UUID to the @id field of the entity and all its nested entities (if not already present).
    private fun assignIds(
        entity: Map<String, Any>,
        fqPodId: String,
        bnodeIdMAp: MutableMap<String, String>
    ): Map<String, Any> {
        // If the entity is a literal (JSON-LD value type), do not assign an id
        if (entity.containsKey(JsonLdKeywords.type) && entity.containsKey(JsonLdKeywords.value)) {
            return entity
        }
        val entityId = (entity["@id"] as? String)
        val id = when {
            // When a blank node is found: skolemize the id
            entityId != null && entityId.startsWith("_:") -> bnodeIdMAp.getOrPut(entityId) { "$fqPodId#${UUID.randomUUID()}" }
            // When no id is found: assign a new random UUID
            entityId == null -> "$fqPodId#${UUID.randomUUID()}"
            // Otherwise keep the existing id
            else -> entityId
        }
        return mapOf("@id" to id).plus(entity.entries.filterNot { (key, _) -> key == "@id" }.associate { (key, value) ->
            key to when (key) {
                JsonLdKeywords.reverse -> value.takeIf { it is Map<*, *> }
                    ?.let { reverseTarget ->
                        (reverseTarget as Map<*, *>).mapValues { reverseTargetValue ->
                            reverseTargetValue.value?.let {
                                assignIdsMapValue(
                                    it,
                                    fqPodId,
                                    bnodeIdMAp
                                )
                            }
                        }
                    }
                    ?: throw IllegalArgumentException("@reverse property must be a map")

                else -> assignIdsMapValue(value, fqPodId, bnodeIdMAp)
            }
        })
    }

    private fun assignIdsMapValue(value: Any, fqPodId: String, bNodeIdMap: MutableMap<String, String>): Any {
        return when (value) {
            is Map<*, *> -> assignIds(value as Map<String, Any>, fqPodId, bNodeIdMap)
            is List<*> -> value.map {
                if (it is Map<*, *>) assignIds(
                    it as Map<String, Any>,
                    fqPodId,
                    bNodeIdMap
                ) else it
            }

            else -> value
        }
    }

}
