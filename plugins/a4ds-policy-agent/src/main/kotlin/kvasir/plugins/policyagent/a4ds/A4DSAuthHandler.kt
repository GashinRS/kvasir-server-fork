package kvasir.plugins.policyagent.a4ds

import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.auth.AuthHandler
import java.security.Principal

/**
 * Handles auth for the storage api, as this is not handled by the Quarkus security layer.
 */
//@IfBuildProperty(name = Constants.SOLID_UMA_POLICY_AGENT_ENABLED, stringValue = "true")
//@ApplicationScoped
class A4DSAuthHandler(
    private val httpAuthenticationMechanism: CustomHttpAuthenticationMechanism
) : AuthHandler {
    override fun getPrincipalForProxiedRequest(proxiedRequest: HttpServerRequest): Principal? {
        // TODO
        return null
    }

    override fun handle(ctx: RoutingContext) {
        httpAuthenticationMechanism.sendChallenge(ctx)!!
            .chain { sendChallenge ->
                if (!sendChallenge!!) {
                    httpAuthenticationMechanism.authenticate(ctx, null).replaceWithVoid()
                        .invoke { _ ->
                            // Continue processing
                            ctx.next()
                        }
                } else {
                    // End request
                    ctx.response().end()
                    Uni.createFrom().voidItem()
                }
            }
            .onFailure().invoke { t ->
                Log.debug("Authentication failed: ${t.message}", t)
                ctx.fail(401)
            }
            .subscribe().with { }
    }
}