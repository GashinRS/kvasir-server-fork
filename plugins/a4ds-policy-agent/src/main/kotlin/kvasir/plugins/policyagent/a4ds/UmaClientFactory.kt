package kvasir.plugins.policyagent.a4ds

import com.authlete.hms.ComponentValueProvider
import com.authlete.hms.SignatureBaseBuilder
import com.authlete.hms.SignatureMetadata
import com.authlete.hms.SignatureMetadataParameters
import com.authlete.hms.impl.JoseHttpSigner
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.ext.web.client.HttpRequest
import io.vertx.mutiny.ext.web.client.HttpResponse
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.rdf.JSONObject
import kvasir.plugins.policyagent.a4ds.jwks.JwksProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.*
import kotlin.collections.find

@ApplicationScoped
class UmaClientFactory(
    vertx: Vertx,
    private val resourceRepositoryProvider: ResourceRepositoryProvider,
    private val jwksProvider: JwksProvider,
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = KvasirConfig.BASE_URI_DEFAULT)
    private val baseUri: String
) {

    private val httpClient = WebClient.create(vertx)

    fun createClient(umaServerUrl: String): Uni<UmaClient> {
        // Retrieve UMA configuration from the server's well-known endpoint
        return httpClient.getAbs(umaServerUrl.removeSuffix("/") + "/.well-known/uma2-configuration")
            .send()
            .chain { response ->
                if (response.statusCode() == 200) {
                    val json = response.bodyAsJsonObject()
                    Uni.createFrom()
                        .item(
                            UmaClient(
                                umaServerUrl,
                                json.map,
                                baseUri,
                                httpClient,
                                jwksProvider,
                                resourceRepositoryProvider.create()
                            )
                        )
                } else {
                    Uni.createFrom()
                        .failure(IllegalStateException("Failed to retrieve UMA configuration from $umaServerUrl: ${response.statusCode()} ${response.statusMessage()}"))
                }
            }
    }

}

private const val UMA_PERMISSION_ENDPOINT = "permission_endpoint"
private const val UMA_INTROSPECTION_ENDPOINT = "introspection_endpoint"
private const val UMA_RESOURCE_REGISTRATION_ENDPOINT = "resource_registration_endpoint"

class UmaClient(
    val authServerUrl: String,
    val umaConfig: JSONObject,
    val baseUri: String,
    private val httpClient: WebClient,
    private val jwksProvider: JwksProvider,
    private val resourceRepository: ResourceRepository
) {

    /**
     * Obtain a permission ticket for accessing a resource with the specified scopes.
     *
     * @param resourceUri The URI of the resource to access.
     * @param requestedScopes The set of scopes being requested for access.
     * @return A Uni emitting the permission ticket upon successful request. For publicly accessible resources that don't require a ticket, this may emit null.
     */
    fun getTicket(resourceUri: String, requestedScopes: Set<Scope>): Uni<String?> {
        val permissionEndpoint = umaConfig[UMA_PERMISSION_ENDPOINT] as String
        val requestBody = listOf(
            mapOf(
                "resource_id" to resourceUri,
                "resource_scopes" to requestedScopes
            )
        )
        return ensureResourceExists(resourceUri, setOf(Scope.READ, Scope.WRITE, Scope.DELETE))
            .chain { _ ->
                httpClient.postAbs(permissionEndpoint)
                    .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                    .putHeader(HttpHeaders.AUTHORIZATION, "HttpSig cred=\"${baseUri.removeSuffix("/")}\"")
                    .sendSigned(Json.encode(requestBody), jwksProvider)
            }.chain { response ->
                when (response.statusCode()) {
                    201 -> Uni.createFrom().item(response.bodyAsJsonObject().getString("ticket"))
                    200 -> Uni.createFrom().nullItem()
                    else -> Uni.createFrom()
                        .failure(RuntimeException("Failed to obtain UMA permission ticket: ${response.statusCode()} ${response.bodyAsString()}"))
                }
            }
    }

    /**
     * Temporary workaround for dynamic resource registration.
     * This will work as long as the AS uses the resource name as the internal resource ID.
     */
    fun ensureResourceExists(resourceUri: String, resourceScopes: Set<Scope>): Uni<Void> {
        Log.debug("Ensuring UMA resource $resourceUri exists with scopes $resourceScopes")
        val resourceRegistrationEndpoint = umaConfig[UMA_RESOURCE_REGISTRATION_ENDPOINT] as String
        val requestBody = ResourceInput(
            name = resourceUri,
            resourceScopes = resourceScopes
        )
        return httpClient.postAbs(resourceRegistrationEndpoint)
            .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .putHeader(HttpHeaders.AUTHORIZATION, "HttpSig cred=\"${baseUri.removeSuffix("/")}\"")
            .sendSigned(Json.encode(requestBody), jwksProvider).chain { response ->
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
        val introspectionEndpoint = umaConfig[UMA_INTROSPECTION_ENDPOINT] as String
        // Use the introspection endpoint to validate the JWT token.
        return httpClient.postAbs(introspectionEndpoint)
            .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
            .putHeader(
                HttpHeaders.AUTHORIZATION,
                "HttpSig cred=\"${baseUri.removeSuffix("/")}\""
            )
            .putHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
            .sendSigned("token_type_hint=access_token&token=$token", jwksProvider)
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

                    tokenMatchesRequestedScopes(introspectionResp, requestedScopes) -> Uni.createFrom().failure(
                        RuntimeException("Access token does not grant the required scopes.")
                    )

                    else -> Uni.createFrom().voidItem()
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

    /**
     * Register a resource with the UMA Authorization Server.
     *
     * @param resource The resource to register.
     * @return A Uni emitting the resource ID upon successful registration.
     */
    fun registerResource(resource: ResourceInput): Uni<String> {
        return httpClient.postAbs(umaConfig[UMA_RESOURCE_REGISTRATION_ENDPOINT] as String)
            .putHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
            .putHeader(HttpHeaders.AUTHORIZATION, "HttpSig cred=\"${baseUri.removeSuffix("/")}\"")
            .sendSigned(Json.encode(resource), jwksProvider)
            .chain { response ->
                if (response.statusCode() == 201) {
                    // Store the resource with its assigned ID
                    val id = response.getHeader(HttpHeaders.LOCATION)
                    resourceRepository.persist(resource.toResource(id, authServerUrl)).replaceWith(id)
                } else {
                    Uni.createFrom()
                        .failure(RuntimeException("Failed to register UMA resource: ${response.statusCode()} ${response.bodyAsString()}"))
                }
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

internal fun HttpRequest<Buffer>.sendSigned(body: String, jwksProvider: JwksProvider): Uni<HttpResponse<Buffer>> {
    val publicKey = jwksProvider.getPublicKey() as ECPublicKey
    val jwk = ECKey.Builder(Curve.forECParameterSpec(publicKey.params), publicKey)
        .privateKey(jwksProvider.getPrivateKey() as ECPrivateKey)
        .keyID(jwksProvider.getKeyId())
        .algorithm(Algorithm("ES256"))
        .build()

    val signer = JoseHttpSigner(jwk)
    val context = ComponentValueProvider()
        .setHeaders(this.headers().names().associateWith { this.headers().getAll(it) })
    val metadata =
        SignatureMetadata(
            SignatureMetadataParameters().setKeyid(jwksProvider.getKeyId()).setAlg("ES256")
                .setCreated(Instant.now())
        )
    val sigBase = SignatureBaseBuilder(context).build(metadata)
    val signature = Base64.getEncoder().encodeToString(sigBase.sign(signer))

    this.putHeader("Signature-Input", sigBase.serialize().replaceFirst("\"@signature-params\": ", "sig="))
    this.putHeader("Signature", "sig=:$signature:")
    return this.sendBuffer(Buffer.buffer(body))
}