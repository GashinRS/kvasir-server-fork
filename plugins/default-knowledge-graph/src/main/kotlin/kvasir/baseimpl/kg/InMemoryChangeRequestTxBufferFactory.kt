package kvasir.baseimpl.kg

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.changes.ChangeRequestTxBuffer
import kvasir.definitions.kg.changes.ChangeRequestTxBufferFactory
import kvasir.definitions.kg.changes.ChangeRequestTxBufferStatistics
import kvasir.definitions.reactive.asMulti
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@ApplicationScoped
class InMemoryChangeRequestTxBufferFactory : ChangeRequestTxBufferFactory {
    override fun open(request: ChangeRequest): Uni<ChangeRequestTxBuffer> {
        val requestStartTs = Instant.now()
        return Uni.createFrom().item(object : ChangeRequestTxBuffer {

            private val insertRecords = HashSet<ChangeRecord>()
            private val deleteRecords = HashSet<ChangeRecord>()

            private val nrOfInserts = AtomicLong(0)
            private val nrOfDeletes = AtomicLong(0)
            override val request: ChangeRequest
                get() = request
            override val requestTimestamp: Instant
                get() = requestStartTs

            override fun stream(
                filterByType: ChangeRecordType?,
                filterBySubject: String?,
                filterByPredicate: String?,
                filterByGraph: String?
            ): Multi<ChangeRecord> {
                return when (filterByType) {
                    ChangeRecordType.INSERT -> insertRecords.asMulti()
                    ChangeRecordType.DELETE -> deleteRecords.asMulti()
                    else -> Multi.createBy().concatenating().streams(deleteRecords.asMulti(), insertRecords.asMulti())
                }.filter {
                    (filterBySubject == null || it.statement.subject == filterBySubject) &&
                            (filterByPredicate == null || it.statement.predicate == filterByPredicate) &&
                            (filterByGraph == null || it.statement.graph == filterByGraph)
                }
            }

            override fun add(records: List<ChangeRecord>): Uni<Void> {
                records.forEach { record ->
                    when (record.type) {
                        ChangeRecordType.INSERT -> insertRecords.add(record)
                        ChangeRecordType.DELETE -> deleteRecords.add(record)
                    }
                }
                return Uni.createFrom().voidItem()
            }

            override fun remove(records: List<ChangeRecord>, stored: Boolean): Uni<Void> {
                records.forEach { record ->
                    when (record.type) {
                        ChangeRecordType.INSERT -> insertRecords.remove(record)
                        ChangeRecordType.DELETE -> deleteRecords.remove(record)
                    }
                }
                if (stored) {
                    records.forEach {
                        when (it.type) {
                            ChangeRecordType.INSERT -> nrOfInserts.incrementAndGet()
                            ChangeRecordType.DELETE -> nrOfDeletes.incrementAndGet()
                        }
                    }
                }
                return Uni.createFrom().voidItem()
            }

            override fun statistics(): Uni<ChangeRequestTxBufferStatistics> {
                return Uni.createFrom()
                    .item(
                        ChangeRequestTxBufferStatistics(nrOfInserts.get(), nrOfDeletes.get())
                    )
            }

            override fun destroy(): Uni<Void> {
                this.insertRecords.clear()
                this.deleteRecords.clear()
                return Uni.createFrom().voidItem()
            }

        })
    }

}