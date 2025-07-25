package kvasir.plugins.messaging.kafka

object Channels {
    const val CHANGE_REQUESTS_PUBLISH = "change_requests_publish"
    const val CHANGE_REQUESTS_SUBSCRIBE = "change_requests_subscribe"
    const val STORAGE_EVENTS_PUBLISH = "storage_events_publish"
    const val STORAGE_EVENTS_TOPIC = "storage.events"
    const val STORAGE_EVENTS_SUBSCRIBE = "storage_events_subscribe"
    const val OUTBOX_TOPIC = "outbox"
    const val OUTBOX_PUBLISH = "outbox_publish"
    const val OUTBOX_SUBSCRIBE = "outbox_subscribe"
    const val QUERY_REQUESTS_TOPIC = "query.requests"
    const val QUERY_REQUESTS_PUBLISH = "query_requests_publish"
    const val QUERY_REQUESTS_SUBSCRIBE = "query_requests_subscribe"
    const val LIFECYCLE_EVENTS_TOPIC = "lifecycle.events"
    const val LIFECYCLE_EVENTS_PUBLISH = "lifecycle_events_publish"
    const val LIFECYCLE_EVENTS_SUBSCRIBE = "lifecycle_events_subscribe"
}