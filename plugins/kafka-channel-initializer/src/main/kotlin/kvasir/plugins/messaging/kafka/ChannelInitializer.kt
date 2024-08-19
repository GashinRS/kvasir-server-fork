package kvasir.plugins.messaging.kafka

import io.smallrye.mutiny.Multi
import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRequest
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
    private val storageMutationSubscriber: Multi<Message<StorageMutationEvent>>
)