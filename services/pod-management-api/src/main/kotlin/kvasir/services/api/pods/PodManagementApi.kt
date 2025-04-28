package kvasir.services.api.pods

import com.fasterxml.jackson.annotation.JsonProperty
import io.minio.MakeBucketArgs
import io.minio.MinioAsyncClient
import io.minio.SetBucketVersioningArgs
import io.minio.messages.VersioningConfiguration
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.eventbus.EventBus
import jakarta.annotation.security.PermitAll
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.*
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.*
import kvasir.definitions.messaging.Channels
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getChildUri
import kvasir.utils.http.getParentUri
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import org.jboss.resteasy.reactive.RestResponse
import java.net.URI
import java.util.*

@Tag(name = ApiDocTags.PODS_API)
@Path((""))
class PodManagementApi(
    private val podStore: PodStore,
    private val minioClient: MinioAsyncClient,
    private val uriInfo: KvasirUriInfo,
    @ConfigProperty(name = KvasirConfig.WEBCLIENT_URI_PROPERTY, defaultValue = KvasirConfig.WEBCLIENT_URI_DEFAULT)
    private val webclientUri: Optional<URI>,
    private val podAuthInitializer: Instance<PodAuthInitializer>,
    @Channel(Channels.LIFECYCLE_EVENTS_PUBLISH)
    private val lifecycleEventEmitter: MutinyEmitter<LifeCycleEvent>,
    private val eventBus: EventBus
) {

    @PermitAll
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    fun register(input: RegisterPodInput): Uni<Response> {
        // This basic implementation check if the pod already exists in a non-atomic way.
        val fqPodId = uriInfo.getResourceUri().getChildUri(input.name).toASCIIString()
        return podStore.getById(fqPodId).chain { existingPod ->
            if (existingPod != null) {
                Uni.createFrom().item(Response.status(Response.Status.CONFLICT).build())
            } else {
                // Initialize auth config with policy enforcement provider (if no config specified)
                if (!input.configuration.containsKey(KvasirVocab.authConfiguration)) {
                    podAuthInitializer.get().initialize(fqPodId, input.name).map { it }
                } else {
                    Uni.createFrom().nullItem()
                }
                    .chain { authConfig ->
                        val config =
                            if (authConfig != null) input.configuration.plus(KvasirVocab.authConfiguration to authConfig) else input.configuration
                        podStore.persist(Pod(fqPodId, config))
                    }
                    .chain { _ ->
                        // Initialize a new S3 bucket for the pod
                        Uni.createFrom().completionStage(
                            minioClient.makeBucket(
                                MakeBucketArgs.builder().bucket(S3Utils.getBucket(fqPodId)).build()
                            )
                        )
                            .chain { _ ->
                                // Enable versioning for the bucket
                                Uni.createFrom().completionStage(
                                    minioClient.setBucketVersioning(
                                        SetBucketVersioningArgs.builder()
                                            .bucket(S3Utils.getBucket(fqPodId))
                                            .config(
                                                VersioningConfiguration(
                                                    VersioningConfiguration.Status.ENABLED,
                                                    true
                                                )
                                            )
                                            .build()
                                    )
                                )
                            }
                    }
                    .chain { _ ->
                        // Emit life-cycle event
                        lifecycleEventEmitter.send(
                            LifeCycleEvent(
                                type = LifeCycleEventType.POD_CREATED,
                                podId = fqPodId
                            )
                        )
                    }
                    .map { Response.created(URI.create(fqPodId)).build() }
            }
        }
    }

    @PermitAll
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
        val fqPodId = uriInfo.getResourceUri().toASCIIString()
        return podStore.getById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform { it!! }
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("{podId}")
    fun getHtml(@PathParam("podId") podId: String): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().toASCIIString()
        return podStore.getById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transformToUni { item ->
                if (webclientUri.isPresent) {
                    val uiUri = UriBuilder.fromUri(webclientUri.get()).path("/force-session/${podId}").build()
                    Uni.createFrom().item(RestResponse.seeOther<Void>(uiUri).toResponse())
                } else {
                    get(podId).map { Response.ok(it, JSON_LD_MEDIA_TYPE).build() }
                }
            }
    }

    @PermitAll
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Path("{podId}/.profile")
    fun getProfile(@PathParam("podId") podId: String): Uni<PodPublicProfile> {
        // This is a simple example of a profile endpoint that returns a public profile of the pod.
        // Could fetch data from the KG, but for now, extracts some static info
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return podStore.getById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform {
                PodPublicProfile("${fqPodId}/.profile", it!!.getAuthConfiguration()!!.serverUrl)
            }
    }

    @PUT
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Path("{podId}")
    fun update(@PathParam("podId") podId: String, input: UpdatePodInput): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().toASCIIString()
        return podStore.getById(fqPodId).chain { existingPod ->
            if (existingPod == null) {
                Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build())
            } else {
                podStore.persist(existingPod.copy(configuration = input.configuration))
                    .chain { _ ->
                        // Emit life-cycle event
                        lifecycleEventEmitter.send(LifeCycleEvent(type = LifeCycleEventType.POD_UPDATED, podId = fqPodId))
                    }
                    .map { Response.noContent().build() }
            }
        }
    }

    @DELETE
    @Path("{podId}")
    fun delete(@PathParam("podId") podId: String): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().toASCIIString()
        return podStore.deleteById(fqPodId)
            .chain { _ ->
                // Emit life-cycle event
                lifecycleEventEmitter.send(LifeCycleEvent(type = LifeCycleEventType.POD_DELETED, podId = fqPodId))
            }
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
