package kvasir.plugins.messaging.kafka

import io.smallrye.config.ConfigMapping

/**
 * Channel names for use with @Channel annotations.
 * These must remain as compile-time constants for annotation parameters.
 */
object Channels {
    const val CHANGES_INCOMING_PUBLISH = "changes_incoming_publish"

    const val CHANGES_PLAIN_PROCESSING_QUEUE_SUBSCRIBE = "changes_plain_processing_queue_subscribe"

    const val CHANGES_STATEFUL_PROCESSING_QUEUE_SUBSCRIBE = "changes_stateful_processing_queue_subscribe"

    const val CHANGES_REF_PROCESSING_QUEUE_SUBSCRIBE = "changes_ref_processing_queue_subscribe"

    const val CHANGES_PROCESSING_COMPLETED_PUBLISH = "changes_processing_completed_publish"

    const val CHANGES_OUTGOING_SUBSCRIBE = "changes_outgoing_subscribe"

    const val STORAGE_EVENTS_PUBLISH = "storage_events_publish"
    const val STORAGE_EVENTS_SUBSCRIBE = "storage_events_subscribe"

    const val QUERY_REQUESTS_PUBLISH = "query_requests_publish"

    const val LIFECYCLE_EVENTS_PUBLISH = "lifecycle_events_publish"
}

/**
 * Configuration mapping for Kafka topics.
 * Topic names are loaded from application.yaml under the prefix "kvasir.topics".
 * In test profile, topics are prefixed with a unique UUID to allow parallel test execution.
 */
@ConfigMapping(prefix = "kvasir.topics")
interface TopicsConfig {
    fun changesIncoming(): String

    fun changesPlainProcessingQueue(): String

    fun changesStatefulProcessingQueue(): String

    fun changesRefProcessingQueue(): String

    fun changesProcessingCompleted(): String

    fun changesOutgoing(): String

    fun storageEvents(): String

    fun queryRequests(): String

    fun lifecycleEvents(): String
}

