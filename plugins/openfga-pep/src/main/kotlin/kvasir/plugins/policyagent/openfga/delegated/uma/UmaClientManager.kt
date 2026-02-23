package kvasir.plugins.policyagent.openfga.delegated.uma

import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheResult
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.persistence.RepositoryFactory

@ApplicationScoped
class UmaClientManager(
    private val vertx: Vertx,
    private val repositoryFactory: RepositoryFactory,
    private val httpConfig: HttpConfig,
) {

    /**
     * Retrieve an UmaClient for the given podId. These UmaClients are cached so that every podId gets the same
     * UmaClient instance. This way subsequent calls to the instance's methods will be properly cached.
     * @param podId The identifier of the pod.
     * @return The UmaClient instance for the specified podId.
     */
    @CacheResult(cacheName = "umaClients")
    fun getUmaClient(podId: String): UmaClient = UmaClient(
        repositoryFactory.getRepository(Pod::class),
        podId,
        WebClient.create(vertx),
        httpConfig.baseUri().removeSuffix("/")
    )

    @CacheInvalidate(cacheName = "umaClients")
    /**
     * Invalidate the UMA client for the given podId. This effectively resets all internal fetch function for
     * client credentials, well_known configuration, PAT token, etc.
     */
    fun invalidateUmaClient(podId: String): Uni<Void> {
        Log.debug("Invalidating UMA client for pod '$podId'")
        return Uni.createFrom().voidItem()
    }
}