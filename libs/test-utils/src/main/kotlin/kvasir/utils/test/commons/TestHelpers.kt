package kvasir.utils.test.commons

import io.quarkus.logging.Log
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Providers
import kvasir.definitions.config.*
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.changes.ChangeStatusCode
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.pod.PodConfigProvider
import org.eclipse.microprofile.config.ConfigProvider
import java.time.Duration
import java.util.*
import kotlin.math.roundToLong

@ApplicationScoped
class TestHelpers(
    val httpConfig: HttpConfig,
    val repositoryFactory: RepositoryFactory,
    val kg: Instance<KnowledgeGraph>,
    val vertx: Vertx,
    private val providers: Providers
) {

    private val webClient = WebClient.create(vertx, WebClientOptions().setFollowRedirects(false))

    fun getPodUri(podId: String): String {
        return "${httpConfig.baseUri().removeSuffix("/")}/$podId"
    }

    fun queryKGViaHTTP(
        q: Any,
        podUri: String,
        sliceUri: String? = null
    ): QueryResult {
        val requestUri = "${sliceUri ?: podUri}/query"
        return given()
            .contentType(MediaType.APPLICATION_JSON)
            .body(q)
            .post(requestUri)
            .then()
            .statusCode(200)
            .extract().body().`as`(QueryResult::class.java)
    }

    /**
     * Schedules a Change Request via the HTTP API and then waits for the request to be processed,
     * resulting in the expected state (or committed if no explicit expected result is specified).
     *
     * @return The id of the ProcessedChange resulting from the request
     */
    fun requestChangeViaHTTPSync(
        body: Any,
        podUri: String,
        expectedResult: ChangeStatusCode = ChangeStatusCode.COMMITTED,
        sliceUri: String? = null,
        optionalAuthToken: String? = null
    ): String {
        // Perform the request
        val requestUri = "${sliceUri ?: podUri}/changes"

        val changeRequestUri = given()
            .apply {
                if (optionalAuthToken != null) {
                    this.auth().oauth2(optionalAuthToken)
                }
            }
            .contentType(RDFMediaTypes.JSON_LD)
            .body(body)
            .post(requestUri)
            .then()
            .statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        // Wait for the request to be committed
        val processedChangeUri = waitForChangeRequest(
            changeRequestUri,
            podUri,
            expectedResult,
            optionalAuthToken = optionalAuthToken
        ).await().indefinitely()

        // Extract the ProcessedChange ID from the URI (the last segment)
        val processedChangeId = processedChangeUri.substringAfterLast('/')
        return processedChangeId
    }

    fun waitForChangeRequest(
        changeRequestUri: String,
        podUri: String,
        expectedResult: ChangeStatusCode = ChangeStatusCode.COMMITTED,
        retryInitialDelay: Duration = Duration.ofMillis(200),
        delayFactor: Double = 1.2,
        optionalAuthToken: String? = null
    ): Uni<String> {
        return webClient.getAbs(changeRequestUri)
            .apply {
                if (optionalAuthToken != null) {
                    this.bearerTokenAuthentication(optionalAuthToken)
                }
            }
            .send()
            .chain { response ->
                // If the Change API is trying to redirect...
                if (response.statusCode() == 303) {
                    // Fetch the Location header
                    webClient.getAbs(response.getHeader(HttpHeaders.LOCATION))
                        .apply {
                            if (optionalAuthToken != null) {
                                this.bearerTokenAuthentication(optionalAuthToken)
                            }
                        }
                        .send().chain { processedResp ->
                            val processedChange =
                                JsonLdHelper.decode(processedResp.bodyAsString(), ProcessedChange::class.java)
                            if (processedChange.getStatusCode() == expectedResult) {
                                Uni.createFrom().item(processedChange.id)
                            } else {
                                Uni.createFrom()
                                    .failure(RuntimeException("Change request resulted in status '${processedChange.getStatusCode()}', expected '$expectedResult'"))
                            }
                        }
                } else {
                    Uni.createFrom().failure(StillProcessingException())
                }
            }
            .onFailure(StillProcessingException::class.java).recoverWithUni { _ ->
                Log.debug("Change request still processing, retrying in $retryInitialDelay...")
                Uni.createFrom().voidItem().onItem().delayIt().by(retryInitialDelay).chain { _ ->
                    waitForChangeRequest(
                        changeRequestUri,
                        podUri,
                        expectedResult,
                        retryInitialDelay.plusMillis((retryInitialDelay.toMillis() * delayFactor).roundToLong()),
                        delayFactor,
                        optionalAuthToken
                    )
                }
            }
    }

    fun <T> waitForCondition(
        fetcher: () -> Uni<T>,
        conditionChecker: (T) -> Boolean,
        retryInitialDelay: Duration = Duration.ofMillis(200),
        delayFactor: Double = 1.2
    ): Uni<T> {
        return fetcher.invoke()
            .chain { result ->
                if (conditionChecker.invoke(result)) {
                    Uni.createFrom().item(result)
                } else {
                    Uni.createFrom().failure(StillProcessingException())
                }
            }
            .onFailure(StillProcessingException::class.java).recoverWithUni { _ ->
                Uni.createFrom().voidItem().onItem().delayIt().by(retryInitialDelay).chain { _ ->
                    waitForCondition(
                        fetcher,
                        conditionChecker,
                        retryInitialDelay.plusMillis((retryInitialDelay.toMillis() * delayFactor).roundToLong()),
                        delayFactor
                    )
                }
            }
    }

}

class StillProcessingException : RuntimeException()

inline fun <reified T> QueryResult.getDataField(name: String): T? {
    return this.data?.get(name)?.let {
        if (it is T) it else null
    }
}

fun getTokenForClient(clientId: String, clientSecret: String): String {
    val oidcServerUrl = ConfigProvider.getConfig().getValue("kvasir.pod.auth.oidc.server-url", String::class.java)
    val basicAuth = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
    return given()
        .header(HttpHeaders.AUTHORIZATION, "Basic $basicAuth")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .formParam("grant_type", "client_credentials")
        .post("${oidcServerUrl}/protocol/openid-connect/token")
        .then()
        .statusCode(200)
        .extract()
        .path("access_token")
}

class TestPodConfig(
    private val name: String,
    private val ownerUserId: String,
    val clients: List<TestGenerateClientConfig> = emptyList()
) : BootstrapPodConfig {
    override fun name(): String {
        return name
    }

    override fun ownerUserId(): Optional<String> {
        return Optional.of(ownerUserId)
    }

    override fun autoRegisterUma(): Boolean {
        return false
    }

    override fun autoRegisterHttpEndpointPolicyEnforcer(): Boolean {
        return false
    }

    override fun generateClients(): Optional<List<GenerateClientConfig>> {
        return if (clients.isNotEmpty()) {
            Optional.of(clients)
        } else {
            Optional.empty()
        }
    }

    override fun configuration(): PodConfigOverride {
        val json = """
            {
              "auto-ingest-rdf": true,
              "default-context": { "ex": "http://example.org/" }
            }
        """.trimIndent()
        return PodConfigProvider.deserializePodConfigOverride(json)
    }

}

class TestGenerateClientConfig(
    private val clientId: String,
    private val clientSecret: String,
    private val rootAccess: Boolean
) : GenerateClientConfig {
    override fun clientId() = clientId

    override fun enableServiceAccount() = true

    override fun clientSecret() = Optional.of(clientSecret)

    override fun redirectUris(): Optional<List<String>> = Optional.empty()

    override fun enableForcePKCE() = false

    override fun openfga() = Optional.of(object : OpenFgaClientConfig {
        override fun relationships(): Optional<List<OpenFgaPermissionConfig>> {
            return if (rootAccess) {
                Optional.of(
                    listOf(
                        object : OpenFgaPermissionConfig {
                            override fun targetResource(): String = "/"
                            override fun relations(): List<String> = listOf("reader", "writer", "deleter")
                        }
                    ))
            } else {
                Optional.empty()
            }
        }

    } as OpenFgaClientConfig)
}