package kvasir.plugins.messaging.kafka

import io.smallrye.reactive.messaging.MutinyEmitter
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.storage.StorageEvent
import org.eclipse.microprofile.reactive.messaging.Channel

/**
 * Utility Bean to provide a StorageEvent emitter.
 * Allows injection of the emitter in beans with an initialization phase.
 */
@ApplicationScoped
class StorageMutationEmitterProvider(
    @Channel(Channels.STORAGE_EVENTS_PUBLISH)
    private val storageMutationsEmitter: MutinyEmitter<StorageEvent>
) {
    fun getEmitter(): MutinyEmitter<StorageEvent> {
        return storageMutationsEmitter
    }
}