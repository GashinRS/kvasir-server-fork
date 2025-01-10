package kvasir.services.api.pods

import com.fasterxml.jackson.annotation.JsonProperty
import io.minio.MakeBucketArgs
import io.minio.MinioAsyncClient
import io.minio.SetBucketVersioningArgs
import io.minio.messages.VersioningConfiguration
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodEvent
import kvasir.definitions.kg.PodEventType
import kvasir.definitions.kg.PodStore
import kvasir.definitions.messaging.Channels
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel

@Tag(name = ApiDocTags.PODS_API)
@Path((""))
class PodManagementApi(
    private val podStore: PodStore,
    private val minioClient: MinioAsyncClient,
    @Channel(Channels.POD_EVENT_PUBLISH) private val podEventEmitter: MutinyEmitter<PodEvent>,
    private val uriInfo: UriInfo
) {

    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    fun register(@Context uriInfo: UriInfo, input: RegisterPodInput): Uni<Response> {
        // This basic implementation check if the pod already exists in a non-atomic way.
        val podId = uriInfo.absolutePathBuilder.path(input.name).build().toString()
        return podStore.getById(podId).chain { existingPod ->
            if (existingPod != null) {
                Uni.createFrom().item(Response.status(Response.Status.CONFLICT).build())
            } else {
                podStore.persist(Pod(podId, input.configuration))
                    .chain { _ ->
                        // Initialize a new S3 bucket for the pod
                        Uni.createFrom().completionStage(
                            minioClient.makeBucket(
                                MakeBucketArgs.builder().bucket(S3Utils.getBucket(podId)).build()
                            )
                        ).chain { _ ->
                            // Enable versioning for the bucket
                            Uni.createFrom().completionStage(
                                minioClient.setBucketVersioning(
                                    SetBucketVersioningArgs.builder()
                                        .bucket(S3Utils.getBucket(podId))
                                        .config(VersioningConfiguration(VersioningConfiguration.Status.ENABLED, true))
                                        .build()
                                )
                            )
                        }
                    }
                    .chain { _ -> podEventEmitter.send(PodEvent(PodEventType.CREATED, input.name)) }
                    .map { Response.created(uriInfo.absolutePathBuilder.path(input.name).build()).build() }
            }
        }
    }

    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    fun list(): Uni<List<PodInfo>> {
        return podStore.list().map { result ->
            result.map { pod -> PodInfo(pod.id, "${pod.id}/.profile") }
        }
    }

    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Path("{podId}")
    fun get(@PathParam("podId") podId: String): Uni<Pod> {
        val podId = uriInfo.absolutePath.toString()
        return podStore.getById(podId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform { it!! }
    }

    @PermitAll
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Path("{podId}/.profile")
    fun getProfile(@PathParam("podId") podId: String): Uni<PodPublicProfile> {
        // This is a simple example of a profile endpoint that returns a public profile of the pod.
        // Could fetch data from the KG, but for now, extracts some static info
        val podId = uriInfo.absolutePath.toString().substringBeforeLast("/.profile")
        return podStore.getById(podId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform {
                PodPublicProfile("${podId}/.profile", it!!.getAuthConfiguration()!!.serverUrl)
            }
    }

    @PUT
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Path("{podId}")
    fun update(@PathParam("podId") podId: String, input: UpdatePodInput): Uni<Response> {
        val podId = uriInfo.absolutePath.toString()
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
        val podId = uriInfo.absolutePath.toString()
        return podStore.deleteById(podId)
            .chain { _ -> podEventEmitter.send(PodEvent(PodEventType.DELETED, podId)) }
            .map { Response.noContent().build() }
    }

}

data class RegisterPodInput(
    @JsonProperty(KvasirVocab.name)
    val name: String,
    @JsonProperty(KvasirVocab.configuration)
    val configuration: Map<String, Any>,
)

data class UpdatePodInput(
    @JsonProperty(KvasirVocab.configuration)
    val configuration: Map<String, Any>,
)

data class PodInfo(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.profile)
    val profile: String
)

data class PodPublicProfile(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.authServerUrl)
    val authServerUri: String
)