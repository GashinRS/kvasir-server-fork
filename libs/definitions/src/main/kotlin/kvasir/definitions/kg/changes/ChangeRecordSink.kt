package kvasir.definitions.kg.changes

import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.ChangeRecord

interface ChangeRecordSink {

    val preferredBatchSize: Int

    fun write(podId: String, changeRecords: Collection<ChangeRecord>): Uni<Void>

}