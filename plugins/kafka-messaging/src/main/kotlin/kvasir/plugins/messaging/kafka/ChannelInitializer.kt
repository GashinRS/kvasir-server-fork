package kvasir.plugins.messaging.kafka

import io.smallrye.mutiny.Multi
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.LifeCycleEvent
import kvasir.definitions.kg.QueryRequestEvent
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.storage.StorageEvent
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class ChannelInitializer(
    @Channel(Channels.CHANGES_INCOMING_PUBLISH)
    private val changeRequestEmitter: MutinyEmitter<ChangeRequest>,
    @Channel(Channels.CHANGES_PLAIN_PROCESSING_QUEUE_SUBSCRIBE)
    private val changeRequestPlainQueueSubscriber: Multi<Message<ChangeRequest>>,
    @Channel(Channels.CHANGES_STATEFUL_PROCESSING_QUEUE_SUBSCRIBE)
    private val changeRequestStatefulQueueSubscriber: Multi<Message<ChangeRequest>>,
    @Channel(Channels.CHANGES_REF_PROCESSING_QUEUE_SUBSCRIBE)
    private val changeRequestRefQueueSubscriber: Multi<Message<ChangeRequest>>,
    @Channel(Channels.CHANGES_PROCESSING_COMPLETED_PUBLISH)
    private val changeRequestProcessingCompletedEmitter: MutinyEmitter<ChangeRequest>,
    @Channel(Channels.CHANGES_OUTGOING_SUBSCRIBE)
    private val processedChangeSubscriber: Multi<Message<ProcessedChange>>,
    @Channel(Channels.STORAGE_EVENTS_PUBLISH)
    private val storageMutationEmitter: MutinyEmitter<StorageEvent>,
    @Channel(Channels.STORAGE_EVENTS_SUBSCRIBE)
    private val storageMutationSubscriber: Multi<Message<StorageEvent>>,
    @Channel(Channels.QUERY_REQUESTS_PUBLISH)
    private val queryRequestEmitter: MutinyEmitter<QueryRequestEvent>,
    @Channel(Channels.LIFECYCLE_EVENTS_PUBLISH)
    private val lifecycleEventEmitter: MutinyEmitter<LifeCycleEvent>
)