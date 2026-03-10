package kvasir.plugins.policyagent.openfga.delegated.uma

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheResult
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.config.UMAConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.Repository
import kvasir.definitions.rdf.JSONObject
import kvasir.utils.pod.PodConfigProvider
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.jvm.optionals.getOrNull

const val UMA_TOKEN_ENDPOINT = "token_endpoint"
const val UMA_PERMISSION_ENDPOINT = "permission_endpoint"
const val UMA_INTROSPECTION_ENDPOINT = "introspection_endpoint"
const val UMA_RESOURCE_REGISTRATION_ENDPOINT = "resource_registration_endpoint"

class UmaClient(
    val podStore: Repository<Pod>,
    val podId: String,
    var webClient: WebClient,
    var baseUri: String,
) {

    @Volatile
    private var cachedUmaConfig: Uni<UMAConfig>? = null

    private val configLock = Any() // Lock for config fetching to prevent duplicate fetching
    private var expireTime: Long? = null;

    /**
     * Get the UMA authorization server URL from the UMA configuration.
     */
    fun getAuthServerUrl(): Uni<String> {
        return refreshConfig().map { it.serverUrl() }
    }

    /**
     * Refresh the UMA client configuration from the pod configuration.
     * The result is memoized for 5 minutes
     */
    private fun refreshConfig(): Uni<UMAConfig> {
        // Fast path: check without synchronization (volatile read)
        cachedUmaConfig?.let { return it }

        // Slow path: synchronize to ensure only one thread creates the Uni
        synchronized(configLock) {
            // Double-check: another thread may have created it while we waited for the lock
            cachedUmaConfig?.let { return it }

            // Create and cache the new Config Uni
            val uni = createConfigUni()
            cachedUmaConfig = uni
            return uni
        }
    }

    private fun createConfigUni(): Uni<UMAConfig> {
        return podStore.findById(podId)
            .onItem().ifNull()
            .failWith { IllegalStateException("Pod with ID '$podId' not found when refreshing UMA client configuration.") }
            .onItem().ifNotNull().transformToUni { pod ->
                val podConfig = PodConfigProvider.fromPod(pod!!)
                val config = podConfig.auth().uma().getOrNull()
                if (config != null) Uni.createFrom().item(config)
                else Uni.createFrom()
                    .failure { IllegalStateException("UMA configuration not found in pod '$podId' when refreshing UMA client configuration.") }
            }
            .invoke { _: UMAConfig ->
                Log.debugv("Fetched UMA configuration for pod {0}", podId)
                Uni.createFrom().voidItem()
                    .onItem().delayIt().by(Duration.of(5, ChronoUnit.MINUTES))
                    .subscribe().with { _ ->
                        synchronized(configLock) {
                            cachedUmaConfig = null
                        }
                    }
            }
            .memoize().indefinitely()
    }

    /**
     * Fetch the UMA well-known configuration from the authorization server.
     * The result is memoized for 5 minutes.
     */
    private fun getWellKnown(): Uni<JSONObject> {
        return refreshConfig().map { it.serverUrl().removeSuffix("/") + "/.well-known/uma2-configuration" }
            .flatMap { wellKnownEndpoint ->
                webClient.getAbs(wellKnownEndpoint)
                    .send()
                    .chain { response ->
                        when (response.statusCode()) {
                            200 -> Uni.createFrom().item(response.bodyAsJsonObject())
                            else -> Uni.createFrom()
                                .failure(Exception("Failed to fetch UMA well-known configuration for pod '${getPodName()}': ${response.statusCode()} ${response.statusMessage()}"))
                        }
                    }
                    .map { it.map }
                    .memoize().forFixedDuration(Duration.of(5, ChronoUnit.MINUTES))
            }
    }

    /**
     * Fetch a PAT token for the UMA client using the client credentials grant.
     * The token is cached. If the expirationTime expires, it will be invalidated before fetching anew.
     */
    fun fetchPatToken(): Uni<String> {
        return refreshConfig().chain { config ->
            // If we have an expireTime set, and it's in the past, invalidate the cache and fetch a new token
            if (expireTime != null && expireTime!! <= System.currentTimeMillis()) {
                expireTime = null;
                invalidatePatToken(config.clientId().get(), config.clientSecret().get())
            }
            // Fetch a new token, if none is cached already
            getPatToken(config.clientId().get(), config.clientSecret().get())
                .map {
                    // If not set, set the expireTime
                    if (expireTime == null) {
                        val expiresIn = it.getNumber("expires_in")?.toLong();
                        expireTime = System.currentTimeMillis() + (expiresIn ?: 0) * 1000
                    }
                    // return access toen
                    it.getString("access_token")
                }
        }
    }

    @CacheInvalidate(cacheName = "patTokens")
    fun invalidatePatToken(clientId: String, clientSecret: String): Unit {
        // Invalidates token because of the @CacheInvalidate annotation.
        // The method body can be empty because the caching mechanism will handle the invalidation
        // based on the clientId and clientSecret parameters.
    }

    /**
     * Fetch a new PAT token from the UMA authorization server using the client credentials grant.
     * It is cached based on the clientId and clientSecret, so that subsequent calls with the same credentials will return the cached token until it expires.
     */
    @CacheResult(cacheName = "patTokens")
    fun getPatToken(clientId: String, clientSecret: String): Uni<JsonObject> {
        return getEndpointWithConfig(UMA_TOKEN_ENDPOINT)
            .chain { (tokenEndpoint, config) ->
                webClient.postAbs(tokenEndpoint)
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .basicAuthentication(clientId, clientSecret)
                    .sendBuffer(
                        Buffer.buffer(
                            "grant_type=client_credentials&scope=uma_protection"
                        )
                    )
                    .chain { response ->
                        when (response.statusCode()) {
                            201, 200 -> Uni.createFrom().item(response.bodyAsJsonObject())
                            else -> Uni.createFrom()
                                .failure(Exception("Failed to fetch PAT token for UMA client of pod '${getPodName()}': ${response.statusCode()} ${response.statusMessage()}"))
                        }
                    }
            }
    }


    fun getTicket(resourceUri: String, requestedScopes: Set<Scope>): Uni<String?> {
        val requestBody = listOf(
            mapOf(
                "resource_id" to resourceUri,
                "resource_scopes" to requestedScopes
            )
        )
        return getEndpointWithConfig(UMA_PERMISSION_ENDPOINT)
            // Ensure we have a valid PAT token
            .chain { (permissionEndpoint, config) ->
                ensureResourceExists(resourceUri, setOf(Scope.READ, Scope.WRITE, Scope.DELETE))
                    .chain { _ ->
                        fetchPatToken().flatMap { pat ->
                            webClient.postAbs(permissionEndpoint)
                                .bearerTokenAuthentication(pat!!)
                                .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                                .sendBuffer(Buffer.buffer(Json.encode(requestBody)))
                        }
                    }.chain { response ->
                        when (response.statusCode()) {
                            201 -> Uni.createFrom().item(response.bodyAsJsonObject().getString("ticket"))
                            200 -> Uni.createFrom().nullItem()
                            else -> Uni.createFrom()
                                .failure(RuntimeException("Failed to obtain UMA permission ticket: ${response.statusCode()} ${response.bodyAsString()}"))
                        }
                    }
            }
    }

    fun ensureResourceExists(resourceUri: String, resourceScopes: Set<Scope>): Uni<Void> {
        Log.debug("Ensuring UMA resource $resourceUri exists with scopes $resourceScopes")
        val requestBody = ResourceInput(
            name = resourceUri,
            resourceScopes = resourceScopes
        )
        return getEndpointWithConfig(UMA_RESOURCE_REGISTRATION_ENDPOINT)
            .chain { (resourceRegistrationEndpoint, config) ->
                fetchPatToken().flatMap { pat ->
                    webClient.postAbs(resourceRegistrationEndpoint)
                        .bearerTokenAuthentication(pat!!)
                        .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                        .sendBuffer(Buffer.buffer(Json.encode(requestBody)))
                }.chain { response ->
                    when (response.statusCode()) {
                        201 -> {
                            Log.debug("Registered UMA resource $resourceUri")
                            val id = response.bodyAsJsonObject().getString("_id")
                            Log.debug("UMA server assigned resource ID $id to resource URI $resourceUri")
                            require(resourceUri == id) {
                                "UMA server returned a different resource ID than the resource URI. This is not compatible with the current Kvasir implementation."
                            }
                            Uni.createFrom().voidItem()
                        }

                        409 -> {
                            Log.debug("Resource $resourceUri already registered with UMA server.")
                            // Resource already exists, which is fine
                            Uni.createFrom().voidItem()
                        }

                        else -> Uni.createFrom()
                            .failure(RuntimeException("Failed to register UMA resource: ${response.statusCode()} ${response.bodyAsString()}"))
                    }
                }
            }
    }

    fun validateToken(token: String, requestedScopes: Set<Scope>): Uni<Void> {
        // Use the introspection endpoint to validate the JWT token.
        return getEndpointWithConfig(UMA_INTROSPECTION_ENDPOINT)
            .chain { (introspectionEndpoint, config) ->
                fetchPatToken().flatMap { pat ->
                    webClient.postAbs(introspectionEndpoint)
                        .bearerTokenAuthentication(pat!!)
                        .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
                        .putHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                        .sendBuffer(Buffer.buffer("token_type_hint=access_token&token=$token"))
                }
                    .chain { resp ->
                        Log.debug("Response from introspection endpoint: ${resp.bodyAsString()}")
                        val introspectionResp = resp.bodyAsJsonObject()
                        // Check the introspection endpoint response to determine if the session is valid.
                        when {
                            resp.statusCode() != 200 -> Uni.createFrom().failure(
                                RuntimeException(
                                    "Failed to introspect access token: ${resp.statusCode()} ${
                                        introspectionResp.getString(
                                            "error"
                                        )
                                    }"
                                )
                            )

                            !introspectionResp.getBoolean("active") -> Uni.createFrom().failure(
                                RuntimeException("Access token is not active according to introspection response.")
                            )

//                    tokenMatchesRequestedScopes(introspectionResp, requestedScopes) -> Uni.createFrom().failure(
//                        RuntimeException("Access token does not grant the required scopes.")
//                    )

                            else -> Uni.createFrom().voidItem()
                        }
                    }
            }
    }

    /**
     * Register a resource with the UMA Authorization Server.
     *
     * @param resource The resource to register.
     * @return A Uni emitting the resource ID upon successful registration.
     */
    fun registerResource(resource: ResourceInput): Uni<String> {
        return getEndpointWithConfig(UMA_RESOURCE_REGISTRATION_ENDPOINT)
            .chain { (resourceRegistrationEndpoint, config) ->
                fetchPatToken().flatMap { pat ->
                    webClient.postAbs(resourceRegistrationEndpoint)
                        .bearerTokenAuthentication(pat!!)
                        .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                        .sendBuffer(Buffer.buffer(Json.encode(resource)))
                        .chain { response ->
                            if (response.statusCode() == 201) {
                                // Store the resource with its assigned ID
                                val id = response.getHeader(HttpHeaders.LOCATION)
                                Uni.createFrom().item(id)
                            } else {
                                Uni.createFrom()
                                    .failure(RuntimeException("Failed to register UMA resource: ${response.statusCode()} ${response.bodyAsString()}"))
                            }
                        }
                }
            }
    }

    private fun tokenMatchesRequestedScopes(
        introspectionResp: JsonObject,
        requestedScopes: Set<Scope>
    ): Boolean = introspectionResp.getJsonArray("permissions")
        .none { permission ->
            permission as JsonObject
            val grantedScopes = permission.getJsonArray("resource_scopes")
                .mapNotNull { resourceScope -> Scope.entries.find { it.iri == resourceScope } }.toSet()
            requestedScopes.all { it in grantedScopes }
        }

    private fun getPodName(): String {
        return podId.substringAfterLast("/")
    }

    /**
     * Get the specified UMA endpoint along with the current UMA configuration.
     */
    private fun getEndpointWithConfig(key: String): Uni<Pair<String, UMAConfig>> {
        return Uni.combine().all().unis(
            refreshConfig(),
            getWellKnown()
        ).asTuple().chain { tuple ->
            val config = tuple.item1
            val wellKnown = tuple.item2
            val endpoint = wellKnown[key] as? String
            if (endpoint == null) Uni.createFrom()
                .failure { IllegalStateException("UMA configuration does not contain expected endpoint '$key' for pod '${getPodName()}'") }
            else Uni.createFrom().item(Pair(endpoint, config))
        }
    }
}

@GenerateNoArgConstructor
data class ResourceInput(
    // Resource URI
    val name: String,
    @get:JsonProperty("resource_scopes")
    val resourceScopes: Set<Scope>
) {

    fun toResource(id: String, authServerUrl: String): Resource {
        return Resource(id, authServerUrl, name, resourceScopes)
    }

}

@GenerateNoArgConstructor
data class Resource(
    // ID assigned by the UMA server
    override var id: String,
    // UMA server managing this resource
    val authServerUrl: String,
    // Resource URI
    val name: String,
    @JsonProperty("resource_scopes")
    val resourceScopes: Set<Scope>
) : PersistentEntity()

enum class Scope(
    @get:JsonValue
    val iri: String
) {
    READ("urn:example:css:modes:read"),
    APPEND("urn:example:css:modes:append"),
    CREATE("urn:example:css:modes:create"),
    DELETE("urn:example:css:modes:delete"),
    WRITE("urn:example:css:modes:write");

    companion object {

        @JvmStatic
        @JsonCreator
        fun fromIri(iri: String): Scope {
            return entries.find { it.iri == iri } ?: throw IllegalArgumentException("Unknown scope IRI: $iri")
        }

    }
}

@GenerateNoArgConstructor
@Persistent(storageLevel = StorageLevel.SYSTEM, collectionName = "uma_client_configs")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UmaClientConfig(
    override var id: String,
    var podId: String,
    var authServerUrl: String,
    var umaConfig: JSONObject,
    var clientId: String?,
    var clientSecret: String?,
    var pat: String?,
) : PersistentEntity() {}
