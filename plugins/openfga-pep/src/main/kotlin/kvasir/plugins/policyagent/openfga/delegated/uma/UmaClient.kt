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
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.rdf.JSONObject

const val UMA_TOKEN_ENDPOINT = "token_endpoint"
const val UMA_PERMISSION_ENDPOINT = "permission_endpoint"
const val UMA_INTROSPECTION_ENDPOINT = "introspection_endpoint"
const val UMA_RESOURCE_REGISTRATION_ENDPOINT = "resource_registration_endpoint"

class UmaClient(
    val pat: String,
    val extras: UmaExtras,
    var webClient: WebClient,
) {

    fun getTicket(resourceUri: String, requestedScopes: Set<Scope>): Uni<String?> {
        val requestBody = listOf(
            mapOf(
                "resource_id" to resourceUri,
                "resource_scopes" to requestedScopes
            )
        )
        val permissionEndpoint = extras.endpoints[UMA_PERMISSION_ENDPOINT] as String;
        return ensureResourceExists(resourceUri, setOf(Scope.READ, Scope.WRITE, Scope.DELETE))
            .chain { _ ->
                webClient.postAbs(permissionEndpoint)
                    .bearerTokenAuthentication(pat)
                    .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                    .sendBuffer(Buffer.buffer(Json.encode(requestBody)))
            }
            .chain { response ->
                when (response.statusCode()) {
                    201 -> Uni.createFrom().item(response.bodyAsJsonObject().getString("ticket"))
                    200 -> Uni.createFrom().nullItem()
                    else -> Uni.createFrom()
                        .failure(RuntimeException("Failed to obtain UMA permission ticket: ${response.statusCode()} ${response.bodyAsString()}"))
                }
            }
    }


    fun ensureResourceExists(resourceUri: String, resourceScopes: Set<Scope>): Uni<Void> {
        Log.debug("Ensuring UMA resource $resourceUri exists with scopes $resourceScopes")
        val requestBody = ResourceInput(
            name = resourceUri,
            resourceScopes = resourceScopes
        )
        val resourceRegistrationEndpoint = extras.endpoints[UMA_RESOURCE_REGISTRATION_ENDPOINT] as String
        return webClient.postAbs(resourceRegistrationEndpoint)
            .bearerTokenAuthentication(pat)
            .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .sendBuffer(Buffer.buffer(Json.encode(requestBody)))
            .chain { response ->
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


    fun validateToken(token: String, requestedScopes: Set<Scope>): Uni<Void> {
        val introspectionEndpoint = extras.endpoints[UMA_INTROSPECTION_ENDPOINT] as String
        // Use the introspection endpoint to validate the JWT token.
        return webClient.postAbs(introspectionEndpoint)
            .bearerTokenAuthentication(pat)
            .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
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


    /**
     * Register a resource with the UMA Authorization Server.
     *
     * @param resource The resource to register.
     * @return A Uni emitting the resource ID upon successful registration.
     */
    fun registerResource(resource: ResourceInput): Uni<String> {
        val resourceRegistrationEndpoint = extras.endpoints[UMA_RESOURCE_REGISTRATION_ENDPOINT] as String
        return webClient.postAbs(resourceRegistrationEndpoint)
            .bearerTokenAuthentication(pat)
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
