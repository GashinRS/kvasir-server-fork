package kvasir.services.changes.processor

import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.KnowledgeGraph
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

@ApplicationScoped
class ChangeProcessor(
    private val knowledgeGraph: KnowledgeGraph,
    @Channel("change_requests_subscribe")
    private val changeRequestsSubscriber: Multi<Message<ChangeRequest>>,
    @ConfigProperty(name = "kvasir.change-processor.commits.buffer-size", defaultValue = "100000")
    private val bufferSize: Int,
    @ConfigProperty(name = "kvasir.change-processor.commits.max-delay-ms", defaultValue = "1000")
    private val maxDelayMs: Long
) {

    @Startup
    fun start() {
        Log.info("Initializing KG change request storage processor...")
        changeRequestsSubscriber
            .onOverflow()
            .invoke { _ -> Log.warn("Change request processing is overflowing, trying to temporarily buffer...") }
            .buffer(bufferSize * 5)
            .onItem().transformToUniAndConcatenate { change -> knowledgeGraph.process(change.payload).map { change } }
            .group().intoLists().of(bufferSize, Duration.of(maxDelayMs, ChronoUnit.MILLIS))
            .onItem().transformToUniAndConcatenate { commitBatch ->
                // Acknowledge the last received message
                Uni.createFrom().completionStage { commitBatch.last().ack() }
            }
            .onFailure().invoke { err ->
                Log.error("Error in change processor, shutting down.", err)
                exitProcess(1)
            }
            .onSubscription().invoke { _ -> Log.debug("Subscribed to change request processing flow!") }
            .subscribe()
            .with { }
    }
}
