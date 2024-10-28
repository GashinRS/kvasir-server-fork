package kvasir.services.api.pods

import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodEvent
import kvasir.definitions.kg.PodEventType
import kvasir.definitions.kg.PodStore
import kvasir.definitions.messaging.Channels
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import org.eclipse.microprofile.reactive.messaging.Channel

@Path((""))
class PodManagementApi(
    private val podStore: PodStore,
    @Channel(Channels.POD_EVENT_PUBLISH) private val podEventEmitter: MutinyEmitter<PodEvent>
) {

    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    fun register(@Context uriInfo: UriInfo, input: RegisterPodInput): Uni<Response> {
        // This basic implementation check if the pod already exists in a non-atomic way.
        return podStore.getById(input.id).chain { existingPod ->
            if (existingPod != null) {
                Uni.createFrom().item(Response.status(Response.Status.CONFLICT).build())
            } else {
                podStore.persist(Pod(input.id, input.configuration))
                    .chain { _ -> podEventEmitter.send(PodEvent(PodEventType.CREATED, input.id)) }
                    .map { Response.created(uriInfo.absolutePathBuilder.path(input.id).build()).build() }
            }
        }
    }

    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    fun list(): Uni<List<Pod>> {
        return podStore.list()
    }

    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Path("{podId}")
    fun get(@PathParam("podId") podId: String): Uni<Pod> {
        return podStore.getById(podId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform { it!! }
    }

    @PUT
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Path("{podId}")
    fun update(@PathParam("podId") podId: String, input: UpdatePodInput): Uni<Response> {
        return podStore.getById(podId).chain { existingPod ->
            if (existingPod == null) {
                Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build())
            } else {
                podStore.persist(existingPod.copy(configuration = input.configuration))
                    .chain { _ -> podEventEmitter.send(PodEvent(PodEventType.UPDATED, podId)) }
                    .map { Response.noContent().build() }
            }
        }
    }

    @DELETE
    @Path("{podId}")
    fun delete(@PathParam("podId") podId: String): Uni<Response> {
        return podStore.deleteById(podId)
            .chain { _ -> podEventEmitter.send(PodEvent(PodEventType.DELETED, podId)) }
            .map { Response.noContent().build() }
    }

}

data class RegisterPodInput(
    val id: String,
    val configuration: Map<String, Any>,
)

data class UpdatePodInput(
    val configuration: Map<String, Any>,
)