package kvasir.definitions.messaging

object Channels {
    const val CHANGE_REQUESTS_PUBLISH = "change_requests_publish"
    const val STORAGE_MUTATIONS_PUBLISH = "storage_mutations_publish"
    const val CHANGE_REQUESTS_SUBSCRIBE = "change_requests_subscribe"
    const val STORAGE_MUTATIONS_SUBSCRIBE = "storage_mutations_subscribe"
    const val OUTBOX_TOPIC = "outbox"
    const val OUTBOX_PUBLISH = "outbox_publish"
    const val SLICE_OUTBOX_PUBLISH = "slice_outbox_publish"
    const val OUTBOX_SUBSCRIBE = "outbox_subscribe"
    const val SLICE_EVENT_SUBSCRIBE = "slice_events_subscribe"
    const val SLICE_EVENT_PUBLISH = "slice_events_publish"
    const val POD_EVENT_SUBSCRIBE = "pod_events_subscribe"
    const val POD_EVENT_PUBLISH = "pod_events_publish"

    fun outboxTopicForSlice(podId: String, sliceId: String) = "outbox.$sliceId"
}