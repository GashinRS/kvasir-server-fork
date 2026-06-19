package kvasir.plugins.policyagent.openfga

import dev.openfga.sdk.api.client.model.ClientTupleKey
import idlab.quarkus.ext.pep.openfga.runtime.cdi.OpenFgaManager
import idlab.quarkus.ext.pep.openfga.runtime.config.OpenFgaPolicyEnforcerConfig
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.logging.Log
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser
import io.smallrye.mutiny.infrastructure.Infrastructure
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ClientErrorException
import kvasir.definitions.auth.AuthHandler
import kvasir.plugins.policyagent.openfga.delegated.PostCheckDelegator
import kvasir.plugins.policyagent.openfga.extractors.DefaultContextExtractor
import kvasir.plugins.policyagent.openfga.extractors.DefaultRelationExtractor
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import kvasir.plugins.policyagent.openfga.utils.getContextForParents
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import java.security.Principal
import java.time.Duration

@ApplicationScoped
@IfBuildProperty(name = "openfga.pep.enabled", stringValue = "true")
class OpenFgaAuthHandler(
    private val fgaManager: OpenFgaManager,
    private val openFgaPolicyEnforcerConfig: OpenFgaPolicyEnforcerConfig,
    @param:ConfigProperty(name = "kvasir-ext.openfga.pep.check-timeout-ms")
    private val checkTimeoutMs: Long
) : AuthHandler {

    private val postCheckHook = PostCheckDelegator()

    override fun handle(ctx: RoutingContext) {
        try {
            // 1. Capture the exact Vert.x Event Loop thread processing THIS specific request
            val currentVertxContext = ctx.vertx().getOrCreateContext()

            val relEx = DefaultRelationExtractor()
            val contextEx = DefaultContextExtractor()

            val securityIdentity = (ctx.user() as QuarkusHttpUser).securityIdentity

            val store = extractStore(ctx)
            val subject = extractSubject(securityIdentity.principal)
            val relation = relEx.extractRelation(ctx.request().method())
            val `object` = extractObject(ctx.request().path())
            val context = contextEx.extract()
            val tuple = ClientTupleKey().user(subject).relation(relation)._object(`object`)
            val contextualTuples = getContextForParents(ctx.request().path())
            fgaManager.check(store, tuple, contextualTuples, context)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .ifNoItem().after(Duration.ofMillis(checkTimeoutMs))
                .failWith { RuntimeException("OpenFGA check timed out") }
                .chain { isAllowed ->
                    postCheckHook.handle(ctx.request(), store, tuple, contextualTuples, context, isAllowed)
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .ifNoItem().after(Duration.ofMillis(checkTimeoutMs))
                        .failWith { RuntimeException("Post-check processing timed out") }
                }
                // 2. Force Mutiny to complete directly on the captured Vert.x Context Thread
                .emitOn { command -> currentVertxContext.runOnContext { command.run() } }
                .subscribe().with(
                    { _ ->
                        if (!ctx.failed() && !ctx.response().ended()) {
                            ctx.next()
                        }
                    },
                    { err ->
                        if (!ctx.failed() && !ctx.response().ended()) {
                            if (err is ClientErrorException) {
                                ctx.fail(err.response.status)
                            } else {
                                Log.warn("Exception in OpenFGA Auth Handler", err)
                                ctx.fail(err)
                            }
                        }
                    }
                )
        } catch (e: Exception) {
            Log.warn("Exception while processing OpenFGA auth check", e)
            ctx.fail(500)
        }
    }

    private fun extractStore(ctx: RoutingContext): String {
        val pathSegments = ctx.request().path().removePrefix("/").split("/")
        return if (pathSegments.isNotEmpty()) {
            pathSegments[0]
        } else {
            throw IllegalArgumentException("No valid store found in the request path")
        }
    }

    private fun extractSubject(principal: Principal?): String {
        val userName = if (principal == null || principal.name.isBlank()) {
            openFgaPolicyEnforcerConfig.identifiers().anonymousUser()
        } else {
            principal.name
        }
        return "${openFgaPolicyEnforcerConfig.extractors().types().subject()}:${contextualizeSubject(userName)}"
    }

    private fun extractObject(path: String): String {
        return "${openFgaPolicyEnforcerConfig.extractors().types().`object`()}:${path}"
    }

    override fun getPrincipalForProxiedRequest(proxiedRequest: HttpServerRequest): Principal? {
        return proxiedRequest.getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.startsWith("Bearer") }
            ?.let { authHeader ->
                val jwt = authHeader.removePrefix("Bearer ")
                val firstPassJwtConsumer = JwtConsumerBuilder()
                    .setSkipAllValidators()
                    .setDisableRequireSignature()
                    .setSkipSignatureVerification()
                    .build()

                val jwtContext = firstPassJwtConsumer.process(jwt)
                val identityBuilder = QuarkusSecurityIdentity.builder()
                    .setPrincipal { jwtContext.jwtClaims.getClaimValueAsString("preferred_username") }
                identityBuilder.build().principal
            }
    }

}