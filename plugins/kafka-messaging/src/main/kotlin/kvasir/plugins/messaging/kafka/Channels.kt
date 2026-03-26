package kvasir.plugins.messaging.kafka

object Channels {
    const val CHANGES_INCOMING_TOPIC = "changes.incoming"
    const val CHANGES_INCOMING_PUBLISH = "changes_incoming_publish"

    const val CHANGES_PLAIN_PROCESSING_QUEUE_TOPIC = "changes.plain-processing-queue"
    const val CHANGES_PLAIN_PROCESSING_QUEUE_SUBSCRIBE = "changes_plain_processing_queue_subscribe"

    const val CHANGES_STATEFUL_PROCESSING_QUEUE_TOPIC = "changes.stateful-processing-queue"
    const val CHANGES_STATEFUL_PROCESSING_QUEUE_SUBSCRIBE = "changes_stateful_processing_queue_subscribe"

    const val CHANGES_REF_PROCESSING_QUEUE_TOPIC = "changes.ref-processing-queue"
    const val CHANGES_REF_PROCESSING_QUEUE_SUBSCRIBE = "changes_ref_processing_queue_subscribe"

    const val CHANGES_PROCESSING_COMPLETED_TOPIC = "changes.processing-completed"
    const val CHANGES_PROCESSING_COMPLETED_PUBLISH = "changes_processing_completed_publish"

    const val CHANGES_OUTGOING_TOPIC = "changes.outgoing"
    const val CHANGES_OUTGOING_SUBSCRIBE = "changes_outgoing_subscribe"


    const val STORAGE_EVENTS_TOPIC = "storage.events"
    const val STORAGE_EVENTS_PUBLISH = "storage_events_publish"
    const val STORAGE_EVENTS_SUBSCRIBE = "storage_events_subscribe"

    const val QUERY_REQUESTS_TOPIC = "query.requests"
    const val QUERY_REQUESTS_PUBLISH = "query_requests_publish"

    const val LIFECYCLE_EVENTS_TOPIC = "lifecycle.events"
    const val LIFECYCLE_EVENTS_PUBLISH = "lifecycle_events_publish"
}