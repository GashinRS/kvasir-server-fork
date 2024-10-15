package kvasir.plugins.messaging.kafka

import io.smallrye.mutiny.Multi
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.ChangeResult
import kvasir.definitions.kg.SliceEvent
import kvasir.definitions.messaging.Channels
import kvasir.definitions.storage.StorageMutationEvent
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message

@ApplicationScoped
class ChannelInitializer(
    @Channel(Channels.CHANGE_REQUESTS_PUBLISH)
    private val changeRequestEmitter: MutinyEmitter<ChangeRequest>,
    @Channel(Channels.STORAGE_MUTATIONS_PUBLISH)
    private val storageMutationEmitter: MutinyEmitter<StorageMutationEvent>,
    @Channel(Channels.CHANGE_REQUESTS_SUBSCRIBE)
    private val changeRequestSubscriber: Multi<Message<ChangeRequest>>,
    @Channel(Channels.STORAGE_MUTATIONS_SUBSCRIBE)
    private val storageMutationSubscriber: Multi<Message<StorageMutationEvent>>,
    @Channel(Channels.OUTBOX_PUBLISH)
    private val outboxEmitter: MutinyEmitter<ChangeResult>,
    @Channel(Channels.SLICE_OUTBOX_PUBLISH)
    val sliceOutboxEmitter: MutinyEmitter<ChangeResult>,
    @Channel(Channels.OUTBOX_SUBSCRIBE)
    private val outboxSubscriber: Multi<Message<ChangeResult>>,
    @Channel(Channels.SLICE_EVENT_PUBLISH)
    private val sliceEventEmitter: MutinyEmitter<SliceEvent>,
    @Channel(Channels.SLICE_EVENT_SUBSCRIBE)
    private val sliceEventSubscriber: Multi<Message<SliceEvent>>

)