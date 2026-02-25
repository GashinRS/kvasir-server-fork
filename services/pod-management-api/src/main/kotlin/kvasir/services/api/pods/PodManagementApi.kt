package kvasir.services.api.pods

import com.fasterxml.jackson.annotation.JsonProperty
import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import jakarta.annotation.security.PermitAll
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriBuilder
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.auth.AuthConstants
import kvasir.definitions.auth.AuthConstants.REDACTED_CREDENTIAL
import kvasir.definitions.config.BootstrapPodConfig
import kvasir.definitions.config.GenerateClientConfig
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.config.OpenFgaClientConfig
import kvasir.definitions.config.OpenFgaPermissionConfig
import kvasir.definitions.config.PodConfig
import kvasir.definitions.config.PodConfigOverride
import kvasir.definitions.kg.LifeCycleEvent
import kvasir.definitions.kg.LifeCycleEventType
import kvasir.definitions.kg.Pod
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JSON_LD_MEDIA_TYPE
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.plugins.messaging.kafka.Channels
import kvasir.plugins.policyagent.openfga.delegated.uma.UmaClientManager
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getChildUri
import kvasir.utils.http.getParentUri
import kvasir.utils.pod.PodConfigProvider
import kvasir.utils.pod.PodSetupHelper
import org.apache.http.HttpStatus
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import org.jboss.resteasy.reactive.RestResponse
import java.net.URI
import java.util.*

@Path("")
class PodManagementApi(
    private val uriInfo: KvasirUriInfo,
    private val httpConfig: HttpConfig,
    private val podSetupHelper: PodSetupHelper,
    private val repositoryFactory: RepositoryFactory,
    @param:Channel(Channels.LIFECYCLE_EVENTS_PUBLISH)
    private val lifecycleEventEmitter: MutinyEmitter<LifeCycleEvent>,
    private val securityIdentity: Instance<SecurityIdentity>,
    private val podConfigProvider: PodConfigProvider,
    private val platformPodConfig: PodConfig,
    private val umaClientManager: UmaClientManager
) {

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
        return podSetupHelper.createPod(fqPodId, input, input.configuration, errorWhenExists = true)
            .chain { _ ->
                // Emit life-cycle event when the Pod was successfully created
                lifecycleEventEmitter.send(
                    LifeCycleEvent(
                        eventType = LifeCycleEventType.POD_CREATED,
                        podId = fqPodId,
                        requestingUser = securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                            ?: AuthConstants.ANONYMOUS_USERNAME,
                    )
                )
            }
            .map { Response.created(URI.create(fqPodId)).build() }
            .onFailure(IllegalStateException::class.java).recoverWithItem { _ ->
                Response.status(HttpStatus.SC_CONFLICT).entity("Pod with ID $fqPodId already exists.").build()
            }
    }

    private fun listPodInfo(): Uni<List<PodInfo>> =
        repositoryFactory.getRepository(Pod::class).find().map { result ->
            result.items.map { pod -> PodInfo(pod.id) }
        };


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
        return listPodInfo();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun getHtml(): Uni<Response> {
        val uiUri = UriBuilder.fromUri(httpConfig.webclientUri()).build();
        return if (httpConfig.redirectToWebclient()) Uni.createFrom()
            .item(Response.seeOther(uiUri).build()) else listPodInfo().map {
            Response.ok(it, JSON_LD_MEDIA_TYPE).build()
        };
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
        return repositoryFactory.getRepository(Pod::class).findById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform {
                it!!.configuration = redactKeys(setOf("auth.uma.client-id", "auth.uma.client-secret"), it.configuration)
                it
            }
    }




    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("{podId}")
    @Tag(name = ApiDocTags.PODS_API)
    fun getHtml(@PathParam("podId") podId: String): Uni<Response> {
        val fqPodId = uriInfo.getResourceUri().toASCIIString()
        return repositoryFactory.getRepository(Pod::class).findById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transformToUni { item ->
                val uiUri = UriBuilder.fromUri(httpConfig.webclientUri()).path("/force-session/${podId}").build()
                Uni.createFrom().item(RestResponse.seeOther<Void>(uiUri).toResponse())
            }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{podId}/runtime-config")
    @Tag(name = ApiDocTags.PODS_API)
    @Operation(
        summary = "Get pod runtime config",
        description = "Returns the actual pod config used at runtime for a a specific pod identified by its ID. This overlays the user settings with the system configuration."
    )
    @OpenFgaPolicyEnforcer
    fun getRuntimeConfig(@PathParam("podId") podId: String): Uni<PodConfig> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(1).toASCIIString()
        return podConfigProvider.getPodConfigById(fqPodId)
            .onItem().ifNull().failWith(NotFoundException("Pod not found"))
            .onItem().ifNotNull().transform { it!! }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{podId}/platform-config")
    @Tag(name = ApiDocTags.PODS_API)
    @Operation(
        summary = "Get the default pod config values, defined at platform-level.",
        description = "Returns the default pod config set via the system configuration."
    )
    @OpenFgaPolicyEnforcer
    fun getPlatformConfig(@PathParam("podId") podId: String): Uni<PodConfig> {
        return Uni.createFrom().item(platformPodConfig)
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
        val podStore = repositoryFactory.getRepository(Pod::class)
        return podStore.findById(fqPodId).chain { existingPod ->
            if (existingPod == null) {
                Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build())
            } else {
                podStore.persist(existingPod.copyAndKeepUmaCredentials(input.configuration))
                    // When updating the podConfig, it is best to invalidate any cached UmaClients for this pod
                    .chain { _ ->
                        umaClientManager.invalidateUmaClient(fqPodId)
                    }
                    .chain { _ ->
                        // Emit life-cycle event
                        lifecycleEventEmitter.send(
                            LifeCycleEvent(
                                eventType = LifeCycleEventType.POD_UPDATED,
                                podId = fqPodId,
                                requestingUser = securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                                    ?: AuthConstants.ANONYMOUS_USERNAME
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
        return repositoryFactory.getRepository(Pod::class).deleteById(fqPodId)
            .chain { _ ->
                // Emit life-cycle event
                lifecycleEventEmitter.send(
                    LifeCycleEvent(
                        eventType = LifeCycleEventType.POD_DELETED,
                        podId = fqPodId,
                        requestingUser = securityIdentity.takeIf { it.isResolvable }?.get()?.principal?.name
                            ?: AuthConstants.ANONYMOUS_USERNAME
                    )
                )
            }
            .map { Response.noContent().build() }
    }

    private fun redactKeys(keyPaths: Set<String>, jsonString: String): String {
        val json = JsonObject(jsonString)
        for (path in keyPaths) {
            val keys = path.split(".")
            var idx = 0;
            var obj = json;
            while (idx < keys.size-1) {
                obj = obj.getJsonObject(keys[idx], JsonObject())
                idx++;
            }
            val finalKey = keys[keys.size - 1]
            if (obj.getString(finalKey) != null) {
                obj.put(finalKey, REDACTED_CREDENTIAL)
            }
        }
        return json.encode();
    }

}

@GenerateNoArgConstructor
data class RegisterPodInput(
    val name: String,
    val ownerUserId: String,
    val configuration: String = "{}",
    val autoRegisterUma: Boolean = false,
    val autoRegisterHttpEndpointPolicyEnforcer: Boolean = false,
    val adminClientId: String? = null,
    val adminClientSecret: String? = null
) : BootstrapPodConfig {

    override fun name(): String = name

    override fun ownerUserId(): Optional<String> = Optional.of(ownerUserId)
    override fun autoRegisterUma(): Boolean = autoRegisterUma

    override fun autoRegisterHttpEndpointPolicyEnforcer(): Boolean = autoRegisterHttpEndpointPolicyEnforcer

    override fun configuration(): PodConfigOverride {
        return PodConfigProvider.deserializePodConfigOverride(configuration)
    }

    override fun generateClients(): Optional<List<GenerateClientConfig>> {
        return if (adminClientId != null) {
            Optional.of(
                listOf(
                    object : GenerateClientConfig {
                        override fun clientId(): String = adminClientId
                        override fun clientSecret(): Optional<String> = Optional.ofNullable(adminClientSecret)
                        override fun redirectUris(): Optional<List<String>> = Optional.empty()
                        override fun enableForcePKCE(): Boolean = false
                        override fun openfga(): Optional<OpenFgaClientConfig> = Optional.of(
                            object : OpenFgaClientConfig {
                                override fun relationships(): Optional<List<OpenFgaPermissionConfig>> {
                                    return Optional.of(
                                        listOf(
                                            object : OpenFgaPermissionConfig {
                                                override fun targetResource(): String = "/"
                                                override fun relations(): List<String> =
                                                    listOf("reader", "writer", "deleter")
                                            }
                                        ))
                                }
                            }
                        )

                        override fun enableServiceAccount(): Boolean = true

                    }
                ))
        } else {
            Optional.empty()
        }
    }
}

@GenerateNoArgConstructor
data class UpdatePodInput(
    val configuration: String,
)

@GenerateNoArgConstructor
data class PodInfo(
    @get:JsonProperty(JsonLdKeywords.id)
    val id: String,
) {
    @JsonProperty(JsonLdKeywords.type)
    fun getType() = KvasirVocab.Pod
}

@GenerateNoArgConstructor
data class PodInfoGraph(
    @get:JsonProperty(JsonLdKeywords.graph)
    val pods: List<PodInfo>
)
