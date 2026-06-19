package kvasir.plugins.policyagent.openfga.auth

import io.quarkus.cache.Cache
import io.quarkus.cache.CacheName
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.config.PodConfig
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.plugins.policyagent.openfga.utils.getJWTProviderConfig
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import org.jose4j.jwt.consumer.JwtContext
import java.io.ByteArrayInputStream
import java.security.Key


private const val OIDC_WELL_KNOWN_PATH = "/.well-known/openid-configuration"
private const val SOLID_OIDC_ISSUER_RELATION = "http://www.w3.org/ns/solid/terms#oidcIssuer"
private const val JWKS_URI_KEY = "jwks_uri"

@ApplicationScoped
class JwtVerifier(
    private val vertx: Vertx,
    @param:ConfigProperty(name = "kvasir.auth.keycloak.url")
    private val keycloakServerUrl: String,
    @param:ConfigProperty(name = "kvasir.auth.keycloak.realm")
    private val kvasirRealm: String,
    @param:CacheName("well-known-cache")
    private val wellKnownCache: Cache,
    @param:CacheName("jwks-cache")
    private val jwksCache: Cache
) {

    private val httpClient = WebClient.create(vertx)

    /**
     * Verify the JWT signature using the issuer's JWKS endpoint.
     * @param jwt The JWT context to verify.
     * @param webId The WebID associated with the JWT, if any.
     * @param podConfig The Pod configuration.
     */
    fun verify(jwt: JwtContext, webId: String?, podConfig: PodConfig): Uni<Void> {
        return (if (webId != null) {
            /**
             * When the JWT has an associated WebID, we cannot trust the issuer claim directly,
             * but have to look up the WebID profile and verify the issuer based on the configured auth server.
             */
            httpClient.getAbs(webId).putHeader(HttpHeaders.ACCEPT, RDFMediaTypes.TURTLE).send().chain { resp ->
                try {
                    val oidcIssuer = ByteArrayInputStream(resp.bodyAsBuffer().bytes).use { inputStream ->
                        val model = Rio.parse(inputStream, webId, RDFFormat.TURTLE)
                        model.find { it.predicate.stringValue() == SOLID_OIDC_ISSUER_RELATION }?.`object`?.stringValue()
                    }
                    if (oidcIssuer == jwt.jwtClaims.issuer) {
                        Uni.createFrom().item(oidcIssuer)
                    } else {
                        Log.debug("JWT issuer '${jwt.jwtClaims.issuer}' does not match OIDC issuer '$oidcIssuer' from WebID profile '$webId'")
                        Uni.createFrom().failure(ClientErrorException(401))
                    }
                } catch (t: Throwable) {
                    Uni.createFrom().failure(t)
                }
            }
        } else {
            Uni.createFrom().item(jwt.jwtClaims.issuer)
        })
            .chain { issuer ->
                // Lookup issuer /.well-known/openid-configuration
                val targetUri = issuer.removeSuffix("/").plus(OIDC_WELL_KNOWN_PATH)
                wellKnownCache.getAsync(targetUri) { httpClient.getAbs(targetUri).send() }
            }
            .chain { resp ->
                try {
                    // Extract jwks_uri
                    val jwksUri = resp.bodyAsJsonObject().getString(JWKS_URI_KEY)
                    // Verify JWT signature using jwks_uri
                    verifyJwt(jwt, jwksUri, podConfig)
                } catch (t: Throwable) {
                    Uni.createFrom().failure(t)
                }
            }
    }

    private fun verifyJwt(jwt: JwtContext, jwksUri: String, podConfig: PodConfig): Uni<Void> {
        return getPublicKey(jwt, jwksUri)
            .chain { publicKey ->
                vertx.executeBlocking {
                    try {
                        val verifier = JwtConsumerBuilder()
                            .setRequireExpirationTime()
                            .setSkipDefaultAudienceValidation()
                            .apply {
                                getJWTProviderConfig(
                                    jwt.jwtClaims.issuer,
                                    podConfig,
                                    "$keycloakServerUrl/realms/$kvasirRealm"
                                )?.jwtAllowedClockSkewSeconds()
                                    ?.let { jwtAllowedClockSkewSeconds ->
                                        this.setAllowedClockSkewInSeconds(jwtAllowedClockSkewSeconds)
                                    }
                            }
                            .setVerificationKey(publicKey)
                            .build()
                        verifier.processContext(jwt)
                    } catch (t: Throwable) {
                        throw RuntimeException("JWT verification failed", t)
                    }
                }
            }
            .replaceWithVoid()
    }

    private fun getPublicKey(jwt: JwtContext, jwksUri: String): Uni<Key> {
        return jwksCache.getAsync(jwksUri) {
            httpClient.getAbs(jwksUri).send().map { it.bodyAsString() }
                .chain { jwksJson ->
                    vertx.executeBlocking {
                        try {
                            // Extract the algorithm from the unverified token header
                            val jws = JsonWebSignature()
                            jws.setCompactSerialization(jwt.jwt)
                            val alg = jws.getAlgorithmHeaderValue() // e.g., "RS256"
                            val jwks = JsonWebKeySet(jwksJson)


                            // Filter JWKS by criteria: findJsonWebKey(kid, kty, use, alg)
// We pass null for kid, but pass "RSA" or "EC" (kty) and the algorithm
                            val kty = if (alg.startsWith("RS")) "RSA" else "EC"
                            val jwk = jwks.findJsonWebKey(null, kty, "sig", alg)

                            requireNotNull(jwk) { "No key found matching algorithm: " + alg }
                            jwk.getKey()
                        } catch (t: Throwable) {
                            throw RuntimeException("Failed to parse JWKS or locate verification key", t)
                        }
                    }
                }
        }
    }

}