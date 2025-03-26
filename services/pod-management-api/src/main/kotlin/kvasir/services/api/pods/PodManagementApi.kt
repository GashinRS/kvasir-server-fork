package kvasir.services.api.pods

import com.fasterxml.jackson.annotation.JsonProperty
import io.minio.MakeBucketArgs
import io.minio.MinioAsyncClient
import io.minio.SetBucketVersioningArgs
import io.minio.messages.VersioningConfiguration
import io.quarkus.security.PermissionsAllowed
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.PermitAll
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodAuthInitializer
import kvasir.definitions.kg.PodStore
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.s3.S3Utils
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.resteasy.reactive.RestResponse
import java.net.URI
import java.util.Optional

@Tag(name = ApiDocTags.PODS_API)
@Path((""))
class PodManagementApi(
    private val podStore: PodStore,
    private val minioClient: MinioAsyncClient,
    private val uriInfo: UriInfo,
    @ConfigProperty(name = "kvasir.webclient-uri")
    private val webclientUri: Optional<URI>,
    private val podAuthInitializer: Instance<PodAuthInitializer>
) {

    @PermitAll
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    fun register(@Context uriInfo: UriInfo, input: RegisterPodInput): Uni<Response> {
        // This basic implementation check if the pod already exists in a non-atomic way.
        val podId = uriInfo.absolutePathBuilder.path(input.name).build().toString()
        return podStore.getById(podId).chain { existingPod ->
            if (existingPod != null) {
                Uni.createFrom().item(Response.status(Response.Status.CONFLICT).build())
            } else {
                // Initialize auth config with policy enforcement provider (if no config specified)
                if (!input.configuration.containsKey(KvasirVocab.authConfiguration)) {
                    podAuthInitializer.get().initialize(podId, input.name).map { it }
                } else {
                    Uni.createFrom().nullItem()
                }
                    .chain { authConfig ->
                        val config =
                            if (authConfig != null) input.configuration.plus(KvasirVocab.authConfiguration to authConfig) else input.configuration
                        podStore.persist(Pod(podId, config))
                    }
                    .chain { _ ->
                        // Initialize a new S3 bucket for the pod
                        Uni.createFrom().completionStage(
                            minioClient.makeBucket(
                                MakeBucketArgs.builder().bucket(S3Utils.getBucket(podId)).build()
                            )
                        )
                            .chain { _ ->
                                // Enable versioning for the bucket
                                Uni.createFrom().completionStage(
                                    minioClient.setBucketVersioning(
                                        SetBucketVersioningArgs.builder()
                                            .bucket(S3Utils.getBucket(podId))
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
                    .map { Response.created(uriInfo.absolutePathBuilder.path(input.name).build()).build() }
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
        val podId = uriInfo.absolutePath.toString()
        return podStore.getById(podId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform { it!! }
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("{podId}")
    fun getHtml(@PathParam("podId") podId: String, @Context uriInfo: UriInfo): Uni<Response> {
        val fullPodId = uriInfo.absolutePath.toString()
        return podStore.getById(fullPodId)
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
                    .map { Response.noContent().build() }
            }
        }
    }

    @DELETE
    @Path("{podId}")
    fun delete(@PathParam("podId") podId: String): Uni<Response> {
        val podId = uriInfo.absolutePath.toString()
        return podStore.deleteById(podId)
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