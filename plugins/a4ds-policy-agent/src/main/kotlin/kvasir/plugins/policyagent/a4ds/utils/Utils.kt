package kvasir.plugins.policyagent.a4ds.utils

import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import org.jose4j.jwt.consumer.JwtConsumerBuilder

internal fun parseAsSecurityIdentity(jwt: String): SecurityIdentity {
    // No validation required here (not the responsibility of this function)
    // Parse the JWT in order to pass along the identity and other relevant information.
    val firstPassJwtConsumer = JwtConsumerBuilder()
        .setSkipAllValidators()
        .setDisableRequireSignature()
        .setSkipSignatureVerification()
        .build()

    val jwtContext = firstPassJwtConsumer.process(jwt)
    // TODO: set identity based on the agreed upon attribute (currently using subject with a fallback to jti)
    val identityBuilder = QuarkusSecurityIdentity.builder()
        .setPrincipal { jwtContext.jwtClaims.subject ?: jwtContext.jwtClaims.jwtId }
    return identityBuilder.build()
}