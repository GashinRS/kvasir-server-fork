package kvasir.plugins.policyagent.keycloak

import io.quarkus.keycloak.pep.TenantPolicyConfigResolver
import io.quarkus.keycloak.pep.runtime.KeycloakPolicyEnforcerTenantConfig
import io.quarkus.oidc.OidcRequestContext
import io.quarkus.oidc.OidcTenantConfig
import io.quarkus.oidc.TenantConfigResolver
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import kvasir.definitions.kg.PodStore
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.keycloak.representations.adapters.config.PolicyEnforcerConfig

private val EXCLUDE_PATH_PREFIXES = setOf("/q/", "/favicon.ico")

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
        if (pathItems.isEmpty() || routingContext.request().path() in EXCLUDE_PATH_PREFIXES) {
            return Uni.createFrom().nullItem()
        }
        val podName = pathItems.first()
        val podId = "${baseUri}$podName"
        return podStore.getById(podId)
            .onItem().ifNull().failWith { NotFoundException() }
            .onItem().ifNotNull().transformToUni { pod ->
                Uni.createFrom().item(OidcTenantConfig().apply {
                    this.setTenantId(podName)
                    pod?.getAuthConfiguration()?.let { authConfig ->
                        this.setAuthServerUrl(authConfig.serverUrl)
                        this.setClientId(authConfig.clientId)
                        this.credentials.setSecret(authConfig.clientSecret)
                    }
                }).map {
                    println(it)
                    it
                }
            }
    }

}

@ApplicationScoped
class KvasirTenantPolicyConfigResolver() : TenantPolicyConfigResolver {
    override fun resolve(
        routingContext: RoutingContext,
        tenantConfig: OidcTenantConfig?,
        requestContext: OidcRequestContext<KeycloakPolicyEnforcerTenantConfig>
    ): Uni<KeycloakPolicyEnforcerTenantConfig?> {
        val tenantId = tenantConfig?.tenantId
        return if (tenantId == null) {
            // Default policy config resolver
            Uni.createFrom().nullItem()
        } else {
            Uni.createFrom().item(
                KeycloakPolicyEnforcerTenantConfig.builder()
                    .enforcementMode(PolicyEnforcerConfig.EnforcementMode.ENFORCING).build()
            )
        }
    }

}