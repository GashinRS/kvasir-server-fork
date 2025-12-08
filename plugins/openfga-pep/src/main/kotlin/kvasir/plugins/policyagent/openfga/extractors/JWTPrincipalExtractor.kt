package kvasir.plugins.policyagent.openfga.extractors

import kvasir.definitions.rdf.JSONObject
import org.jose4j.jwt.consumer.JwtContext

interface JWTPrincipalExtractor {

    fun extractPrincipalFromJWT(jwt: JwtContext): String

}

/**
 * A simple JWT principal extractor that extracts the principal from a specified claim in the JWT.
 * The claim name is provided via configuration.
 *
 * Configuration example:
 * {
 *   "attributeName": "preferred_username"
 * }
 */
open class SimpleJWTPrincipalExtractor(protected val config: JSONObject) : JWTPrincipalExtractor {

    private val attributeName = config["attribute-name"]?.toString()
        ?: throw IllegalArgumentException("Field 'attributeName' must be provided in the configuration")

    override fun extractPrincipalFromJWT(jwt: JwtContext): String {
        val claimValue = jwt.jwtClaims.getClaimValue(attributeName)
        return claimValue?.toString()
            ?: throw IllegalArgumentException("Attribute '$attributeName' not found in JWT claims")
    }

}

object DefaultJWTPrincipalExtractor : SimpleJWTPrincipalExtractor(mapOf("attribute-name" to "sub"))