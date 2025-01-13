package kvasir.plugins.policyagent.keycloak

import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.keycloak.pep.PolicyEnforcerResolver
import io.quarkus.keycloak.pep.TenantPolicyConfigResolver
import io.quarkus.keycloak.pep.runtime.KeycloakPolicyEnforcerAuthorizer
import io.quarkus.keycloak.pep.runtime.KeycloakPolicyEnforcerTenantConfig
import io.quarkus.oidc.OidcRequestContext
import io.quarkus.oidc.OidcTenantConfig
import io.quarkus.oidc.TenantConfigResolver
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.spi.runtime.BlockingSecurityExecutor
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy.CheckResult
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Singleton
import jakarta.ws.rs.NotFoundException
import kvasir.definitions.kg.PodStore
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.keycloak.representations.adapters.config.PolicyEnforcerConfig
import kotlin.jvm.optionals.getOrNull

private val EXCLUDE_PATH_PREFIXES = setOf("/q/", "/favicon.ico")

@ApplicationScoped
@IfBuildProperty(name = "quarkus.keycloak.policy-enforcer.enable", stringValue = "true")
class KvasirTenantConfigResolver(
    private val podStore: PodStore,
    @ConfigProperty(name = "kvasir.base-uri", defaultValue = "http://localhost:8080/")
    private val baseUri: String,
) : TenantConfigResolver {
    override fun resolve(
        routingContext: RoutingContext,
        requestContext: OidcRequestContext<OidcTenantConfig>
    ): Uni<OidcTenantConfig?> {
        return getTenantConfig(routingContext.request().path())
    }

    private fun getTenantConfig(path: String): Uni<OidcTenantConfig?> {
        val pathItems = path.split('/').filterNot { it.isBlank() }
        if (pathItems.isEmpty() || EXCLUDE_PATH_PREFIXES.any{path.startsWith(it)}) {
            return Uni.createFrom().nullItem()
        }
        val podName = pathItems.first()
        val podId = "${baseUri}$podName"
        return podStore.getById(podId)
            .onItem().ifNull().failWith { NotFoundException() }
            .onItem().ifNotNull().transformToUni { pod ->
                Uni.createFrom().item(OidcTenantConfig().apply {
                    this.setTenantId(podName)
                    this.setApplicationType(OidcTenantConfig.ApplicationType.SERVICE)
                    pod?.getAuthConfiguration()?.let { authConfig ->
                        this.setAuthServerUrl(authConfig.serverUrl)
                        this.setClientId(authConfig.clientId)
                        this.credentials.setSecret(authConfig.clientSecret)
                    }
                })
            }
    }

}

@ApplicationScoped
@IfBuildProperty(name = "quarkus.keycloak.policy-enforcer.enable", stringValue = "true")
class KvasirTenantPolicyConfigResolver() : TenantPolicyConfigResolver {
    override fun resolve(
        routingContext: RoutingContext,
        tenantConfig: OidcTenantConfig?,
        requestContext: OidcRequestContext<KeycloakPolicyEnforcerTenantConfig>
    ): Uni<KeycloakPolicyEnforcerTenantConfig?> {
        val tenantId = tenantConfig?.tenantId?.getOrNull()?.takeIf { it != "Default" }
        return if (tenantId == null) {
            // Default policy config resolver
            Uni.createFrom().nullItem()
        } else {
            Uni.createFrom().item(
                KeycloakPolicyEnforcerTenantConfig.builder()
                    .paths("/$tenantId/.profile").enforcementMode(PolicyEnforcerConfig.EnforcementMode.DISABLED)
                    .build()
            )
        }
    }

}

@Singleton
@IfBuildProperty(name = "quarkus.keycloak.policy-enforcer.enable", stringValue = "true")
class FixedKeycloakPolicyEnforcerAuthorizer(
    private val resolver: PolicyEnforcerResolver,
    private val blockingExecutor: BlockingSecurityExecutor
) : KeycloakPolicyEnforcerAuthorizer(), HttpSecurityPolicy {

    companion object {
        private const val POLICY_ENFORCER =
            "io.quarkus.keycloak.pep.runtime.KeycloakPolicyEnforcerAuthorizer#POLICY_ENFORCER"
    }

    override fun checkPermission(
        routingContext: RoutingContext,
        identity: Uni<SecurityIdentity>,
        requestContext: HttpSecurityPolicy.AuthorizationRequestContext
    ): Uni<CheckResult> {
        return identity.flatMap { identity ->
            if (identity.isAnonymous) {
                val tenantConfig = routingContext.get<OidcTenantConfig>(OidcTenantConfig::class.java.name)
                resolver.resolvePolicyEnforcer(routingContext, tenantConfig)
                    .flatMap { policyEnforcer ->
                        routingContext.put(POLICY_ENFORCER, policyEnforcer)
                        blockingExecutor.executeBlocking { policyEnforcer.pathMatcher.matches(routingContext.normalizedPath()) }
                            .flatMap { pathConfig ->
                                if (pathConfig != null && pathConfig.enforcementMode == PolicyEnforcerConfig.EnforcementMode.ENFORCING) {
                                    CheckResult.deny()
                                } else {
                                    checkPermissionInternalMadeAccessible(routingContext, identity)
                                }
                            }
                    }
            } else {
                checkPermissionInternalMadeAccessible(routingContext, identity)
            }
        }
    }

    private fun checkPermissionInternalMadeAccessible(
        routingContext: RoutingContext,
        securityIdentity: SecurityIdentity
    ): Uni<HttpSecurityPolicy.CheckResult> {
        val methodDef = KeycloakPolicyEnforcerAuthorizer::class.java.getDeclaredMethod(
            "checkPermissionInternal",
            RoutingContext::class.java,
            SecurityIdentity::class.java
        )
        methodDef.isAccessible = true
        return methodDef.invoke(this, routingContext, securityIdentity) as Uni<HttpSecurityPolicy.CheckResult>
    }

}