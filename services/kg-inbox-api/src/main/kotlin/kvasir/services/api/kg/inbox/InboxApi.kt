package kvasir.services.api.kg.inbox

import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.changeops.Assertion
import kvasir.definitions.openapi.ApiDocConstants
import kvasir.definitions.openapi.ApiDocTags
import org.apache.kafka.common.errors.RecordTooLargeException
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel

@Tag(name = ApiDocTags.KNOWLEDGE_GRAPH_API)
@Path("{podId}/kg/inbox")
class InboxApi(
    @Channel("change_requests_publish")
    private val changeEmitter: MutinyEmitter<ChangeRequest>
) {

    @POST
    @Consumes("application/ld+json", MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Perform mutations on the KG.",
        description = "Post a change request, containing the requested mutations, to the inbox of the specified pod.",
    )
    @APIResponse(responseCode = "202", description = "Change request accepted.")
    fun postJsonLDChangeRequest(
        @PathParam("podId") podId: String,
        input: ChangeRequestInput
    ): Uni<Response> {
        val changeCommand = input.toChangeRequest(podId)
        println(changeCommand)
        return changeEmitter.sendMessage(KafkaRecord.of(podId, changeCommand))
            .map { _ -> Response.accepted().build() }
            .onFailure(RecordTooLargeException::class.java)
            .recoverWithItem { _ -> Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build() }
    }

}

data class ChangeRequestInput(
    @get:Schema(
        name = "@context",
        description = "The JSON-LD context for the change request.",
        example = ApiDocConstants.JSON_LD_CONTEXT_EXAMPLE_1
    )
    val context: Map<String, Any> = emptyMap(),
    @get:Schema(name = "kss:graph", description = "Optional named graph IRI to which the change request applies.")
    val graph: String = "",
    @get:Schema(
        name = "kss:assert",
        description = "List of assertions to be checked before applying the change request."
    )
    val assert: List<Assertion> = emptyList(),
    @get:Schema(
        name = "kss:where",
        description = "Optional GraphQL query where matches are required to be found for the change request to be applied. Results are bound to the field names in the query and can be used in the insert and delete operations (via templates).",
        example = "{ id ex_givenName(_: \"Bob\") }"
    )
    val where: String? = null,
    @get:Schema(
        name = "kss:insert",
        description = "List of triples to be inserted, or a [JSONata](https://jsonata.org) template string to be applied to the results of the where-clause.",
        example = "[ { \"@id\": \"ex:123\", \"ex:givenName\": \"Bob\" } ]"
    )
    val insert: List<Any> = emptyList(),
    @get:Schema(
        name = "kss:insert",
        description = "List of triples to be deleted, or a [JSONata](https://jsonata.org) template string to be applied to the results of the where-clause.",
        example = "[ { \"@id\": \"ex:123\", \"ex:givenName\": \"Alice\" } ]"
    )
    val delete: List<Any> = emptyList(),
) {

    fun toChangeRequest(podId: String): ChangeRequest {
        return ChangeRequest(
            context = context,
            podId = podId,
            graph = graph,
            assert = assert,
            where = where,
            insert = insert,
            delete = delete
        )
    }

}