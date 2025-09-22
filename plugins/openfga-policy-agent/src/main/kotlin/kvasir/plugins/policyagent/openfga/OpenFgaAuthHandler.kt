package kvasir.plugins.policyagent.openfga

import idlab.quarkus.ext.pep.openfga.runtime.OpenFgaManager
import idlab.quarkus.ext.pep.openfga.runtime.config.OpenFgaPolicyEnforcerConfig
import io.quarkiverse.openfga.client.model.RelObject
import io.quarkiverse.openfga.client.model.RelTupleDefinition
import io.quarkiverse.openfga.client.model.RelUser
import io.quarkus.logging.Log
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser
import io.vertx.core.http.HttpHeaders
import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.auth.AuthHandler
import kvasir.plugins.policyagent.openfga.extractors.DefaultRelationExtractor
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import kvasir.plugins.policyagent.openfga.utils.getContextForParents
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import java.security.Principal

@ApplicationScoped
class OpenFgaAuthHandler(
    private val fgaManager: OpenFgaManager,
    private val openFgaPolicyEnforcerConfig: OpenFgaPolicyEnforcerConfig
) : AuthHandler {
    override fun handle(ctx: RoutingContext) {
        val relEx = DefaultRelationExtractor()

        val securityIdentity = (ctx.user() as QuarkusHttpUser).securityIdentity

        fgaManager.storeNames.flatMap { storeNames ->
            val store = extractStore(ctx, storeNames)
            val subject = extractSubject(securityIdentity.principal)
            val relation = relEx.extractRelation(ctx.request().method())
            val `object` = extractObject(ctx.request().path())
            val tuple =
                RelTupleDefinition.builder().user(RelUser.valueOf(subject)).relation(relation)
                    .`object`(RelObject.valueOf(`object`)).build()
            fgaManager.check(store, tuple, getContextForParents(ctx.request().path()))
        }.subscribe().with(
            { granted ->
                Log.debugf("Granted [%s]", granted)
                if (granted) {
                    ctx.next()
                } else {
                    ctx.fail(403)
                }
            },
            ctx::fail
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