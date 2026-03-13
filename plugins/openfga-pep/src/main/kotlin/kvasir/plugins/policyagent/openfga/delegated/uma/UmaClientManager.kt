package kvasir.plugins.policyagent.openfga.delegated.uma

import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKeyGenerator
import io.quarkus.cache.CacheResult
import io.quarkus.cache.CompositeCacheKey
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.config.UMAConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JSONObject
import kvasir.utils.pod.PodConfigProvider
import java.lang.reflect.Method
import java.util.*
import kotlin.jvm.optionals.getOrNull

/**
 * A simple data class that bundles important properties for the UmaClient
 * @param podId The fully qualified podId
 * @param config The UMA configuration of the pod (runtime, so with kvasir fallbacks if not overwritten in pod)
 * @param endpoints The UMA endpoints fetched from the UMA well-known configuration
 * @param baseUri The base URI of the Kvasir instance, used for constructing the resource URIs when requesting
 * UMA permission tickets
 */
data class UmaExtras(val podId: String, val config: UMAConfig, val endpoints: JSONObject, val baseUri: String) {

    /**
     * Get only the name of the pod
     */
    fun getPodName(): String {
        return podId.substringAfterLast("/");
    }

}

@ApplicationScoped
class UmaClientManager(
    private val vertx: Vertx,
    private val repositoryFactory: RepositoryFactory,
    private val httpConfig: HttpConfig,
) {

    private val webClient: WebClient = WebClient.create(vertx)

    /**
     * Retrieve an UmaClient for the given podId. These UmaClients are cached so that every podId gets the same
     * UmaClient instance. This way subsequent calls to the instance's methods will be properly cached.
     * @param podId The identifier of the pod.
     * @return The UmaClient instance for the specified podId.
     */
    fun getUmaClient(podId: String): Uni<UmaClient> {
        return getUmaExtras(podId).chain { extras ->
            getPatToken(extras)
                .chain { pat -> refreshPatIfExpired(pat, extras) }
                .map {
//                    Log.debug(it.encodePrettily())
                    UmaClient(
                        it.getString("access_token"),
                        extras,
                        WebClient.create(vertx),
                    )
                }
        }
    }

    /**
     * Invalidate the UMA configuration cache for the given podId. This is used when the UMA configuration of a pod is
     * updated, so that the next time the UMA client tries to fetch the configuration, it will get the updated one
     * instead of the cached one.
     */
    @CacheInvalidate(cacheName = "umaExtras")
    fun invalidatePodConfigCache(podId: String): Uni<Void> {
        Log.debug("Invalidating UMA configuration cache for pod '$podId'");
        return Uni.createFrom().voidItem();
    }

    /**
     * Fetch the UmaExtras for the given podId, including the UMA endpoints from the well-known configuration.
     */
    @CacheResult(cacheName = "umaExtras")
    fun getUmaExtras(podId: String): Uni<UmaExtras> {
        return repositoryFactory.getRepository(Pod::class).findById(podId)
            .onItem().ifNull()
            .failWith { IllegalStateException("Pod with ID '$podId' not found when refreshing UMA client configuration.") }
            .onItem().ifNotNull().transformToUni { pod ->
                val podConfig = PodConfigProvider.fromPod(pod!!)
                val config = podConfig.auth().uma().getOrNull()
                if (config != null) Uni.createFrom().item(config)
                else Uni.createFrom()
                    .failure { IllegalStateException("UMA configuration not found in pod '$podId' when refreshing UMA client configuration.") }
            }
            .chain { config ->
                getWellKnown(config.serverUrl())
                    .onFailure().transform { ex ->
                        RuntimeException(
                            "Failed to fetch UMA well-known configuration for pod '$podId': ${ex.message}",
                            ex
                        )
                    }
                    .map { endpoints ->
                        UmaExtras(podId, config, endpoints, httpConfig.baseUri().removeSuffix("/"))
                    }
            }
    }

    /**
     *  Fetch a PAT token from the UMA server using the client credentials grant. The result is cached based on the
     *  clientId and clientSecret, so that subsequent calls with the same credentials will return the same token until it expires.
     */
    @CacheResult(cacheName = "umaPATs", keyGenerator = UmaExtrasKeyGen::class)
    fun getPatToken(extras: UmaExtras): Uni<JsonObject> {
//        fun getPatToken(
//            @CacheKey clientId: String,
//            @CacheKey clientSecret: String,
//            endpoints: JSONObject,
//            podName: String
//        ): Uni<JsonObject> {
        val tokenEndpoint = extras.endpoints[UMA_TOKEN_ENDPOINT] as String;
        return webClient.postAbs(tokenEndpoint)
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
            .basicAuthentication(extras.config.clientId().get(), extras.config.clientSecret().get())
            .sendBuffer(
                Buffer.buffer(
                    "grant_type=client_credentials&scope=uma_protection"
                )
            )
            .chain { response ->
                when (response.statusCode()) {
                    201, 200 -> Uni.createFrom().item(response.bodyAsJsonObject())
                    else -> Uni.createFrom()
                        .failure(Exception("Failed to fetch PAT token for UMA client of pod '${extras.getPodName()}': ${response.statusCode()} ${response.statusMessage()}"))
                }
            }
    }

    /**
     *  Invalidate the PAT token cache for the given client credentials.
     */
    @CacheInvalidate(cacheName = "umaPATs")
    fun invalidatePatTokenCache(clientId: String, clientSecret: String): Uni<Void> {
        return Uni.createFrom().voidItem();
    }

    fun refreshPatIfExpired(pat: JsonObject, extras: UmaExtras): Uni<JsonObject> {
        val expiresIn = pat.getInteger("expires_in")
        val tok = pat.getString("access_token")
        val parsedToken = JsonObject(String(Base64.getDecoder().decode(tok.split('.')[1])))
        val issuedAt = parsedToken.getLong("iat").times(1000);
        val expiresAt = issuedAt.plus(expiresIn * 1000);
        val now = System.currentTimeMillis()
        return if (now >= expiresAt) {
            Log.debug("PAT token expired at $expiresAt (time now: $now), refreshing...")
            invalidatePatTokenCache(extras.config.clientId().get(), extras.config.clientSecret().get())
                .chain { _ ->
                    getPatToken(extras)
                }
        } else {
            Uni.createFrom().item(pat)
        }
    }

    /**
     * Fetch the UMA well-known configuration from the authorization server.
     * The result is memoized for 5 minutes.
     */
    private fun getWellKnown(serverUrl: String): Uni<JSONObject> {
        val wellKnownUri = serverUrl.removeSuffix("/") + "/.well-known/uma2-configuration";
        return webClient.getAbs(wellKnownUri).send()
            .chain { response ->
                when (response.statusCode()) {
                    200 -> Uni.createFrom().item(response.bodyAsJsonObject())
                    else -> Uni.createFrom().failure(RuntimeException(response.bodyAsString()))
                }
            }
            .map { it.map }
    }
}

class UmaExtrasKeyGen : CacheKeyGenerator {
    override fun generate(method: Method, vararg methodParams: Any): Any {
        assert(methodParams.isNotEmpty()) { "Expected at least one parameter (UmaExtras) for UMA extras cache key generation, but got ${methodParams.size}" };
        val obj = methodParams[0];
        assert(obj is UmaExtras) { "Expected first parameter to be of type UmaExtras for UMA extras cache key generation, but got ${obj::class.java.name}" };
        val extras = obj as UmaExtras;
        return CompositeCacheKey(extras.config.clientId().get(), extras.config.clientSecret().get());
    }

}