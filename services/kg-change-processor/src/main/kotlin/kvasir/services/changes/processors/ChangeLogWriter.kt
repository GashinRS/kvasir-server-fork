package kvasir.services.changes.processors

import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.reactive.toUni
import kvasir.plugins.messaging.kafka.Channels
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import java.util.concurrent.CompletionStage
import kotlin.system.exitProcess

@ApplicationScoped
class ChangeLogWriter(
    private val repositoryFactory: RepositoryFactory,
    @ConfigProperty(name = "kvasir-ext.change-processor.ignore-non-existing-pod", defaultValue = "false")
    private val ignoreNonExistingPod: Boolean,
    @ConfigProperty(name = "kvasir-ext.change-processor.shutdown-on-error", defaultValue = "true")
    private val shutdownOnError: Boolean
) {

    @Incoming(Channels.CHANGES_OUTGOING_SUBSCRIBE)
    fun process(message: Message<ProcessedChange>): CompletionStage<Void> {
        val committedChange = message.payload
        return repositoryFactory.getRepository(ProcessedChange::class, committedChange.podId).persist(committedChange)
            .onFailure { err -> ignoreNonExistingPod && err.message?.contains("does not exist") == true }
            .recoverWithUni { err ->
                Log.warn("Encountered change for non-existing Pod ('${committedChange.podId}'), ignoring as configured: ${err.message}")
                Uni.createFrom().voidItem()
            }
            .chain { _ -> message.ack().toUni() }
            .onFailure().invoke { err ->
                Log.error("Error in change log writer${if (shutdownOnError) ", shutting down." else ""}", err)
                if (shutdownOnError) {
                    exitProcess(1)
                }
            }
            .convert().toCompletionStage()
    }

}