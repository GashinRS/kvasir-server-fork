package kvasir.plugins.policyagent.uma

import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.smallrye.jwt.runtime.auth.JWTAuthMechanism
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.PodStore
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import java.util.UUID

@IfBuildProperty(name = Constants.SOLID_UMA_POLICY_AGENT_ENABLED, stringValue = "true")
@Alternative
@Priority(1)
@ApplicationScoped
class CustomHttpAuthenticationMechanism(
    private val delegate: JWTAuthMechanism,
    private val podStore: PodStore,
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = KvasirConfig.BASE_URI_DEFAULT)
    private val baseUri: String,
) :
    HttpAuthenticationMechanism by delegate {

    override fun authenticate(
        context: RoutingContext,
        identityProviderManager: IdentityProviderManager?
    ): Uni<SecurityIdentity> {
        return context.request().getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.startsWith("Bearer ") }?.let {
            val jwt = it.removePrefix("Bearer ")
            // Currently skipping validation
            val firstPassJwtConsumer = JwtConsumerBuilder()
                .setSkipAllValidators()
                .setDisableRequireSignature()
                .setSkipSignatureVerification()
                .build()

            // TODO: check JWT signature, etc!!

            val jwtContext = firstPassJwtConsumer.process(jwt)
            // TODO: set identity permissions and subject based on correct claim names
            val identityBuilder = QuarkusSecurityIdentity.builder()
                .setPrincipal { jwtContext.jwtClaims.subject }
            jwtContext.jwtClaims.getStringListClaimValue("scope")?.forEach { scopePermission ->
                identityBuilder.addPermissionAsString(scopePermission)
            }

            Uni.createFrom().item(identityBuilder.build())
        } ?: delegate.authenticate(context, identityProviderManager)
    }

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> {
        val pathItems = context.request().path().split('/').filterNot { it.isBlank() }
        return if (pathItems.isEmpty()) {
            delegate.getChallenge(context)
        } else {
            val podName = pathItems.first()
            val podId = "${baseUri}$podName"
            podStore.getById(podId)
                .onItem().ifNull().failWith { NotFoundException() }
                .onItem().ifNotNull().transform { pod ->
                    val authServerUrl = pod?.getAuthConfiguration()?.serverUrl
                    // TODO: fetch ticket from configured Authorization Server?
                    val ticket = UUID.randomUUID().toString()
                    ChallengeData(
                        403,
                        HttpHeaders.WWW_AUTHENTICATE,
                        "UMA realm=\"solid\", as_uri=\"$authServerUrl\", ticket=\"$ticket\""
                    )
                }
        }
    }

}