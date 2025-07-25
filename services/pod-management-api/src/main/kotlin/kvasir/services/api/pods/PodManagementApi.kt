package kvasir.services.api.pods

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import idlab.quarkus.ext.pep.openfga.runtime.annotations.OpenFgaPolicyEnforcer
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriBuilder
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.config.GenerateClientConfig
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.config.PodConfig
import kvasir.definitions.kg.LifeCycleEvent
import kvasir.definitions.kg.LifeCycleEventType
import kvasir.definitions.kg.Pod
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getChildUri
import kvasir.utils.http.getParentUri
import kvasir.utils.pod.PodSetupHelper
import org.apache.http.HttpStatus
import org.eclipse.microprofile.config.ConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import org.jboss.resteasy.reactive.RestResponse
import java.net.URI
import java.util.*
import kotlin.jvm.optionals.getOrNull

@Path("")
class PodManagementApi(
    private val uriInfo: KvasirUriInfo,
    @ConfigProperty(name = KvasirConfig.WEBCLIENT_URI_PROPERTY, defaultValue = KvasirConfig.WEBCLIENT_URI_DEFAULT)
    private val webclientUri: Optional<URI>,
    @Channel(Channels.LIFECYCLE_EVENTS_PUBLISH)
    private val lifecycleEventEmitter: MutinyEmitter<LifeCycleEvent>
) : PodSetupHelper() {

    @PermitAll
    @POST
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Tag(name = ApiDocTags.PODS_API)
    @Operation(
        summary = "Register a new pod",
        description = "Registers a new pod with the given name and configuration."
    )
    @APIResponse(responseCode = "201", description = "Pod successfully created.")
    fun register(input: RegisterPodInput): Uni<Response> {
        // This basic implementation check if the pod already exists in a non-atomic way.
        val fqPodId = uriInfo.getResourceUri().getChildUri(input.name).toASCIIString()
        return createPod(fqPodId, input, errorWhenExists = true)
            .chain { _ ->
                // Emit life-cycle event when the Pod was successfully created
                lifecycleEventEmitter.send(
                    LifeCycleEvent(
                        type = LifeCycleEventType.POD_CREATED,
                        podId = fqPodId
                    )
                )
            }
            .map { Response.created(URI.create(fqPodId)).build() }
            .onFailure(IllegalStateException::class.java).recoverWithItem { _ ->
                Response.status(HttpStatus.SC_CONFLICT).entity("Pod with ID $fqPodId already exists.").build()
            }
    }

    @PermitAll
    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Tag(name = ApiDocTags.PODS_API)
    @Operation(
        summary = "List all pods",
        description = "Returns a list of all registered pods with a reference to their public profiles."
    )
    @APIResponseSchema(PodInfoGraph::class)
    fun list(): Uni<List<PodInfo>> {
        return podStore.list().map { result ->
            result.map { pod -> PodInfo(pod.id, "${pod.id}/.profile") }
        }
    }

    @GET
    @Produces(JSON_LD_MEDIA_TYPE)
    @Path("{podId}")
    @Tag(name = ApiDocTags.PODS_API)
    @Operation(
        summary = "Get pod details",
        description = "Returns the details of a specific pod identified by its ID."
    )
    @OpenFgaPolicyEnforcer
    fun get(@PathParam("podId") podId: String): Uni<Pod> {
        val fqPodId = uriInfo.getResourceUri().toASCIIString()
        return podStore.getById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform { it!! }
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("{podId}")
    @Tag(name = ApiDocTags.PODS_API)
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
    @Tag(name = ApiDocTags.PODS_API)
    @Operation(
        summary = "Get pod public profile",
        description = "Returns the public profile of a pod, which includes its ID and authentication server URL."
    )
    fun getProfile(@PathParam("podId") podId: String): Uni<PodPublicProfile> {
        // This is a simple example of a profile endpoint that returns a public profile of the pod.
        // Could fetch data from the KG, but for now, extracts some static info
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        return podStore.getById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform { pod ->
                PodPublicProfile(
                    "${fqPodId}/.profile",
                    pod!!.getAuthConfiguration()?.get("authServerUrl")?.let { it as String }
                        ?: ConfigProvider.getConfig()
                            .getOptionalValue("quarkus.oidc.auth-server-url", String::class.java).getOrNull()
                )
            }
    }

    @PUT
    @Consumes(JSON_LD_MEDIA_TYPE)
    @Path("{podId}")
    @Tag(name = ApiDocTags.PODS_API)
    @Operation(
        summary = "Update pod configuration",
        description = "Updates the configuration of an existing pod identified by its ID."
    )
    @APIResponse(responseCode = "204", description = "Pod configuration updated.")
    @OpenFgaPolicyEnforcer
    fun update(@PathParam("podId") podId: String, input: UpdatePodInput): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().toASCIIString()
        return podStore.getById(fqPodId).chain { existingPod ->
            if (existingPod == null) {
                Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build())
            } else {
                podStore.persist(existingPod.copy(configuration = input.configuration))
                    .chain { _ ->
                        // Emit life-cycle event
                        lifecycleEventEmitter.send(
                            LifeCycleEvent(
                                type = LifeCycleEventType.POD_UPDATED,
                                podId = fqPodId
                            )
                        )
                    }
                    .map { Response.noContent().build() }
            }
        }
    }

    @DELETE
    @Path("{podId}")
    @Tag(name = ApiDocTags.PODS_API)
    @Operation(
        summary = "Delete pod",
        description = "Deletes an existing pod identified by its ID. This will also remove all associated data."
    )
    @APIResponse(responseCode = "201", description = "Pod successfully deleted.")
    @OpenFgaPolicyEnforcer
    fun delete(@PathParam("podId") podId: String): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().toASCIIString()
        // TODO: delete all content (incl. S3 bucket, KG data, etc.)
        return podStore.deleteById(fqPodId)
            .chain { _ ->
                // Emit life-cycle event
                lifecycleEventEmitter.send(LifeCycleEvent(type = LifeCycleEventType.POD_DELETED, podId = fqPodId))
            }
            .map { Response.noContent().build() }
    }

}

@GenerateNoArgConstructor
data class RegisterPodInput(
    @get:JsonProperty(KvasirVocab.name)
    val name: String,
    @get:JsonProperty(KvasirVocab.ownerUserId)
    val ownerUserId: String,
    @get:JsonProperty(KvasirVocab.configuration)
    val configuration: Map<String, Any>,
) : PodConfig {

    override fun name(): String = name

    override fun ownerUserId(): Optional<String> = Optional.of(ownerUserId)

    override fun configuration(): Map<String, Any> = configuration

    override fun generateClients(): Optional<List<GenerateClientConfig>> = Optional.empty()
}

@GenerateNoArgConstructor
data class UpdatePodInput(
    @get:JsonProperty(KvasirVocab.configuration)
    val configuration: Map<String, Any>,
)

@GenerateNoArgConstructor
data class PodInfo(
    @get:JsonProperty(JsonLdKeywords.id)
    val id: String,
    @get:JsonProperty(KvasirVocab.profile)
    val profile: String
)

@GenerateNoArgConstructor
data class PodInfoGraph(
    @get:JsonProperty(JsonLdKeywords.graph)
    val pods: List<PodInfo>
)

@GenerateNoArgConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class PodPublicProfile(
    @get:JsonProperty(JsonLdKeywords.id)
    val id: String,
    @get:JsonProperty(KvasirVocab.authServerUrl)
    val authServerUri: String? = null
)
