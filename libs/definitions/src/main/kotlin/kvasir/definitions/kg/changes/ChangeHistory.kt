package kvasir.definitions.kg.changes

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.persistence.PersistentEntity
import java.time.Instant

@GenerateNoArgConstructor
@Persistent(storageLevel = StorageLevel.PER_POD, collectionName = "changes")
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProcessedChange(
    /**
     * Change id (represents a KG state, if the change was successfully applied).
     */
    override var id: String,
    override var createdBy: String,
    /**
     * Allows matching the ProcessedChange to the original Change Request.
     */
    var origRequestId: String,
    var podId: String,
    var processingHistory: List<ChangeProcessingHistoryEntry>,
    var sliceId: String? = null,
    var sliceTag: String? = null,
    var nrOfInserts: Long = 0,
    var nrOfDeletes: Long = 0,
    var associatedReferences: List<AssociatedReference> = emptyList()
) : PersistentEntity() {

    fun getErrorMessage(): String? {
        if(processingHistory == null) {
            return null // This is needed for Jackson SerDes
        }
        return processingHistory
            .filter { it.statusCode.terminalState && it.statusCode != ChangeStatusCode.COMMITTED }
            .maxByOrNull { it.timestamp }
            ?.message
    }

    fun getStatusCode(): ChangeStatusCode? {
        if(processingHistory == null) {
            return null // This is needed for Jackson SerDes
        }
        return processingHistory.maxByOrNull { it.timestamp }?.statusCode
    }

}

data class PendingChangeRequest(
    val id: String,
    val timestamp: Instant,
    val requestingUser: String
) {
    val statusCode: ChangeStatusCode = ChangeStatusCode.QUEUED
}

@GenerateNoArgConstructor
data class ChangeProcessingHistoryEntry(
    var timestamp: Instant = Instant.now(),
    var statusCode: ChangeStatusCode,
    var message: String? = null
)

@GenerateNoArgConstructor
data class AssociatedReference(
    val reference: Reference,
    val changeType: ChangeRecordType
)

enum class ChangeStatusCode(val terminalState: Boolean = false) {
    /**
     * The Change Request was added to the processing queue
     */
    QUEUED,

    /**
     * The Change Request is being processed.
     */
    PROCESSING,

    /**
     * The Change Request was successfully applied.
     */
    COMMITTED(true),

    /**
     * The Change Request was not applied because one or more assertions failed.
     */
    ASSERTION_FAILED(true),

    /**
     * The Change Request was not applied because the with-clause did not return any results.
     */
    NO_MATCHES(true),

    /**
     * The Change Request was not applied because the with-clause returned too many results.
     */
    TOO_MANY_MATCHES(true),

    /**
     * The Change Request was not applied because of a validation error.
     */
    VALIDATION_ERROR(true),

    /**
     * The Change Request was not applied because of an internal error.
     */
    INTERNAL_ERROR(true)
}