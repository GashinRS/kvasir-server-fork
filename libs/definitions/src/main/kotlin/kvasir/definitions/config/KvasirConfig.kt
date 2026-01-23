package kvasir.definitions.config

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import io.vertx.core.json.JsonObject
import kvasir.definitions.rdf.JSONObject
import org.eclipse.microprofile.config.spi.Converter
import java.util.*

@ConfigMapping(prefix = "kvasir.http")
interface HttpConfig {

    /**
     * The base URI where Kvasir is accessible.
     */
    fun baseUri(): String

    /**
     * The URI where the Kvasir web client is accessible.
     */
    fun webclientUri(): String

    /**
     * If true, HTTP requests to the base URI will be redirected to the web client URI.
     */
    @WithDefault("true")
    fun redirectToWebclient(): Boolean
}

/**
 * Default configuration related to Pods.
 * May be overridden per Pod.
 */
@ConfigMapping(prefix = "kvasir.pod")
interface PodConfig {

    /**
     * The default JSON-LD context to use when interfacing with the global GraphQL endpoint of the Pod's Knowledge Graph.
     * This allows the execution of standard GraphQL queries (which do not have context information) against the Pod's Knowledge Graph.
     */
    @JsonProperty("default-context")
    fun defaultContext(): Map<String, String>

    /**
     * If true, RDF data will be automatically ingested into the KG when uploaded via the storage-api.
     */
    @WithDefault("false")
    @JsonProperty("auto-ingest-rdf")
    fun autoIngestRdf(): Boolean

    /**
     * Authentication and authorization configuration for the Pod.
     */
    @JsonProperty("auth")
    fun auth(): PodAuthConfig

}

// Make sure the Optionals in config here are left absent when they are empty and are included when they are null
// Also make sure empty values are not serialized as their annotated @WithDefault values
//@JsonInclude(JsonInclude.Include.NON_ABSENT)
interface PodAuthConfig {
    /**
     * Configuration related to OpenID Connect (OIDC) authentication.
     */
    @JsonProperty("oidc")
    fun oidc(): Optional<OIDCConfig>

    /**
     * Whether to enable Solid WebID support.
     */
    @WithDefault("false")
    @JsonProperty("enable-solid-web-id")
    fun enableSolidWebId(): Boolean

    /**
     * Whether to require DPoP tokens for protected resources.
     */
    @WithDefault("false")
    @JsonProperty("require-dpop")
    fun requireDpop(): Boolean

    /**
     * Whether to skip the access token hash (ath) check for DPoP tokens.
     * Note: skipping this check may have security implications and should only be done if you fully understand the consequences.
     * This setting is primarily intended for backward compatibility with clients that implement an earlier version of the DPoP specification.
     */
    @WithDefault("false")
    @JsonProperty("skip-dpop-ath-check")
    fun skipDpopAthCheck(): Boolean

    /**
     * Configuration related to UMA authorization.
     */
    @JsonProperty("uma")
    fun uma(): Optional<UMAConfig>

    /**
     * Configuration for HTTP Endpoint Policy Enforcers.
     */
    @JsonProperty("http-endpoint-policy-enforcer")
    fun httpEndpointPolicyEnforcer(): Optional<HttpEndpointPolicyEnforcerConfig>
}

@JsonInclude(JsonInclude.Include.NON_NULL)
interface JWTProviderConfig {
    @JsonProperty("server-url")
    fun serverUrl(): String

    /**
     * Allows configuring a custom JWT principal extractor for JWT tokens issued by this provider.
     */
    @JsonProperty("principal-extractor")
    fun principalExtractor(): Optional<JWTPrincipalExtractorConfig>

    /**
     * Allowed clock skew in seconds to apply during JWT token validation.
     */
    @JsonProperty("jwt-allowed-clock-skew-seconds")
    fun jwtAllowedClockSkewSeconds(): Int
}

interface OIDCConfig : JWTProviderConfig

interface UMAConfig : JWTProviderConfig

@JsonInclude(JsonInclude.Include.NON_NULL)
interface HttpEndpointPolicyEnforcerConfig {
    @JsonProperty("url")
    fun url(): String

    @JsonProperty("basic-auth")
    fun basicAuth(): Optional<BasicAuthConfig>

    @JsonProperty("api-key")
    fun apiKey(): Optional<ApiKeyConfig>
}

interface JWTPrincipalExtractorConfig {
    fun className(): String
    fun config(): Map<String, String>
}

interface BasicAuthConfig {
    @JsonProperty("username")
    fun username(): String

    @JsonProperty("password")
    fun password(): String
}

interface ApiKeyConfig {
    @JsonProperty("key-name")
    fun keyName(): String

    @JsonProperty("key-value")
    fun keyValue(): String

    @JsonProperty("send-via")
    fun sendVia(): ApiKeySendVia
}

enum class ApiKeySendVia {
    header,
    query
}

/**
 * This config has all the same fields as the PodConfig, but they are all Optionals. Semantics:
 * - **empty (key absent)**: Follow platform default podconfig
 * - **null (when it makes sense)**: Explicitly override with null
 * - **value**: Explicitly override with value
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
interface PodConfigOverride {
    /**
     * The default JSON-LD context to use when interfacing with the global GraphQL endpoint of the Pod's Knowledge Graph.
     * This allows the execution of standard GraphQL queries (which do not have context information) against the Pod's Knowledge Graph.
     */
    @JsonProperty("default-context")
    fun defaultContext(): Map<String, String>

    /**
     * If true, RDF data will be automatically ingested into the KG when uploaded via the storage-api.
     */
    @WithDefault("false")
    @JsonProperty("auto-ingest-rdf")
    fun autoIngestRdf(): Optional<Boolean>

    /**
     * Authentication and authorization configuration for the Pod.
     */
    @JsonProperty("auth")
    fun auth(): Optional<PodAuthConfigOverride>
}

// Make sure the Optionals in config here are left absent when they are empty and are included when they are null
@JsonInclude(JsonInclude.Include.NON_ABSENT)
interface PodAuthConfigOverride {
    /**
     * Configuration related to OpenID Connect (OIDC) authentication.
     */
    @JsonProperty("oidc")
    fun oidc(): Optional<OIDCConfig>

    /**
     * Whether to enable Solid WebID support.
     */
    @JsonProperty("enable-solid-web-id")
    fun enableSolidWebId(): Optional<Boolean>

    /**
     * Whether to require DPoP tokens for protected resources.
     */
    @JsonProperty("require-dpop")
    fun requireDpop(): Optional<Boolean>

    /**
     * Whether to skip the access token hash (ath) check for DPoP tokens.
     * Note: skipping this check may have security implications and should only be done if you fully understand the consequences.
     * This setting is primarily intended for backward compatibility with clients that implement an earlier version of the DPoP specification.
     */
    @JsonProperty("skip-dpop-ath-check")
    fun skipDpopAthCheck(): Optional<Boolean>

    /**
     * Configuration related to UMA authorization.
     */
    @JsonProperty("uma")
    fun uma(): Optional<UMAConfig>

    /**
     * Configuration for HTTP Endpoint Policy Enforcers.
     */
    @JsonProperty("http-endpoint-policy-enforcer")
    fun httpEndpointPolicyEnforcer(): Optional<HttpEndpointPolicyEnforcerConfig>
}

/**
 * Quarkus configuration mapping used for bootstrapping the system via config.
 * The processing of this config is done in the init-service.
 */
@ConfigMapping(prefix = "kvasir.bootstrap")
interface BootstrapConfig {
    /**
     * Returns the list of pods to be initialized based on the supplied config.
     */
    fun pods(): List<BootstrapPodConfig>

    /**
     * If true, the Kvasir init-service will terminate after the setup is completed (or failed).
     */
    @WithDefault("false")
    fun exitAfterSetup(): Boolean
}

interface BootstrapPodConfig {
    /**
     * The name of the pod. The pod will be accessible via {kvasir.base-uri}/{name}.
     */
    fun name(): String

    /**
     * The user ID of the owner of the pod (optional).
     * Primarily useful when using an external OIDC server (not the Kvasir built-in Keycloak).
     * If not provided, we assume the userId is the same as the pod name.
     */
    fun ownerUserId(): Optional<String>

    /**
     * If set to true, authorization is delegated to the configured UMA server for all resources.
     */
    @WithDefault("false")
    fun autoRegisterUma(): Boolean

    /**
     * If set to true, authorization is delegated to the configured HTTP Endpoint Policy Enforcer for all resources.
     */
    @WithDefault("false")
    fun autoRegisterHttpEndpointPolicyEnforcer(): Boolean

    /**
     * As a convenience, Kvasir allows generating clients for the Pod from config.
     * For now, this is only supported when using Kvasir's built-in Keycloak server.
     */
    fun generateClients(): Optional<List<GenerateClientConfig>>

    /**
     * Allows overriding Pod-specific configuration.
     */
    fun configuration(): PodConfigOverride
}

interface GenerateClientConfig {
    /**
     * The client ID.
     */
    fun clientId(): String

    /**
     * Whether to enable the service account for this client.
     * Defaults to false.
     */
    @WithDefault("false")
    fun enableServiceAccount(): Boolean

    /**
     * The client secret, if applicable.
     * Optional, as some clients may be public clients without a secret.
     */
    fun clientSecret(): Optional<String>

    /**
     * The redirect URIs for the client.
     * Optional, as some clients may not require a redirect URI (e.g., service clients).
     */
    fun redirectUris(): Optional<List<String>>

    @WithName("enable-force-pkce")
    @WithDefault("false")
    fun enableForcePKCE(): Boolean

    /**
     * Optional OpenFGA configuration for the client.
     * Only applicable if the OpenFGA plugin is enabled.
     * Allows specifying which resources and relations the client should have access to.
     */
    fun openfga(): Optional<OpenFgaClientConfig>
}

interface OpenFgaClientConfig {

    /**
     * Returns the list of relationships that define the permissions for this client.
     * Each relationship specifies a target resource and the relations that apply to it.
     */
    fun relationships(): Optional<List<OpenFgaPermissionConfig>>

}

interface OpenFgaPermissionConfig {
    /**
     * The target resource for which the permissions apply, defined as a path relative to the Pod's base URI.
     */
    fun targetResource(): String

    /**
     * The list of relations that apply to the target resource.
     * For example, "reader", "writer", etc.
     * Possible relations are defined in the OpenFGA schema being used.
     */
    fun relations(): List<String>
}

class JsonConvertor : Converter<JSONObject> {
    override fun convert(input: String?): JSONObject? {
        return input?.takeIf { it.isNotBlank() }?.let { JsonObject(input).map }
    }

}