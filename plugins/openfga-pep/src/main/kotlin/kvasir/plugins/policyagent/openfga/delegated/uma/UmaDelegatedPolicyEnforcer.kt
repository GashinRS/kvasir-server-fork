package kvasir.plugins.policyagent.openfga.delegated.uma

import io.quarkus.arc.Unremovable
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import kvasir.plugins.policyagent.openfga.OpenFgaConstants
import kvasir.plugins.policyagent.openfga.delegated.DelegatedCheckParams
import kvasir.plugins.policyagent.openfga.delegated.DelegatedPolicyEnforcer
import kvasir.plugins.policyagent.openfga.utils.parseJWT
import kvasir.utils.pod.PodConfigProvider
import kotlin.jvm.optionals.getOrNull

@ApplicationScoped
@Unremovable // DelegatedPolicyEnforcers are discovered via programmatic lookup, so CDI may think they are unused and remove them otherwise
class UmaDelegatedPolicyEnforcer : DelegatedPolicyEnforcer {

    @Inject
    lateinit var podConfigProvider: PodConfigProvider

    @Inject
    lateinit var umaClientFactory: UmaClientFactory

    override fun id(): String = "uma"

    override fun isAllowed(params: DelegatedCheckParams): Uni<Boolean> {
        val request = params.request
        return getUMAClient(request)
            .chain { umaClient ->
                val token = request.getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.startsWith("Bearer") }
                    ?.removePrefix("Bearer ")?.trim()
                // Determine the scopes required for this request
                val requestedScopes = determineScopes(params.checkedTuple.relation)
                if (umaClient == null) {
                    Uni.createFrom().item(false)
                } else if (token != null) {
                    val parsedToken = parseJWT(token)
                    val issuer = parsedToken.jwtClaims.issuer
                    if (issuer == umaClient.authServerUrl) {
                        // If a token is present and the issuer matches the configured server, validate it with the UMA server
                        umaClient.validateToken(token, requestedScopes).map { true }
                    } else {
                        // Issuer does not match, disallow access
                        Uni.createFrom().item(false)
                    }
                } else {
                    // If no token is present, proceed to get a UMA ticket
                    // Retrieve a UMA ticket for this request
                    umaClient.getTicket(request.absoluteURI(), requestedScopes)
                        .map { ticket ->
                            if (ticket != null) {
                                // Modify response code to 401 to indicate that authorization is required
                                request.response().statusCode = 401
                                // Write the ticket as a WWW-Authenticate challenge header
                                request.response().putHeader(
                                    HttpHeaders.WWW_AUTHENTICATE,
                                    "UMA realm=\"solid\", as_uri=\"${umaClient.authServerUrl}\", ticket=\"$ticket\""
                                )
                                false
                            } else {
                                // No ticket means the resource is public, allow access
                                true
                            }
                        }
                        .onFailure().recoverWithItem { err ->
                            Log.warn("Failed to get UMA challenge: ${err.message}", err)
                            // In case of failure, disallow access
                            false
                        }
                }
            }
    }

    fun getUMAClient(request: HttpServerRequest): Uni<UmaClient?> {
        return podConfigProvider.getPodConfigForRequestContext(request)
            .map { it.auth().uma().getOrNull()?.serverUrl() }
            .onItem().ifNotNull().transformToUni { umaServerUrl ->
                umaClientFactory.createClient(umaServerUrl!!)
            }
            .onItem().ifNull().switchTo(Uni.createFrom().nullItem())
    }

    private fun determineScopes(permission: String): Set<Scope> {
        return when (permission) {
            OpenFgaConstants.CAN_READ_PERMISSION -> setOf(Scope.READ)
            OpenFgaConstants.CAN_WRITE_PERMISSION -> setOf(Scope.WRITE)
            OpenFgaConstants.CAN_DELETE_PERMISSION -> setOf(Scope.DELETE)
            else -> throw IllegalArgumentException("Unknown permission '$permission' for UMA scope mapping.")
        }
    }

}