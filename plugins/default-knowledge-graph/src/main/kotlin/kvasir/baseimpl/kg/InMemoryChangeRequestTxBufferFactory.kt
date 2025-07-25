package kvasir.baseimpl.kg

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.Vertx
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.changes.ChangeRequestTxBuffer
import kvasir.definitions.kg.changes.ChangeRequestTxBufferFactory
import kvasir.definitions.kg.changes.ChangeRequestTxBufferStatistics
import kvasir.definitions.reactive.asMulti
import java.util.concurrent.atomic.AtomicLong

@ApplicationScoped
class InMemoryChangeRequestTxBufferFactory(private val vertx: Vertx) : ChangeRequestTxBufferFactory {
    override fun open(request: ChangeRequest): Uni<ChangeRequestTxBuffer> {
        return Uni.createFrom().item(object : ChangeRequestTxBuffer {

            private val insertRecords = HashSet<ChangeRecord>()
            private val deleteRecords = HashSet<ChangeRecord>()

            private val nrOfInserts = AtomicLong(0)
            private val nrOfDeletes = AtomicLong(0)
            override val request: ChangeRequest
                get() = request

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

            override fun add(records: List<ChangeRecord>): Uni<Void> = vertx.executeBlocking {
                records.groupBy { it.type }.forEach { (type, records) ->
                    when (type) {
                        ChangeRecordType.INSERT -> insertRecords.addAll(records)
                        ChangeRecordType.DELETE -> deleteRecords.addAll(records)
                    }
                }
            }.replaceWithVoid()

            override fun remove(records: List<ChangeRecord>, stored: Boolean): Uni<Void> = vertx.executeBlocking {
                records.groupBy { it.type }.forEach { (type, records) ->
                    when (type) {
                        ChangeRecordType.INSERT -> {
                            insertRecords.removeAll(records)
                            if (stored) {
                                nrOfInserts.addAndGet(records.size.toLong())
                            }
                        }

                        ChangeRecordType.DELETE -> {
                            deleteRecords.removeAll(records)
                            if (stored) {
                                nrOfDeletes.addAndGet(records.size.toLong())
                            }
                        }
                    }
                }
            }.replaceWithVoid()

            override fun statistics(): Uni<ChangeRequestTxBufferStatistics> {
                return Uni.createFrom()
                    .item(
                        ChangeRequestTxBufferStatistics(nrOfInserts.get(), nrOfDeletes.get())
                    )
            }

            override fun destroy(stored: Boolean): Uni<Void> = vertx.executeBlocking {
                if (stored) {
                    nrOfInserts.addAndGet(this.insertRecords.size.toLong())
                    nrOfDeletes.addAndGet(this.deleteRecords.size.toLong())
                }
                this.insertRecords.clear()
                this.deleteRecords.clear()
            }.replaceWithVoid()

        })
    }

}