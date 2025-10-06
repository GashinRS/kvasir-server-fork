package kvasir.utils.test.commons

import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.kg.ChangeStatusCode
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.changes.ChangeHistory
import kvasir.definitions.kg.changes.ChangeHistoryRequest
import kvasir.definitions.rdf.RDFMediaTypes
import org.eclipse.microprofile.config.inject.ConfigProperty
import io.restassured.RestAssured.given
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.config.GenerateClientConfig
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.config.PodConfig
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.changes.ChangeHistoryFactory
import kvasir.definitions.rdf.JSONObject
import java.time.Duration
import java.util.Optional
import kotlin.math.roundToLong

private val TERMINAL_STATES = setOf(
    ChangeStatusCode.NO_MATCHES,
    ChangeStatusCode.TOO_MANY_MATCHES,
    ChangeStatusCode.ASSERTION_FAILED,
    ChangeStatusCode.INTERNAL_ERROR,
    ChangeStatusCode.VALIDATION_ERROR,
    ChangeStatusCode.COMMITTED
)

@ApplicationScoped
class TestHelpers(
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = "http://localhost:8081/")
    val baseUri: String,
    val changeHistoryFactory: Instance<ChangeHistoryFactory>,
    val kg: Instance<KnowledgeGraph>
) {

    fun getPodUri(podId: String): String {
        return "${baseUri.removeSuffix("/")}/$podId"
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
        waitForChangeRequest(changeRequestUri, podUri, expectedResult, sliceUri).await().indefinitely()
        return changeRequestUri
    }

    fun requestChangeSync(
        changeRequest: ChangeRequest,
        expectedResult: ChangeStatusCode = ChangeStatusCode.COMMITTED
    ) {
        kg.get().process(changeRequest).chain { _ ->
            waitForChangeRequest(changeRequest.id, changeRequest.podId, expectedResult)
        }.await().indefinitely()
    }

    fun waitForChangeRequest(
        changeRequestUri: String,
        podUri: String,
        expectedResult: ChangeStatusCode = ChangeStatusCode.COMMITTED,
        sliceUri: String? = null,
        retryInitialDelay: Duration = Duration.ofMillis(200),
        delayFactor: Double = 1.2
    ): Uni<Void> {
        val changeHistory = changeHistoryFactory.get().getChangeHistory(podUri)
        return changeHistory
            .get(ChangeHistoryRequest(sliceId = sliceUri, changeRequestId = changeRequestUri))
            .chain { report ->
                if (report != null) {
                    val completedStatus =
                        report.statusEntry.filter { it.code.terminalState }.map { it.code }.firstOrNull()
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
                        sliceUri,
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

class TestPodConfig(private val name: String, private val ownerUserId: String) : PodConfig {
    override fun name(): String {
        return name
    }

    override fun ownerUserId(): Optional<String> {
        return Optional.of(ownerUserId)
    }

    override fun generateClients(): Optional<List<GenerateClientConfig>> {
        return Optional.empty()
    }

    override fun configuration(): JSONObject {
        val json = """
            {
              "@context": {
                "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
              },
              "kss:autoIngestRDF": true,
              "kss:defaultContext": "{\"kss\":\"https://kvasir.discover.ilabt.imec.be/vocab#\",\"rdfs\":\"http://www.w3.org/2000/01/rdf-schema#\",\"xsd\":\"http://www.w3.org/2001/XMLSchema#\",\"schema\":\"http://schema.org/\",\"ex\":\"http://example.org/\",\"saref\":\"https://saref.etsi.org/core/\",\"hasMeasurement\":{\"@reverse\":\"https://saref.etsi.org/core/measurementMadeBy\"},\"children\":{\"@reverse\":\"http://example.org/parent\"}}"
            }
        """.trimIndent()
        return JsonUtils.fromString(json) as JSONObject
    }

}