package kvasir.definitions.kg.changes

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.smallrye.mutiny.Uni
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.kg.ChangeStatusCode
import kvasir.definitions.kg.PagedResult
import kvasir.definitions.persistence.PersistentEntity
import kvasir.definitions.persistence.Repository
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import java.time.Instant

interface ChangeHistoryFactory {
    fun getChangeHistory(podId: String): ChangeHistory
}

/**
 * Interface defining a service for interacting with the KG ChangeHistory
 */
interface ChangeHistory : Repository<ChangeReport> {

    /**
     * Retrieve an overview of Changes matching the specified request.
     */
    @Deprecated("Use find with appropriate filter instead")
    fun list(request: ChangeHistoryRequest): Uni<PagedResult<ChangeReport>> {
        val filter = listOfNotNull(
            request.sliceId?.let { "sliceId==\"$it\"" },
            request.changeRequestId?.let { "id==\"$it\"" },
            request.fromTimestamp?.let { "writeTs >= \"$it\"" },
            request.toTimestamp?.let { "writeTs < \"$it\"" }
        ).takeIf { it.isNotEmpty() }?.joinToString(" and ")
        return find(filter, request.pageSize, request.cursor)
    }

    /**
     * Get detailed information for a specific Change matching the specified request.
     */
    @Deprecated("Use findById with instead")
    fun get(request: ChangeHistoryRequest): Uni<ChangeReport?> {
        return list(request.copy(pageSize = 1)).map { results ->
            results.items.firstOrNull()
        }
    }

}

/**
 * Data class encapsulating a Change History request.
 */
data class ChangeHistoryRequest(
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

@GenerateNoArgConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class ChangeReport(
    @get:JsonProperty(JsonLdKeywords.id)
    override var id: String,
    @get:JsonProperty(KvasirVocab.requestingUser)
    var requestingUser: String,
    @get:JsonProperty(KvasirVocab.podId)
    var podId: String,
    @get:JsonProperty(KvasirVocab.statusEntry)
    var statusEntry: List<ChangeReportStatusEntry>,
    @get:JsonProperty(KvasirVocab.sliceId)
    var sliceId: String? = null,
    @get:JsonProperty(KvasirVocab.nrOfInserts)
    var nrOfInserts: Long = 0,
    @get:JsonProperty(KvasirVocab.nrOfDeletes)
    var nrOfDeletes: Long = 0,
    @get:JsonProperty(KvasirVocab.message)
    var errorMessage: String? = null
) : PersistentEntity()

@GenerateNoArgConstructor
data class ChangeReportStatusEntry(
    @get:JsonProperty(KvasirVocab.timestamp) var timestamp: Instant = Instant.now(),
    @get:JsonProperty(KvasirVocab.statusCode) var code: ChangeStatusCode,
    @get:JsonProperty(KvasirVocab.message) var message: String? = null
)