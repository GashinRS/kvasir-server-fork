package kvasir.services.changes.processors

import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import io.smallrye.reactive.messaging.kafka.KafkaRecordBatch
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.changes.ChangeProcessingHistoryEntry
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.changes.ChangeStatusCode
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.definitions.reactive.skipToLast
import kvasir.definitions.reactive.toUni
import kvasir.plugins.messaging.kafka.Channels
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import java.util.concurrent.CompletionStage
import kotlin.system.exitProcess

const val SEND_REPORT_PARALLELISM = 8

/**
 * Builds a failure [ProcessedChange] for a skipped change request (e.g. pod does not exist).
 * Sending this report back through the completion topic ensures the Kafka Streams ordering
 * pipeline does not stall on orphan pending entries.
 */
fun buildSkippedReport(request: ChangeRequest, reason: String): ProcessedChange {
    return ProcessedChange(
        id = request.changeId ?: request.id,
        createdBy = request.requestingUser,
        origRequestId = request.id,
        podId = request.podId,
        processingHistory = listOf(
            ChangeProcessingHistoryEntry(
                statusCode = ChangeStatusCode.INTERNAL_ERROR,
                message = reason
            )
        )
    )
}

fun sendSkippedReports(
    requests: List<ChangeRequest>,
    reason: String,
    emitter: MutinyEmitter<ProcessedChange>
): Uni<Void> {
    return Multi.createFrom().iterable(requests)
        .onItem().transformToUni { request ->
            val report = buildSkippedReport(request, reason)
            emitter.sendMessage(KafkaRecord.of(report.podId, report))
        }
        .merge(SEND_REPORT_PARALLELISM)
        .skipToLast()
}

@ApplicationScoped
class PlainChangeRequestProcessor(
    private val knowledgeGraph: KnowledgeGraph,
    @Channel(Channels.CHANGES_PROCESSING_COMPLETED_PUBLISH)
    private val completedChangeRequestsEmitter: MutinyEmitter<ProcessedChange>,
    @ConfigProperty(name = "kvasir-ext.change-processor.ignore-non-existing-pod", defaultValue = "false")
    private val ignoreNonExistingPod: Boolean,
    @ConfigProperty(name = "kvasir-ext.change-processor.shutdown-on-error", defaultValue = "true")
    private val shutdownOnError: Boolean
) {

    @Incoming(Channels.CHANGES_PLAIN_PROCESSING_QUEUE_SUBSCRIBE)
    fun process(records: KafkaRecordBatch<String, ChangeRequest>): CompletionStage<Void> {
        val requestsGroupedByPod = records.payload.groupBy { it.podId }.toList()
        return Multi.createFrom().iterable(requestsGroupedByPod)
            .onItem().transformToUniAndConcatenate { (_, requests) ->
                knowledgeGraph.processPlain(requests)
                    .chain { reports ->
                        Multi.createFrom().iterable(reports)
                            .onItem().transformToUni { report ->
                                completedChangeRequestsEmitter.sendMessage(KafkaRecord.of(report.podId, report))
                            }
                            .merge(SEND_REPORT_PARALLELISM)
                            .skipToLast()
                    }
                    .onFailure { err -> ignoreNonExistingPod && err.message?.contains("does not exist") == true }
                    .recoverWithUni { err ->
                        Log.warn("Encountered change request for non-existing Pod, ignoring as configured: ${err.message}")
                        sendSkippedReports(requests, err.message ?: "Pod does not exist", completedChangeRequestsEmitter)
                    }

            }
            .skipToLast()
            .chain { _ -> records.ack().toUni() }
            .onFailure().invoke { err ->
                Log.error("Error in change processor${if (shutdownOnError) ", shutting down." else ""}", err)
                if (shutdownOnError) {
                    exitProcess(1)
                }
            }
            .convert().toCompletionStage()
    }

}

@ApplicationScoped
class StatefulChangeRequestProcessor(
    private val knowledgeGraph: KnowledgeGraph,
    @Channel(Channels.CHANGES_PROCESSING_COMPLETED_PUBLISH)
    private val completedChangeRequestsEmitter: MutinyEmitter<ProcessedChange>,
    @ConfigProperty(name = "kvasir-ext.change-processor.ignore-non-existing-pod", defaultValue = "false")
    private val ignoreNonExistingPod: Boolean,
    @ConfigProperty(name = "kvasir-ext.change-processor.shutdown-on-error", defaultValue = "true")
    private val shutdownOnError: Boolean
) {

    @Incoming(Channels.CHANGES_STATEFUL_PROCESSING_QUEUE_SUBSCRIBE)
    fun process(message: Message<ChangeRequest>): CompletionStage<Void> {
        return knowledgeGraph.processStateDependent(message.payload)
            .chain { report -> completedChangeRequestsEmitter.sendMessage(KafkaRecord.of(report.podId, report)) }
            .onFailure { err -> ignoreNonExistingPod && err.message?.contains("does not exist") == true }
            .recoverWithUni { err ->
                Log.warn("Encountered change request for non-existing Pod ('${message.payload.podId}'), ignoring as configured: ${err.message}")
                val report = buildSkippedReport(message.payload, err.message ?: "Pod does not exist")
                completedChangeRequestsEmitter.sendMessage(KafkaRecord.of(report.podId, report))
            }
            .chain { _ -> message.ack().toUni() }
            .onFailure().invoke { err ->
                Log.error("Error in change processor${if (shutdownOnError) ", shutting down." else ""}", err)
                if (shutdownOnError) {
                    exitProcess(1)
                }
            }
            .convert().toCompletionStage()
    }

}

@ApplicationScoped
class RefBasedChangeRequestProcessor(
    private val knowledgeGraph: KnowledgeGraph,
    @Channel(Channels.CHANGES_PROCESSING_COMPLETED_PUBLISH)
    private val completedChangeRequestsEmitter: MutinyEmitter<ProcessedChange>,
    @ConfigProperty(name = "kvasir-ext.change-processor.ignore-non-existing-pod", defaultValue = "false")
    private val ignoreNonExistingPod: Boolean,
    @ConfigProperty(name = "kvasir-ext.change-processor.shutdown-on-error", defaultValue = "true")
    private val shutdownOnError: Boolean
) {
    @Incoming(Channels.CHANGES_REF_PROCESSING_QUEUE_SUBSCRIBE)
    fun process(message: Message<ChangeRequest>): CompletionStage<Void> {
        return knowledgeGraph.processReferenced(message.payload)
            .chain { report -> completedChangeRequestsEmitter.sendMessage(KafkaRecord.of(report.podId, report)) }
            .onFailure { err -> ignoreNonExistingPod && err.message?.contains("does not exist") == true }
            .recoverWithUni { err ->
                Log.warn("Encountered change request for non-existing Pod ('${message.payload.podId}'), ignoring as configured: ${err.message}")
                val report = buildSkippedReport(message.payload, err.message ?: "Pod does not exist")
                completedChangeRequestsEmitter.sendMessage(KafkaRecord.of(report.podId, report))
            }
            .chain { _ -> message.ack().toUni() }
            .onFailure().invoke { err ->
                Log.error("Error in change processor${if (shutdownOnError) ", shutting down." else ""}", err)
                if (shutdownOnError) {
                    exitProcess(1)
                }
            }
            .convert().toCompletionStage()
    }
}