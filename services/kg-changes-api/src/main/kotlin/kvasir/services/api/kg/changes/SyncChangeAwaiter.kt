package kvasir.services.api.kg.changes

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.inject.Singleton
import kvasir.definitions.kg.changes.ProcessedChange
import kvasir.plugins.messaging.kafka.Channels
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

/**
 * Provides synchronous waiting for a [ProcessedChange] matching a given `origRequestId`.
 *
 * Subscribes to the broadcast [Channels.CHANGES_OUTGOING_SUBSCRIBE] channel and filters
 * events matching the requested change. The subscription is materialised eagerly (via
 * [CompletableFuture]) so callers must subscribe to the returned [Uni] *after* sending
 * the corresponding [kvasir.definitions.kg.changes.ChangeRequest] to Kafka to avoid
 * any risk of missing the result.
 *
 * Usage pattern (inside a JAX-RS handler or Uni-transforming lambda):
 * ```
 * // 1. Subscribe eagerly — BEFORE any async operation:
 * val syncFuture = syncChangeAwaiter.awaitChange(requestId).subscribeAsCompletionStage()
 * // 2. Emit to Kafka, then wait for the result:
 * return emitter.sendMessage(record)
 *     .chain { _ -> syncFuture.toUni() }
 *     .map { processedChange -> Response.ok(processedChange).build() }
 * ```
 */
@Singleton
class SyncChangeAwaiter(
    @param:Channel(Channels.CHANGES_OUTGOING_SUBSCRIBE)
    private val outgoingChanges: Multi<ProcessedChange>,
    @param:ConfigProperty(name = "kvasir-ext.changes.default-timeout-ms", defaultValue = "15000")
    private val defaultTimeoutMs: Long
) {
    /**
     * Returns a [Uni] that resolves with the first [ProcessedChange] whose
     * [ProcessedChange.origRequestId] matches [requestId].
     *
     * The caller **must** subscribe to (or call [Uni.subscribeAsCompletionStage] on) the
     * returned [Uni] *before* the corresponding [kvasir.definitions.kg.changes.ChangeRequest]
     * is published to Kafka, to ensure the subscription is active before the result can arrive.
     *
     * @param requestId  the [kvasir.definitions.kg.changes.ChangeRequest.id] to wait for
     * @param timeout    maximum time to wait before failing with [TimeoutException]
     */
    fun awaitChange(
        requestId: String,
        timeout: Duration? = null
    ): Uni<ProcessedChange> {
        val effectiveTimeout = timeout ?: Duration.ofMillis(defaultTimeoutMs)
        // Convert the filtered Multi to a Uni (resolves on the first matching item),
        // then apply the timeout.
        return outgoingChanges
            .filter { it.origRequestId == requestId }
            .toUni()
            .ifNoItem().after(effectiveTimeout).fail()
    }
}



