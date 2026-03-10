package kvasir.plugins.policyagent.openfga.auth

import io.quarkus.cache.Cache
import io.quarkus.cache.CacheName
import io.quarkus.cache.CaffeineCache
import io.quarkus.logging.Log
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.quarkus.smallrye.jwt.runtime.auth.JWTAuthMechanism
import io.quarkus.smallrye.jwt.runtime.auth.SmallRyeJwtConfig
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.config.HttpConfig
import kvasir.definitions.config.PodConfig
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.reactive.toUni
import kvasir.plugins.policyagent.openfga.extractors.DefaultJWTPrincipalExtractor
import kvasir.plugins.policyagent.openfga.extractors.JWTPrincipalExtractor
import kvasir.plugins.policyagent.openfga.utils.getJWTProviderConfig
import kvasir.plugins.policyagent.openfga.utils.parseJWT
import kvasir.utils.pod.PodConfigProvider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.base64url.Base64Url
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import org.jose4j.jwt.consumer.JwtContext
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.jvm.optionals.getOrNull

private const val DPOP_HEADER = "DPoP"
private const val DPOP_SCHEME = "dpop"
private const val BEARER_SCHEME = "bearer"
private const val CNF_CLAIM = "cnf"
private const val JTI_CLAIM = "jti"
private const val ATH_CLAIM = "ath"
private const val HTU_CLAIM = "htu"
private const val HTM_CLAIM = "htm"
private const val WEBID_CLAIM = "webid"
private const val JKT_KEY = "jkt"
private const val JWK_KEY = "jwk"
private const val SOLID_AUDIENCE = "solid"

@Alternative
@Priority(1)
@ApplicationScoped
class CustomHttpAuthenticationMechanism :
    HttpAuthenticationMechanism, JWTAuthMechanism(object : SmallRyeJwtConfig {
    override fun blockingAuthentication() = Optional.of(false)
    override fun silent() = false
    override fun priority() = 1
}) {

    @Inject
    private lateinit var config: HttpConfig

    @Inject
    private lateinit var podConfigProvider: PodConfigProvider

    @Inject
    private lateinit var jwtVerifier: JwtVerifier

    @Inject
    @CacheName("dpop-jti-cache")
    private lateinit var jtiCache: Cache

    @ConfigProperty(name = "kvasir.auth.keycloak.url")
    private lateinit var keycloakServerUrl: String

    @ConfigProperty(name = "kvasir.auth.keycloak.realm")
    private lateinit var kvasirRealm: String

    override fun authenticate(
        context: RoutingContext,
        identityProviderManager: IdentityProviderManager
    ): Uni<SecurityIdentity?> {
        return context.request().getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.lowercase().startsWith(BEARER_SCHEME) || it.lowercase().startsWith(DPOP_SCHEME) }
            ?.let { token ->
                val (scheme, token) = token.split(" ", limit = 2)
                podConfigProvider.getPodConfigForRequestContext(context.request())
                    .chain { podConfig ->
                        if (!scheme.equals(DPOP_SCHEME, ignoreCase = true) && podConfig.auth().requireDpop()) {
                            Log.debug("Request uses Bearer token while DPoP is required!")
                            Uni.createFrom().failure(ClientErrorException(401))
                        } else {
                            try {
                                val jwt = parseJWT(token)
                                verifyJWT(jwt, podConfig)
                                    .chain { webId ->
                                        // If DPoP is used, validate it
                                        if (scheme.equals(DPOP_SCHEME, ignoreCase = true)) {
                                            validateDPoP(jwt, context.request(), podConfig)
                                        } else {
                                            Uni.createFrom().voidItem()
                                        }
                                            .map { _ ->
                                                parseAsSecurityIdentity(
                                                    jwt,
                                                    loadJWTPrincipalExtractor(jwt.jwtClaims.issuer, podConfig),
                                                    webId
                                                )
                                            }
                                    }
                            } catch (err: Throwable) {
                                Log.debug("JWT parsing/verification failed: ${err.message}", err)
                                Uni.createFrom().failure(ClientErrorException(401))
                            }
                        }
                    }
            }?.onFailure()?.recoverWithNull() ?: Uni.createFrom().nullItem()
    }

    /**
     * Verify the JWT token
     * If a WebID token is detected, return the WebID as String
     */
    private fun verifyJWT(jwt: JwtContext, podConfig: PodConfig): Uni<String?> {
        // JWT must be issued by a known issuer (default OIDC or UMA server configured or overridden per pod)
        val knownIssuers =
            setOfNotNull(
                // Add Kvasir built-in Keycloak OIDC issuer
                "$keycloakServerUrl/realms/$kvasirRealm",
                // Add pod-configured OIDC/UMA issuers
                podConfig.auth().oidc().getOrNull()?.serverUrl(),
                podConfig.auth().uma().getOrNull()?.serverUrl()
            )
        val webId = (podConfig.auth().enableSolidWebId() && jwt.jwtClaims.audience.contains(
            SOLID_AUDIENCE
        ) && jwt.jwtClaims.hasClaim(WEBID_CLAIM))
        return if (jwt.jwtClaims.issuer in knownIssuers || webId) {
            // Execute verification
            val webIdValue = jwt.jwtClaims.getClaimValueAsString(WEBID_CLAIM)
            jwtVerifier.verify(jwt, jwt.jwtClaims.getClaimValueAsString(WEBID_CLAIM), podConfig).map { webIdValue }
                .onFailure().recoverWithUni { err ->
                    Log.debug("JWT verification failed: ${err.message}")
                    Uni.createFrom().failure(ClientErrorException(401))
                }
        } else {
            Log.debug("JWT verification failed: unknown issuer ${jwt.jwtClaims.issuer}")
            Uni.createFrom().failure(ClientErrorException(401))
        }
    }

    private fun validateDPoP(jwt: JwtContext, request: HttpServerRequest, podConfig: PodConfig): Uni<Void> {
        return try {
            val cnf = jwt.jwtClaims.getClaimValue(CNF_CLAIM, Map::class.java)
                ?: throw IllegalArgumentException("Missing cnf claim")
            val jkt = (cnf[JKT_KEY] as? String) ?: throw IllegalArgumentException("Missing jkt in cnf claim")
            val dpop = request.getHeader(DPOP_HEADER) ?: throw IllegalArgumentException("Missing DPoP header")
            val dpopHeaderJson = JsonObject(Base64.getDecoder().decode(dpop.split(".").first()).decodeToString())
            val dpopPublicKey =
                PublicJsonWebKey.Factory.newPublicJwk(Json.encode(dpopHeaderJson.getJsonObject(JWK_KEY)))

            // 1. Validate DPoP token (and construct JwtContext)
            val dpopJwt = JwtConsumerBuilder().setVerificationKey(dpopPublicKey.publicKey).build().process(dpop)

            val thumbprint = Base64Url.encode(dpopPublicKey.calculateThumbprint("SHA-256"))
            val jti = dpopJwt.jwtClaims.getClaimValueAsString(JTI_CLAIM)

            // 2. Check if token is bound to dpop proof
            val ath = dpopJwt.jwtClaims.getClaimValueAsString(ATH_CLAIM)
            val jwtBytes = jwt.jwt.toByteArray()
            val messageDigest = MessageDigest.getInstance("SHA-256")
            messageDigest.update(jwtBytes)
            val digest = messageDigest.digest()
            val hash = Base64Url.encode(digest).trimEnd('=')
            if (!podConfig.auth().skipDpopAthCheck()) {
                require(hash == ath) { "DPoP ath claim does not match SHA-256 hash of access token" }
            }

            // 3. Validate thumbprint
            require(thumbprint == jkt) { "DPoP proof thumbprint does not match cnf.jkt" }

            // 4. Validate htu and htm claims
            require(getResourceUri(request) == dpopJwt.jwtClaims.getClaimValueAsString(HTU_CLAIM)) {
                "DPoP htu claim does not match request URI"
            }
            require(request.method().name() == dpopJwt.jwtClaims.getClaimValueAsString(HTM_CLAIM)) {
                "DPoP htm claim does not match request method"
            }

            // 5. Protect against replay attacks
            (jtiCache.`as`(CaffeineCache::class.java).getIfPresent<String>(jti)
                ?.toUni() ?: Uni.createFrom().nullItem())
                .chain { value ->
                    if (value != null) {
                        Log.debug("DPoP replay attack detected for jti: $jti")
                        Uni.createFrom().failure(ClientErrorException(401))
                    } else {
                        // 6. Store JTI to prevent future replay attacks
                        jtiCache.`as`(CaffeineCache::class.java).put(jti, CompletableFuture.completedFuture(jti))
                        Uni.createFrom().voidItem()
                    }
                }
        } catch (t: Throwable) {
            Log.debug("DPOP validation failed: ${t.message}")
            Uni.createFrom().failure(ClientErrorException(401))
        }
    }

    private fun parseAsSecurityIdentity(
        jwt: JwtContext,
        principalExtractor: JWTPrincipalExtractor,
        webId: String?
    ): SecurityIdentity {
        val identityBuilder = QuarkusSecurityIdentity.builder()
            .setPrincipal { webId ?: principalExtractor.extractPrincipalFromJWT(jwt) }
            .addAttributes(jwt.jwtClaims.claimsMap)
        return identityBuilder.build()
    }

    private fun loadJWTPrincipalExtractor(issuer: String, podConfig: PodConfig): JWTPrincipalExtractor {
        val extractorConf =
            getJWTProviderConfig(issuer, podConfig, "$keycloakServerUrl/realms/$kvasirRealm")?.principalExtractor()
                ?.getOrNull()
        return if (extractorConf != null) {
            val clazz = CustomHttpAuthenticationMechanism::class.java.classLoader.loadClass(extractorConf.className())
            clazz.getDeclaredConstructor(JSONObject::class.java)
                .newInstance(extractorConf.config()) as JWTPrincipalExtractor
        } else {
            DefaultJWTPrincipalExtractor
        }
    }

    private fun getResourceUri(request: HttpServerRequest): String {
        return "${config.baseUri().removeSuffix("/")}${request.path()}"
    }

}

