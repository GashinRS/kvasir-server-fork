package kvasir.services.api.kg.streams

import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeResult
import kvasir.definitions.messaging.Channels
import org.eclipse.microprofile.reactive.messaging.Incoming

@ApplicationScoped
class OutboxPrinter {

    @Incoming(Channels.OUTBOX_SUBSCRIBE)
    fun printOutboxEvent(changeResult: ChangeResult) {
        println(changeResult)
    }

}