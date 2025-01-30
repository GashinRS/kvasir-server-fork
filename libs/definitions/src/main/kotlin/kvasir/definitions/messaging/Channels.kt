package kvasir.definitions.messaging

object Channels {
    const val CHANGE_REQUESTS_PUBLISH = "change_requests_publish"
    const val STORAGE_MUTATIONS_PUBLISH = "storage_mutations_publish"
    const val CHANGE_REQUESTS_SUBSCRIBE = "change_requests_subscribe"
    const val STORAGE_MUTATIONS_SUBSCRIBE = "storage_mutations_subscribe"
    const val OUTBOX_TOPIC = "outbox"
    const val OUTBOX_PUBLISH = "outbox_publish"
    const val OUTBOX_SUBSCRIBE = "outbox_subscribe"

    fun outboxTopicForSlice(podId: String, sliceId: String) = "outbox.$sliceId"
}