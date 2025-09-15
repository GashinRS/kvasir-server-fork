package kvasir.plugins.messaging.kafka

import io.smallrye.mutiny.Multi
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.LifeCycleEvent
import kvasir.definitions.kg.QueryRequestEvent
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.storage.StorageEvent
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class ChannelInitializer(
    @Channel(Channels.CHANGE_REQUESTS_PUBLISH)
    private val changeRequestEmitter: MutinyEmitter<ChangeRequest>,
    @Channel(Channels.STORAGE_EVENTS_PUBLISH)
    private val storageMutationEmitter: MutinyEmitter<StorageEvent>,
    @Channel(Channels.CHANGE_REQUESTS_SUBSCRIBE)
    private val changeRequestSubscriber: Multi<Message<ChangeRequest>>,
    @Channel(Channels.STORAGE_EVENTS_SUBSCRIBE)
    private val storageMutationSubscriber: Multi<Message<StorageEvent>>,
    @Channel(Channels.OUTBOX_PUBLISH)
    private val outboxEmitter: MutinyEmitter<ChangeReport>,
    @Channel(Channels.OUTBOX_SUBSCRIBE)
    private val outboxSubscriber: Multi<Message<ChangeReport>>,
    @Channel(Channels.QUERY_REQUESTS_PUBLISH)
    private val queryRequestEmitter: MutinyEmitter<QueryRequestEvent>,
    @Channel(Channels.QUERY_REQUESTS_SUBSCRIBE)
    private val queryRequestSubscriber: Multi<Message<QueryRequestEvent>>,
    @Channel(Channels.LIFECYCLE_EVENTS_PUBLISH)
    private val lifecycleEventEmitter: MutinyEmitter<LifeCycleEvent>,
    @Channel(Channels.LIFECYCLE_EVENTS_SUBSCRIBE)
    private val lifecycleEventSubscriber: Multi<Message<LifeCycleEvent>>
)