package kvasir.plugins.policyagent.a4ds

import graphql.language.OperationDefinition
import graphql.parser.InvalidSyntaxException
import graphql.parser.Parser
import io.quarkus.logging.Log
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.smallrye.jwt.runtime.auth.JWTAuthMechanism
import io.quarkus.smallrye.jwt.runtime.auth.SmallRyeJwtConfig
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.Pod
import kvasir.definitions.kg.PodStoreFactory
import kvasir.plugins.policyagent.a4ds.utils.parseAsSecurityIdentity
import org.eclipse.microprofile.config.inject.ConfigProperty
import kotlin.jvm.optionals.getOrNull

private val MATCH_GLOBAL_GRAPHQL_ENDPOINT = "/[^/]*/query".toRegex()
private val MATCH_SLICE_GRAPHQL_ENDPOINT = "/[^/]*/slices/[^/]*/query".toRegex()

@Alternative
@Priority(1)
@ApplicationScoped
class CustomHttpAuthenticationMechanism() :
    HttpAuthenticationMechanism, JWTAuthMechanism(object : SmallRyeJwtConfig {
    override fun blockingAuthentication() = false
    override fun silent() = false
}) {

    @Inject
    lateinit var podStoreFactory: PodStoreFactory

    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = KvasirConfig.BASE_URI_DEFAULT)
    lateinit var baseUri: String

    @ConfigProperty(
        name = "kvasir.plugins.policy-agent.a4ds.default-uma-server-url",
        defaultValue = "http://localhost:4000/uma"
    )
    lateinit var defaultUmaServerUrl: String

    @Inject
    lateinit var umaClientFactory: UmaClientFactory

    override fun authenticate(
        context: RoutingContext,
        identityProviderManager: IdentityProviderManager?
    ): Uni<SecurityIdentity> {
        return ((context.request().getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.startsWith("Bearer") }?.let {
            // When the authorization header value is of type Bearer token, extract the JWT.
            val jwt = it.removePrefix("Bearer ")
            getUMAClient(context)
                .chain { umaClient ->
                    if (umaClient != null) {
                        mapRequestToScopes(context)
                            .chain { requestedScopes ->
                                umaClient.validateToken(jwt, requestedScopes)
                            }
                            .chain { _ ->
                                val identity = parseAsSecurityIdentity(jwt)
                                Uni.createFrom().item(identity)
                            }
                    } else {
                        // When no authorization server URL is found for the request context, revert to the default auth mechanism.
                        super.authenticate(context, identityProviderManager)
                    }
                }
        }) ?: Uni.createFrom().nullItem())
            .onFailure().recoverWithItem { err ->
                Log.warn("Authentication failed: ${err.message}", err)
                // In case of failure, return an empty result to trigger a challenge
                null
            }
            .invoke { resp ->
                Log.debug("authenticate response: $resp")
            }
    }

    override fun getChallenge(context: RoutingContext): Uni<ChallengeData> {
        // Get the authorization server URL for the request context
        return getUMAClient(context)
            .chain { umaClient ->
                if (umaClient != null) {
                    // Determine the scopes required for this request
                    mapRequestToScopes(context)
                        .chain { requestedScopes ->
                            // Retrieve a UMA ticket for this request
                            umaClient.getTicket(context.request().absoluteURI(), requestedScopes)
                        }
                        .map { ticket ->
                            // Return the appropriate challenge. When no ticket is required (public resource), this will be null.
                            ticket?.let {
                                ChallengeData(
                                    401,
                                    HttpHeaders.WWW_AUTHENTICATE,
                                    "UMA realm=\"solid\", as_uri=\"${umaClient.authServerUrl}\", ticket=\"$it\""
                                )
                            }
                        }
                } else {
                    super.getChallenge(context)
                }
            }
            .onFailure().recoverWithItem { err ->
                Log.warn("Failed to get UMA challenge: ${err.message}", err)
                // In case of failure, fall back to the default challenge
                ChallengeData(401, null, null)
            }
    }

    fun getUMAClient(context: RoutingContext): Uni<UmaClient?> {
        return context.request().path().split('/').filterNot { it.isBlank() }
            .takeIf { it.isNotEmpty() }?.let { pathItems ->
                val podName = pathItems.first()
                val podId = "${baseUri}$podName"
                // Get AS URI for the Pod
                return podStoreFactory.createPodStore().findById(podId)
                    .chain { pod ->
                        // Pod is non-null, otherwise we would not have received the event
                        pod as Pod
                        // Get Auth Server URL from Pod configuration
                        val authServerUrl = pod.getAuthConfiguration()?.get("authServerUrl") ?: defaultUmaServerUrl
                        if (authServerUrl is String) {
                            umaClientFactory.createClient(authServerUrl)
                        } else {
                            Uni.createFrom()
                                .failure(IllegalStateException("Pod $podId does not have a valid authServerUrl in its authConfiguration"))
                        }
                    }
            } ?: Uni.createFrom().nullItem()
    }

    private fun mapRequestToScopes(context: RoutingContext): Uni<Set<Scope>> {
        return if (context.request().path().matches(MATCH_GLOBAL_GRAPHQL_ENDPOINT) || context.request().path()
                .matches(MATCH_SLICE_GRAPHQL_ENDPOINT)
        ) {
            when (context.request().method()) {
                HttpMethod.GET -> Uni.createFrom().item {
                    extractScopeForGraphQLRequest(
                        context.request().getParam("query"),
                        context.request().getParam("operationName")
                    )
                }

                HttpMethod.POST -> getBodyAsString(context).map { bodyStr ->
                    val body = JsonObject(bodyStr)
                    extractScopeForGraphQLRequest(body.getString("query"), body.getString("operationName"))
                }

                else -> Uni.createFrom().failure(
                    IllegalArgumentException(
                        "Unsupported HTTP method for GraphQL endpoint: ${
                            context.request().method()
                        }"
                    )
                )
            }
        } else {
            Uni.createFrom().item {
                when (context.request().method()) {
                    HttpMethod.GET, HttpMethod.HEAD -> setOf(Scope.READ)
                    HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH -> setOf(Scope.WRITE)
                    HttpMethod.DELETE -> setOf(Scope.DELETE)
                    else -> emptySet()
                }
            }
        }
    }

    private fun getBodyAsString(context: RoutingContext): Uni<String> {
        return Uni.createFrom()
            .completionStage {
                context.request().body().map { buffer -> buffer.toString(Charsets.UTF_8) }.toCompletionStage()
            }
    }

    private fun extractScopeForGraphQLRequest(query: String, operationName: String?): Set<Scope> {
        try {
            val graphqlRequest = Parser.parse(query)
            val operations = graphqlRequest.definitions.filterIsInstance<OperationDefinition>().map { it.operation }
            return when {
                operationName != null -> graphqlRequest.getOperationDefinition(operationName)?.getOrNull()
                    ?.let { setOf(mapOperationType(it.operation)) }
                    ?: throw IllegalArgumentException("GraphQL request does not contain an operation with name '$operationName'.")

                operations.isEmpty() -> throw IllegalArgumentException("GraphQL request does not contain any operations.")

                else -> operations.map { mapOperationType(it) }.toSet()
            }
        } catch (e: InvalidSyntaxException) {
            throw IllegalArgumentException(e)
        }
    }

    private fun mapOperationType(
        operation: OperationDefinition.Operation
    ): Scope {
        return when (operation) {
            OperationDefinition.Operation.QUERY, OperationDefinition.Operation.SUBSCRIPTION -> Scope.READ
            OperationDefinition.Operation.MUTATION -> Scope.WRITE
        }
    }

}