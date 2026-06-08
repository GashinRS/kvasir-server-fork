package kvasir.services.changes.streamtopology

import com.github.f4b6a3.uuid.UuidCreator
import io.quarkus.kafka.client.serialization.ObjectMapperSerde
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.plugins.messaging.kafka.TopicsConfig
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.utils.Bytes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.*
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import org.apache.kafka.streams.state.ValueAndTimestamp
import java.time.Duration

const val PENDING_CHANGES_STORE = "pending-changes-store"
const val DEPENDENT_CHANGES_STORE = "dependent-changes-store"
const val LAST_PROCESSED_CHANGE_PER_POD_STORE = "last-processed-change-per-pod-store"

data class ChangeStatus(
    val ready: Boolean,
    val outgoingChange: ProcessedChange? = null
)

@ApplicationScoped
class ChangeTopologyProducer(
    private val topicsConfig: TopicsConfig
) {

    @Produces
    fun buildTopology(): Topology {
        val builder = StreamsBuilder()
        val incomingChangeSerde = ObjectMapperSerde(ChangeRequest::class.java)
        val incomingChangeProducer = Produced.with(Serdes.String(), incomingChangeSerde)
        val outgoingChangeSerde = ObjectMapperSerde(ProcessedChange::class.java)
        val changeStatusSerde = ObjectMapperSerde(ChangeStatus::class.java)

        /**
         * Define State Store for tracking pending changes.
         *
         * It is important that the keys are stored in natural order (i.e. lexicographical order of the change IDs),
         * as this allows us to emit completed changes in the correct order.
         *
         * The default persistent key-value store implementation in Kafka Streams uses a RocksDB instance,
         * which maintains keys in sorted order, so we can leverage that here.
         *
         * DO NOT configure a different store implementation (e.g. in-memory) without ensuring that the keys are still stored in natural order!
         */
        val pendingJobStoreBuilder = Stores.keyValueStoreBuilder(
            Stores.persistentKeyValueStore(PENDING_CHANGES_STORE),
            Serdes.String(),
            changeStatusSerde
        )
        builder.addStateStore(pendingJobStoreBuilder)

        /**
         * Define State Store for tracking change dependencies
         *
         * It is important that the keys are stored in natural order (i.e. lexicographical order of the change IDs),
         * as this allows us to quickly scan for dependent changes that can be released when a "later" change is completed.
         *
         * The default persistent key-value store implementation in Kafka Streams uses a RocksDB instance,
         * which maintains keys in sorted order, so we can leverage that here.
         *
         * DO NOT configure a different store implementation (e.g. in-memory) without ensuring that the keys are still stored in natural order!
         */
        val jobDependencyStoreBuilder = Stores.keyValueStoreBuilder(
            Stores.persistentKeyValueStore(DEPENDENT_CHANGES_STORE),
            Serdes.String(),
            incomingChangeSerde
        )
        builder.addStateStore(jobDependencyStoreBuilder)

        // Take incoming changes
        builder
            .stream(topicsConfig.changesIncoming(), Consumed.with(Serdes.String(), incomingChangeSerde))
            // Assign change IDs and register changes that have a state dependency
            .process(
                { PendingChangeProcessor() },
                Named.`as`("pending-changes-processor"),
                PENDING_CHANGES_STORE, DEPENDENT_CHANGES_STORE
            )
            .split()
            // Changes with referenced inserts/deletes (e.g. S3 file) are added to a separate queue
            .branch(
                { _, request -> request.insertFromRefs.isNotEmpty() || request.deleteFromRefs.isNotEmpty() },
                Branched.withConsumer {
                    it.to(topicsConfig.changesRefProcessingQueue(), incomingChangeProducer)
                }
            )
            // This is a special case: a state dependent change request was queued, but there is no previous state for the pod, so it is added to the stateful processing queue directly
            .branch(
                { _, request -> request.isStateDependent() },
                Branched.withConsumer {
                    it.to(
                        topicsConfig.changesStatefulProcessingQueue(),
                        incomingChangeProducer
                    )
                }
            )
            // Default case: change only contains plain inserts/deletes
            .defaultBranch(Branched.withConsumer {
                it.to(
                    topicsConfig.changesPlainProcessingQueue(),
                    incomingChangeProducer
                )
            })

        // Take changes for which processing is completed (both successful and failed requests)
        val orderedOutgoingJobs = builder
            .stream(topicsConfig.changesProcessingCompleted(), Consumed.with(Serdes.String(), outgoingChangeSerde))
            // Process completed changes, so outgoing order matches the incoming order
            .process(
                { EmitCompletedChangesProcessor() },
                Named.`as`("completed-changes-processor"),
                PENDING_CHANGES_STORE
            )

        // Publish to outgoing channel
        orderedOutgoingJobs.to(topicsConfig.changesOutgoing(), Produced.with(Serdes.String(), outgoingChangeSerde))

        orderedOutgoingJobs.mapValues { _, value -> value.id }
            .toTable(
                Materialized.`as`<String, String, KeyValueStore<Bytes, ByteArray>>(LAST_PROCESSED_CHANGE_PER_POD_STORE)
                    .withKeySerde(
                        Serdes.String()
                    ).withValueSerde(Serdes.String())
            )

        orderedOutgoingJobs
            // Look for matches in the pending state-dependent change requests
            .process(
                { EmitChangeCanStartProcessor() },
                Named.`as`("change-dependency-processor"),
                DEPENDENT_CHANGES_STORE,
                LAST_PROCESSED_CHANGE_PER_POD_STORE
            )
            // Emit to processing queue if a match is found
            .to(topicsConfig.changesStatefulProcessingQueue(), incomingChangeProducer)

        return builder.build()
    }

}

// TODO: implement an expiry mechanism to time-out pending changes that are not completed within a certain timeframe
//      This is important to avoid blocking the processing pipeline indefinitely in case of failures
//      The expired change, could be emitted to the outgoing channel with a failure status
/**
 * Note that although the processor uses a different key (changeId) than the incoming change stream (podId) when
 * interacting with the stores, scaling up the application still works correctly as the state stores are accessed by the
 * processor context and not by key-based partitioning.
 *
 * See: https://kafka.apache.org/26/streams/architecture/#:~:text=For%20each%20state%20store%2C%20it,own%20dedicated%20changelog%20topic%20partition.
 */
class PendingChangeProcessor : Processor<String, ChangeRequest, String, ChangeRequest> {

    private lateinit var context: ProcessorContext<String, ChangeRequest>
    private lateinit var pendingChangeStore: KeyValueStore<String, ChangeStatus>
    private lateinit var dependentChangesStore: KeyValueStore<String, ChangeRequest>

    override fun init(context: ProcessorContext<String, ChangeRequest>) {
        this.context = context
        this.pendingChangeStore = context.getStateStore(PENDING_CHANGES_STORE)
        this.dependentChangesStore = context.getStateStore(DEPENDENT_CHANGES_STORE)
    }

    override fun process(record: Record<String, ChangeRequest>) {
        // Generate UUID v7 as change id for the change request
        val changeId = UuidCreator.getTimeOrderedEpoch().toString()

        // Fetch previous change id for the pod from state store
        val prevChangeId = getPreviousChangeId()

        // Enrich the change request with both change id references
        val enrichedChangeRequest = record.value().copy(changeId = changeId, previousChangeId = prevChangeId)

        Log.debug("Registering new change request with id $changeId for pod ${record.key()} (previous change id: $prevChangeId)")
        // Add a reference to the changeId to the pending change store (i.e. register it as a pending change).
        pendingChangeStore.put(changeId, ChangeStatus(false))
        // If the change is state dependent and the system is aware of a previous state, register it in the dependent changes store
        if (record.value().isStateDependent() && prevChangeId != null) {
            // This queues the change to trigger only when its dependent change is committed
            Log.debug("Change request $changeId is state dependent on previous change $prevChangeId, queuing it for later processing.")
            dependentChangesStore.put(prevChangeId, enrichedChangeRequest)
        } else {
            // Forward the enriched change request downstream
            // Assign a new key (changeId) as we don't need to maintain per pod ordering for change processing (this maximizes potential parallelism)
            context.forward(record.withKey(changeId).withValue(enrichedChangeRequest))
        }
    }

    private fun getPreviousChangeId(): String? {
        return pendingChangeStore.reverseAll().use { iterator ->
            if (iterator.hasNext()) iterator.peekNextKey() else null
        }
    }

}

class EmitCompletedChangesProcessor : Processor<String, ProcessedChange, String, ProcessedChange> {

    private lateinit var context: ProcessorContext<String, ProcessedChange>
    private lateinit var pendingChangesStore: KeyValueStore<String, ChangeStatus>

    override fun init(context: ProcessorContext<String, ProcessedChange>) {
        this.context = context
        this.pendingChangesStore = context.getStateStore(PENDING_CHANGES_STORE)

        // Periodically emit first pending changes (by key natural order) that are completed
        context.schedule(Duration.ofSeconds(1), PunctuationType.WALL_CLOCK_TIME) { _ ->
            pendingChangesStore.all().use { iterator ->
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val changeId = entry.key
                    val changeStatus = entry.value
                    if (changeStatus.ready) {
                        // Emit completed change
                        context.forward(
                            Record(
                                changeStatus.outgoingChange!!.podId,
                                changeStatus.outgoingChange,
                                System.currentTimeMillis()
                            )
                        )
                        // Remove from pending store
                        pendingChangesStore.delete(changeId)
                    } else {
                        // Stop at the first pending job
                        break
                    }
                }
            }
        }

    }

    override fun process(record: Record<String, ProcessedChange>) {
        val changeId = record.value().id
        val pendingChange = pendingChangesStore.get(changeId)
        if (pendingChange != null && !pendingChange.ready) {
            // Update completion state
            pendingChangesStore.put(changeId, ChangeStatus(true, record.value()))
        }
    }
}

class EmitChangeCanStartProcessor : Processor<String, ProcessedChange, String, ChangeRequest> {

    private lateinit var context: ProcessorContext<String, ChangeRequest>
    private lateinit var dependentChangesStore: KeyValueStore<String, ChangeRequest>
    private lateinit var lastProcessedChangePerPodStore: KeyValueStore<String, ValueAndTimestamp<String>>

    override fun init(context: ProcessorContext<String, ChangeRequest>) {
        this.context = context
        this.dependentChangesStore = context.getStateStore(DEPENDENT_CHANGES_STORE)
        this.lastProcessedChangePerPodStore = context.getStateStore(LAST_PROCESSED_CHANGE_PER_POD_STORE)
        // Periodically check for dependent changes that can be released
        context.schedule(Duration.ofSeconds(1), PunctuationType.WALL_CLOCK_TIME) { _ ->
            // For the incoming processed change: release all queued changes that depend on a change older or equal to the committed change
            dependentChangesStore.all().use { iterator ->
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val dependantJob = entry.value
                    val lastProcessedChangeForPod: ValueAndTimestamp<String>? =
                        lastProcessedChangePerPodStore.get(dependantJob.podId)
                    if (lastProcessedChangeForPod != null && entry.value.previousChangeId!! <= lastProcessedChangeForPod.value()) {
                        Log.debug("State dependent change request ${dependantJob.id} can now be processed as its dependent change ${entry.key} has been committed.")
                        // Emit that the dependant change can start
                        context.forward(Record(dependantJob.changeId, dependantJob, System.currentTimeMillis()))
                        // Remove from dependent changes store
                        dependentChangesStore.delete(entry.key)
                    } else {
                        // Stop at the first encountered still-processing change
                        break
                    }
                }
            }
        }
    }

    override fun process(record: Record<String, ProcessedChange>) {
        val processedChangeId = record.value().id
        // Check if the dependent changes store has a change depending on this processed change
        val pendingChangeRequest = dependentChangesStore.delete(processedChangeId)
        if (pendingChangeRequest != null) {
            Log.debug("Received completed change $processedChangeId, releasing dependent change request ${pendingChangeRequest.id} for processing.")
            // If such a pending request was found, emit that the dependant change can start
            context.forward(Record(pendingChangeRequest.changeId, pendingChangeRequest, System.currentTimeMillis()))
        }
    }
}