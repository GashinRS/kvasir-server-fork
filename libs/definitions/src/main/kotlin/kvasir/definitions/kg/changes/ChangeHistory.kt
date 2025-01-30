package kvasir.definitions.kg.changes

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.ChangeStatusCode
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant

/**
 * Interface defining a service for interacting with the KG ChangeHistory
 */
interface ChangeHistory {

    /**
     * Create (or replace) a Change Report.
     */
    fun register(report: ChangeReport): Uni<Void>

    /**
     * Retrieve an overview of Changes matching the specified request.
     */
    fun list(request: ChangeHistoryRequest): Uni<PagedResult<ChangeReport>>

    /**
     * Get detailed information for a specific Change matching the specified request.
     */
    fun get(request: ChangeHistoryRequest): Uni<ChangeReport?>

}

/**
 * Data class encapsulating a Change History request.
 */
data class ChangeHistoryRequest(
    // The id of the pod to fetch change history data from.
    val podId: String,
    // An optional slice identifier (retrieve changes limited to a specific slice)
    val sliceId: String? = null,
    // An optional from timestamp, in order to limit results to a specific time range.
    val fromTimestamp: Instant? = null,
    // An optional to timestamp, in order to limit results to a specific time range.
    val toTimestamp: Instant? = null,
    // Limit results to a specific change request.
    val changeRequestId: String? = null,
    // A cursor for pagination purposes.
    val cursor: String? = null,
    // Limit the size of the results.
    val pageSize: Int = 100
)

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class ChangeReport(
    @JsonProperty(JsonLdKeywords.id)
    val id: String,
    @JsonProperty(KvasirVocab.podId)
    val podId: String,
    @JsonProperty(KvasirVocab.statusEntry)
    val statusEntry: List<ChangeReportStatusEntry>,
    @JsonProperty(KvasirVocab.sliceId)
    val sliceId: String? = null,
    @JsonProperty(KvasirVocab.nrOfInserts)
    val nrOfInserts: Long = 0,
    @JsonProperty(KvasirVocab.nrOfDeletes)
    val nrOfDeletes: Long = 0,
    @JsonProperty(KvasirVocab.message)
    val errorMessage: String? = null
)

data class ChangeReportStatusEntry(
    @JsonProperty(KvasirVocab.timestamp) val timestamp: Instant,
    @JsonProperty(KvasirVocab.statusCode) val code: ChangeStatusCode,
    @JsonProperty(KvasirVocab.message) val message: String? = null
)