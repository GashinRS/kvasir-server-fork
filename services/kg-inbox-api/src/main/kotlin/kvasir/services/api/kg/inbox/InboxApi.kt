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
import org.apache.kafka.common.errors.RecordTooLargeException
import org.eclipse.microprofile.reactive.messaging.Channel

@Path("{podId}/kg/inbox")
class InboxApi(
    @Channel("change_requests_publish")
    private val changeEmitter: MutinyEmitter<ChangeRequest>
) {

    @POST
    @Consumes("application/ld+json", MediaType.APPLICATION_JSON)
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
    val graph: String = "",
    val assertions: List<Assertion> = emptyList(),
    val operations: List<Map<String, Any>> = emptyList(),
    val inserts: List<Map<String, Any>> = emptyList(),
    val deletes: List<Map<String, Any>> = emptyList(),
    val userProvidedContext: Map<String, Any> = emptyMap()
) {

    fun toChangeRequest(podId: String): ChangeRequest {
        return ChangeRequest(
            podId = podId,
            graph = graph,
            assertions = assertions,
            operations = operations,
            inserts = inserts,
            deletes = deletes,
            userProvidedContext = userProvidedContext
        )
    }

}