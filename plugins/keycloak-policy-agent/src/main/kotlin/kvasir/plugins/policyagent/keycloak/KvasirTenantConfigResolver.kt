package kvasir.plugins.policyagent.keycloak

import io.quarkus.keycloak.pep.TenantPolicyConfigResolver
import io.quarkus.keycloak.pep.runtime.KeycloakPolicyEnforcerTenantConfig
import io.quarkus.logging.Log
import io.quarkus.oidc.OidcRequestContext
import io.quarkus.oidc.OidcTenantConfig
import io.quarkus.oidc.TenantConfigResolver
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import kvasir.definitions.kg.PodStore
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class KvasirTenantConfigResolver(
    private val podStore: PodStore,
    @ConfigProperty(name = "kvasir.base-uri", defaultValue = "http://localhost:8080/")
    private val baseUri: String,
) : TenantConfigResolver {
    override fun resolve(
        routingContext: RoutingContext,
        requestContext: OidcRequestContext<OidcTenantConfig>
    ): Uni<OidcTenantConfig>? {
        val pathItems = routingContext.request().path().split('/').filterNot { it.isBlank() }
        if (pathItems.isEmpty() || pathItems.first() == "q") {
            return null
        }
        val podId = "${baseUri}${pathItems.first()}"
        return podStore.getById(podId)
            .onItem().ifNull().failWith { NotFoundException() }
            .onItem().ifNotNull().transformToUni { pod ->
                val result = pod?.getAuthConfiguration()?.let { authConfig ->
                    Uni.createFrom().item(OidcTenantConfig().apply {
                        this.setTenantId(pathItems.first())
                        this.setApplicationType(OidcTenantConfig.ApplicationType.HYBRID)
                        this.setAuthServerUrl(authConfig.serverUrl)
                        this.setClientId(authConfig.clientId)
                        this.credentials.setSecret(authConfig.clientSecret)
                        Log.debug("Resolved tenant config for pod $podId: $this")
                    })
                } ?: Uni.createFrom().failure(NotFoundException())
                result
            }
    }

}

//@ApplicationScoped
//class KvasirTenantPolicyConfigResolver(
//    private val podStore: PodStore,
//    @ConfigProperty(name = "kvasir.base-uri", defaultValue = "http://localhost:8080/")
//    private val baseUri: String,
//) : TenantPolicyConfigResolver {
//
//    override fun resolve(
//        routingContext: RoutingContext,
//        tenantConfig: OidcTenantConfig,
//        requestContext: OidcRequestContext<KeycloakPolicyEnforcerTenantConfig>
//    ): Uni<KeycloakPolicyEnforcerTenantConfig>? {
//        return null
//    }
//
//}