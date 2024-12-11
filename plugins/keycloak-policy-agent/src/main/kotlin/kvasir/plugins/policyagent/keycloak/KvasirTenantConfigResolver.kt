package kvasir.plugins.policyagent.keycloak

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
        val pathItems = routingContext.request().path().split('/').filterNot{ it.isBlank() }
        if (pathItems.isEmpty()) {
            return null
        }
        val podId = "${baseUri}${pathItems.first()}"
        return podStore.getById(podId)
            .onItem().ifNull().failWith { NotFoundException() }
            .onItem().ifNotNull().transformToUni { pod ->
                pod?.getAuthConfiguration()?.let { authConfig ->
                    Uni.createFrom().item(OidcTenantConfig().apply {
                        this.setAuthServerUrl(authConfig.serverUrl)
                        this.setClientId(authConfig.clientId)
                        this.credentials.setSecret(authConfig.clientSecret)
                    })
                } ?: Uni.createFrom().failure(NotFoundException())

            }
    }

}