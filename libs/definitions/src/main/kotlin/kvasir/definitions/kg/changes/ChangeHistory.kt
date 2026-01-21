package kvasir.definitions.kg.changes

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import kvasir.definitions.annotations.Persistent
import kvasir.definitions.annotations.GenerateNoArgConstructor
import kvasir.definitions.annotations.StorageLevel
import kvasir.definitions.kg.ChangeStatusCode
import kvasir.definitions.persistence.PersistentEntity
import java.time.Instant

@GenerateNoArgConstructor
@Persistent(storageLevel = StorageLevel.PER_POD, collectionName = "change_reports")
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChangeReport(
    override var id: String,
    var requestingUser: String,
    var podId: String,
    var statusEntry: List<ChangeReportStatusEntry>,
    var sliceId: String? = null,
    var nrOfInserts: Long = 0,
    var nrOfDeletes: Long = 0,
    var errorMessage: String? = null
) : PersistentEntity()

@GenerateNoArgConstructor
data class ChangeReportStatusEntry(
    var timestamp: Instant = Instant.now(),
    var statusCode: ChangeStatusCode,
    var message: String? = null
)