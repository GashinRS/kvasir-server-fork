package kvasir.plugins.policyagent.openfga.delegated.uma

import io.quarkus.cache.CacheResult
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
     * Retrieve an UmaClient for the given podId.
     * If already in cache, the cached instance is returned.
     * Otherwise, a new instance is created and cached.
     * @param podId The identifier of the pod.
     * @return The UmaClient instance for the specified podId.
     */
    @CacheResult(cacheName = "umaClient")
    fun getUmaClient(podId: String): UmaClient = UmaClient(
        repositoryFactory.getRepository(Pod::class),
        podId,
        WebClient.create(vertx),
        httpConfig.baseUri().removeSuffix("/")
    )


    /**
     * Invalidate the cached UmaClient for the given podId.
     * This can be used when the UmaClient configuration changes and needs to be refreshed.
     * @param podId The identifier of the pod.
     */
    fun invalidateUmaClient(podId: String) {
        // This method can be used to invalidate the cached UmaClient for a specific podId if needed.
    }
}