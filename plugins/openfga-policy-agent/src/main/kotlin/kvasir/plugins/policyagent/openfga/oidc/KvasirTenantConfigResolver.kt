package kvasir.plugins.policyagent.openfga.oidc

import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.cache.Cache
import io.quarkus.cache.CacheName
import io.quarkus.oidc.OidcRequestContext
import io.quarkus.oidc.OidcTenantConfig
import io.quarkus.oidc.TenantConfigResolver
import io.smallrye.mutiny.Uni
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.PodStore
import kvasir.plugins.policyagent.openfga.OpenFgaConstants
import org.eclipse.microprofile.config.inject.ConfigProperty

private val EXCLUDE_PATH_PREFIXES = setOf("/q/", "/favicon.ico", "/robots.txt", "/_ui", "/.well-known/")

/**
 * Enables multi-tenant OIDC (i.e. each Pod can have its own OIDC configuration) op top of Quarkus OIDC.
 */
@ApplicationScoped
@IfBuildProperty(name = OpenFgaConstants.POLICY_AGENT_ENABLED, stringValue = "true")
class KvasirTenantConfigResolver(
    @CacheName(OpenFgaConstants.AUTH_CONFIG_CACHE_NAME)
    private val authConfigCache: Cache,
    private val podStore: PodStore,
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = KvasirConfig.BASE_URI_DEFAULT)
    private val baseUri: String,

    ) : TenantConfigResolver {
    override fun resolve(
        routingContext: RoutingContext,
        requestContext: OidcRequestContext<OidcTenantConfig>
    ): Uni<OidcTenantConfig> {
        val path = routingContext.request().path()
        val pathItems = path.split('/').filterNot { it.isBlank() }
        if (pathItems.isEmpty() || EXCLUDE_PATH_PREFIXES.any { path.startsWith(it) }) {
            return Uni.createFrom().nullItem()
        }
        val podName = pathItems.first()
        val podId = "${baseUri}$podName"
        return authConfigCache.getAsync(podId) {
            podStore.getById(podId)
                .chain { pod ->
                    pod?.getAuthConfiguration()?.let { authConfig ->
                        // Parse the auth configuration as an OidcTenantConfig
                        Uni.createFrom().item(JsonObject(authConfig).mapTo(OidcTenantConfig::class.java))
                    } ?: Uni.createFrom().nullItem() // resolve to default tenant config
                }
        }
    }
}