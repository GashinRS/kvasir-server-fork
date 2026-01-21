package kvasir.utils.test.commons

import io.quarkus.logging.Log
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.config.*
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.ChangeStatusCode
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.RDFMediaTypes
import kvasir.utils.pod.PodConfigProvider
import java.time.Duration
import java.util.*
import kotlin.math.roundToLong

@ApplicationScoped
class TestHelpers(
    val httpConfig: HttpConfig,
    val repositoryFactory: RepositoryFactory,
    val kg: Instance<KnowledgeGraph>
) {

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
     * Returns the URI of the change request.
     */
    fun requestChangeViaHTTPSync(
        body: Any,
        podUri: String,
        expectedResult: ChangeStatusCode = ChangeStatusCode.COMMITTED,
        sliceUri: String? = null
    ): String {
        // Perform the request
        val requestUri = "${sliceUri ?: podUri}/changes"

        val changeRequestUri = given()
            .contentType(RDFMediaTypes.JSON_LD)
            .body(body)
            .post(requestUri)
            .then()
            .statusCode(201)
            .extract().header(HttpHeaders.LOCATION)

        // Wait for the request to be committed
        waitForChangeRequest(changeRequestUri, podUri, expectedResult).await().indefinitely()
        return changeRequestUri
    }

    fun waitForChangeRequest(
        changeRequestUri: String,
        podUri: String,
        expectedResult: ChangeStatusCode = ChangeStatusCode.COMMITTED,
        retryInitialDelay: Duration = Duration.ofMillis(200),
        delayFactor: Double = 1.2
    ): Uni<Void> {
        val changeHistory = repositoryFactory.getRepository(ChangeReport::class, podUri)
        return changeHistory
            .findById(changeRequestUri)
            .chain { report ->
                if (report != null) {
                    val completedStatus =
                        report.statusEntry.filter { it.statusCode.terminalState }.map { it.statusCode }.firstOrNull()
                    when {
                        completedStatus == expectedResult -> Uni.createFrom().voidItem()
                        completedStatus != null -> Uni.createFrom()
                            .failure(RuntimeException("Change request resulted in status '$completedStatus', expected '$expectedResult'"))

                        else -> Uni.createFrom().failure(StillProcessingException())
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
                        delayFactor
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

    override fun configuration(): PodConfig {
        val json = """
            {
              "auto-ingest-rdf": true,
              "default-context": "{\"kss\":\"https://kvasir.discover.ilabt.imec.be/vocab#\",\"rdfs\":\"http://www.w3.org/2000/01/rdf-schema#\",\"xsd\":\"http://www.w3.org/2001/XMLSchema#\",\"schema\":\"http://schema.org/\",\"ex\":\"http://example.org/\",\"saref\":\"https://saref.etsi.org/core/\",\"hasMeasurement\":{\"@reverse\":\"https://saref.etsi.org/core/measurementMadeBy\"},\"children\":{\"@reverse\":\"http://example.org/parent\"}}"
            }
        """.trimIndent()
        return PodConfigProvider.deserializePodConfig(json)
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