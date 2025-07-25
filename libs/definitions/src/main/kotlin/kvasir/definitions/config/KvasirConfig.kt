package kvasir.definitions.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithConverter
import io.smallrye.config.WithDefault
import io.vertx.core.json.JsonObject
import kvasir.definitions.rdf.JSONObject
import org.eclipse.microprofile.config.spi.Converter
import java.util.*

object KvasirConfig {

    const val BASE_URI_PROPERTY = "kvasir.base-uri"
    const val BASE_URI_DEFAULT = "http://localhost:8080/"

    const val WEBCLIENT_URI_PROPERTY = "kvasir.webclient-uri"
    const val WEBCLIENT_URI_DEFAULT = "http://localhost:8080/_ui/"

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
    fun pods(): List<PodConfig>
}

interface PodConfig {
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
     * As a convenience, Kvasir allows generating clients for the Pod from config.
     * For now, this is only supported when using Kvasir's built-in Keycloak server.
     */
    fun generateClients(): Optional<List<GenerateClientConfig>>

    /**
     * Configure the Pod via a JSON-LD object.
     * This maps to the property 'https://kvasir.discover.ilabt.imec.be/vocab#' when creating or updating a Pod using
     * the Pod Management API.
     */
    @WithConverter(JsonConvertor::class)
    fun configuration(): JSONObject
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
    override fun convert(input: String): JSONObject {
        return JsonObject(input).map
    }

}