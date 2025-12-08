package kvasir.plugins.policyagent.openfga

import dev.openfga.sdk.api.client.model.ClientTupleKey
import idlab.quarkus.ext.pep.openfga.runtime.cdi.OpenFgaManager
import idlab.quarkus.ext.pep.openfga.runtime.config.OpenFgaPolicyEnforcerConfig
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.logging.Log
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser
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
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import java.security.Principal

@ApplicationScoped
@IfBuildProperty(name = "openfga.pep.enabled", stringValue = "true")
class OpenFgaAuthHandler(
    private val fgaManager: OpenFgaManager,
    private val openFgaPolicyEnforcerConfig: OpenFgaPolicyEnforcerConfig
) : AuthHandler {

    private val postCheckHook = PostCheckDelegator()

    override fun handle(ctx: RoutingContext) {
        val relEx = DefaultRelationExtractor()
        val contextEx = DefaultContextExtractor()

        val securityIdentity = (ctx.user() as QuarkusHttpUser).securityIdentity

        fgaManager.storeNames.flatMap { storeNames ->
            val store = extractStore(ctx, storeNames)
            val subject = extractSubject(securityIdentity.principal)
            val relation = relEx.extractRelation(ctx.request().method())
            val `object` = extractObject(ctx.request().path())
            val context = contextEx.extract()
            val tuple = ClientTupleKey().user(subject).relation(relation)._object(`object`)
            val contextualTuples = getContextForParents(ctx.request().path())
            fgaManager.check(store, tuple, contextualTuples, context)
                .chain { isAllowed ->
                    postCheckHook.handle(ctx.request(), store, tuple, contextualTuples, context, isAllowed)
                }
        }
            .subscribe().with(
                { _ ->
                    ctx.next()
                },
                { err ->
                    if (err is ClientErrorException) {
                        ctx.fail(err.response.status)
                    } else {
                        Log.warn("Exception in OpenFGA Auth Handler", err)
                        ctx.fail(err)
                    }
                }
            )
    }

    private fun extractStore(ctx: RoutingContext, existingStores: Set<String>): String {
        val pathSegments = ctx.request().path().removePrefix("/").split("/")
        return if (pathSegments.isNotEmpty() && existingStores.contains(pathSegments[0])) {
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