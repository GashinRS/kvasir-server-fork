package kvasir.services.changes.processor

import io.quarkus.logging.Log
import io.quarkus.runtime.Startup
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.plugins.messaging.kafka.Channels
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import kotlin.system.exitProcess

@ApplicationScoped
class ChangeProcessor(
    private val knowledgeGraph: KnowledgeGraph,
    @Channel(Channels.CHANGE_REQUESTS_SUBSCRIBE)
    private val changeRequestsSubscriber: Multi<Message<ChangeRequest>>,
    @ConfigProperty(name = "kvasir.change-processor.overflow.buffer-size", defaultValue = "100000")
    private val overflowBufferSize: Int,
    @ConfigProperty(name = "kvasir.change-processor.shutdown-on-error", defaultValue = "true")
    private val shutdownOnError: Boolean,
    @ConfigProperty(name = "kvasir.change-processor.ignore-non-existing-pod", defaultValue = "false")
    private val ignoreNonExistingPod: Boolean
) {

    @Startup
    fun start() {
        Log.info("Initializing KG change request storage processor...")
        changeRequestsSubscriber
            .onOverflow()
            .invoke { _ -> Log.warn("Change request processing is overflowing, trying to temporarily buffer...") }
            .buffer(overflowBufferSize)
            .onItem().transformToUniAndConcatenate { change ->
                knowledgeGraph.process(change.payload)
                    .onFailure { err -> ignoreNonExistingPod && err.message?.contains("does not exist") == true }
                    .recoverWithUni { err ->
                        Log.warn("Encountered change request for non-existing Pod ('${change.payload.podId}'), ignoring as configured: ${err.message}")
                        Uni.createFrom().voidItem()
                    }
                    .map { change }
                    .chain { _ ->
                        // Ack on success
                        Uni.createFrom().completionStage { change.ack() }
                    }
            }
            .onFailure().invoke { err ->
                Log.error("Error in change processor, shutting down.", err)
                if (shutdownOnError) {
                    exitProcess(1)
                }
            }
            .onSubscription().invoke { _ -> Log.debug("Subscribed to change request processing flow!") }
            .subscribe()
            .with { }
    }
}
