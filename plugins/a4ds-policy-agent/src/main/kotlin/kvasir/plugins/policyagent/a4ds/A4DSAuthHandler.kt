package kvasir.plugins.policyagent.a4ds

import io.quarkus.arc.properties.IfBuildProperty
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.auth.AuthHandler
import kvasir.plugins.policyagent.a4ds.utils.parseAsSecurityIdentity
import java.security.Principal

/**
 * Handles auth for the storage api, as this is not handled by the Quarkus security layer.
 */
@IfBuildProperty(name = Constants.SOLID_UMA_POLICY_AGENT_ENABLED, stringValue = "true")
@ApplicationScoped
class A4DSAuthHandler(
    private val httpAuthenticationMechanism: CustomHttpAuthenticationMechanism
) : AuthHandler {
    override fun getPrincipalForProxiedRequest(proxiedRequest: HttpServerRequest): Principal? {
        return proxiedRequest.getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.startsWith("Bearer") }?.let {
            // When the authorization header value is of type Bearer token, extract the JWT.
            val jwt = it.removePrefix("Bearer ")
            parseAsSecurityIdentity(jwt).principal
        }
    }

    override fun handle(ctx: RoutingContext) {
        // Do nothing, already handled by the CustomHttpAuthenticationMechanism
        ctx.next()
    }
}