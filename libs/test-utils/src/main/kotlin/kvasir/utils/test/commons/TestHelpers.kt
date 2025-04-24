package kvasir.utils.test.commons

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
import kvasir.definitions.config.KvasirConfig
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.rdf.JSONObject
import java.time.Duration
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
    val changeHistory: Instance<ChangeHistory>,
    val kg: Instance<KnowledgeGraph>
) {

    fun getPodUri(podId: String = TestConstants.TEST_POD_1_ID): String {
        return "${baseUri.removeSuffix("/")}/$podId"
    }

    fun queryKGViaHTTP(
        q: Any,
        podUri: String = getPodUri(TestConstants.TEST_POD_1_ID),
        context: JSONObject? = null,
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
        podUri: String = getPodUri(TestConstants.TEST_POD_1_ID),
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
        podUri: String = getPodUri(TestConstants.TEST_POD_1_ID),
        expectedResult: ChangeStatusCode = ChangeStatusCode.COMMITTED,
        sliceUri: String? = null,
        retryInitialDelay: Duration = Duration.ofMillis(200),
        delayFactor: Double = 1.2
    ): Uni<Void> {
        return changeHistory.get()
            .get(ChangeHistoryRequest(podId = podUri, sliceId = sliceUri, changeRequestId = changeRequestUri))
            .chain { report ->
                if (report != null) {
                    val lastStatusEntry = report.statusEntry.sortedByDescending { it.timestamp }.first()
                    when (lastStatusEntry.code) {
                        expectedResult -> Uni.createFrom().voidItem()
                        in TERMINAL_STATES.minus(expectedResult) -> Uni.createFrom()
                            .failure(RuntimeException("Change request resulted in status '${lastStatusEntry.code}', expected '$expectedResult'"))

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