package kvasir.plugins.policyagent.openfga.delegated.uma

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
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

const val UMA_REGISTRATION_ENDPOINT = "registration_endpoint"
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
    private var pat: String? = null

    @Volatile
    private var refresh: String? = null

    @Volatile
    private var cachedPatToken: Uni<JsonObject>? = null

    private val tokenLock = Any() // Lock for token creation to prevent duplicate creation

    /**
     * Get the UMA authorization server URL from the UMA configuration.
     */
    fun getAuthServerUrl(): Uni<String> {
        return refreshConfig().map { it.serverUrl() }
    }

    /**
     * Refresh the UMA client configuration from the pod configuration.
     * The result is memoized for 5 minutes.
     */
    private fun refreshConfig(): Uni<UMAConfig> {
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
            .memoize().forFixedDuration(Duration.of(5, ChronoUnit.MINUTES))
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
     * Fetch a Protection API Token (PAT) for this UMA client.
     * This method is memoized for the duration of the token's validity based on expires_in.
     * @return A Uni that completes when the PAT token is fetched and stored.
     */
    fun fetchPatToken(): Uni<JsonObject> {
        // Fast path: check without synchronization (volatile read)
        cachedPatToken?.let { return it }

        // Slow path: synchronize to ensure only one thread creates the Uni
        synchronized(tokenLock) {
            // Double-check: another thread may have created it while we waited for the lock
            cachedPatToken?.let { return it }

            // Create and cache the new token fetch Uni
            val newTokenUni = createTokenFetchUni()
            cachedPatToken = newTokenUni
            return newTokenUni
        }
    }

    /**
     * Create a new token fetch Uni with memoization and scheduled invalidation.
     */
    private fun createTokenFetchUni(): Uni<JsonObject> {
        return getEndpointWithConfig(UMA_TOKEN_ENDPOINT)
            .chain { (tokenEndpoint, config) ->
                webClient.postAbs(tokenEndpoint)
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .basicAuthentication(config.clientId().get(), config.clientSecret().get())
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
            .invoke { json ->
                val expiresIn = json.getInteger("expires_in")?.toLong() ?: 300L
                // Store tokens in fields for easy access
                val patToken = json.getString("access_token")
                val refreshToken = json.getString("refresh_token")
                pat = patToken
                if (refreshToken != null) {
                    refresh = refreshToken
                }
                Log.debugv(
                    "Fetched PAT token for UMA client of pod {0}, expiring in {1} seconds",
                    getPodName(),
                    expiresIn
                )

                // Invalidate cache slightly before expiration (90% of the time)
                val cacheTime = (expiresIn * 0.9).toLong()
                // Schedule cache invalidation asynchronously
                Uni.createFrom().voidItem()
                    .onItem().delayIt().by(Duration.of(cacheTime, ChronoUnit.SECONDS))
                    .subscribe().with { _ ->
                        synchronized(tokenLock) {
                            cachedPatToken = null
                        }
                    }
            }
            .memoize().indefinitely()
    }


    fun getTicket(resourceUri: String, requestedScopes: Set<Scope>): Uni<String?> {
        val requestBody = listOf(
            mapOf(
                "resource_id" to resourceUri,
                "resource_scopes" to requestedScopes
            )
        )
        return getEndpointWithConfig(UMA_PERMISSION_ENDPOINT)
            .call { _ -> fetchPatToken() } // Ensure we have a valid PAT token
            .chain { (permissionEndpoint, config) ->
                ensureResourceExists(resourceUri, setOf(Scope.READ, Scope.WRITE, Scope.DELETE))
                    .chain { _ ->
                        webClient.postAbs(permissionEndpoint)
                            .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                            .bearerTokenAuthentication(pat!!)
                            .sendBuffer(Buffer.buffer(Json.encode(requestBody)))
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
            .call { _ -> fetchPatToken() } // Ensure we have a valid PAT token
            .chain { (resourceRegistrationEndpoint, config) ->
                webClient.postAbs(resourceRegistrationEndpoint)
                    .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                    .bearerTokenAuthentication(pat!!)
                    .sendBuffer(Buffer.buffer(Json.encode(requestBody))).chain { response ->
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
            .call { _ -> fetchPatToken() } // Ensure we have a valid PAT token
            .chain { (introspectionEndpoint, config) ->
                webClient.postAbs(introspectionEndpoint)
                    .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
                    .bearerTokenAuthentication(pat!!)
                    .putHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                    .sendBuffer(Buffer.buffer("token_type_hint=access_token&token=$token"))
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
            .call { _ -> fetchPatToken() } // Ensure we have a valid PAT token
            .chain { (resourceRegistrationEndpoint, config) ->
                webClient.postAbs(resourceRegistrationEndpoint)
                    .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                    .bearerTokenAuthentication(pat!!)
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
