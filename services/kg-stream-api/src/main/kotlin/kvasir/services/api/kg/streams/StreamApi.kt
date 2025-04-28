package kvasir.services.api.kg.streams

import io.smallrye.mutiny.Multi
import io.vertx.core.json.Json
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.kafka.client.consumer.KafkaConsumer
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.messaging.Channels
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.storage.StorageEvent
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getParentUri
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.resteasy.reactive.RestStreamElementType
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext
import java.time.Duration
import java.util.*

@Tag(name = ApiDocTags.KG_STREAMING_API)
@Path("")
class StreamApi(
    private val vertx: Vertx,
    private val knowledgeGraph: KnowledgeGraph,
    @ConfigProperty(
        name = "kafka.bootstrap.servers"
    )
    private val kafkaBootstrapServers: String,
    @ConfigProperty(
        name = "kvasir.streaming.buffer-size",
        defaultValue = "500"
    )
    private val bufferSize: Int,
    @ConfigProperty(
        name = "kvasir.streaming.buffering-max-delay-ms",
        defaultValue = "1000"
    )
    private val bufferingMaxDelayMs: Long,
    private val uriInfo: KvasirUriInfo,
    @Channel(Channels.QUERY_REQUESTS_SUBSCRIBE)
    private val queryRequestsSubscriber: Multi<QueryRequestEvent>,
    @Channel(Channels.LIFECYCLE_EVENTS_SUBSCRIBE)
    private val lifecycleEventsSubscriber: Multi<LifeCycleEvent>,
    @Channel(Channels.STORAGE_EVENTS_SUBSCRIBE)
    private val storageMutationSubscriber: Multi<StorageEvent>,
    private val requestContext: ServerRequestContext,
    @ConfigProperty(name = "kvasir.streaming.resume-token-http-header-name", defaultValue = "X-Kvasir-Resume-Token")
    private val resumeTokenHeaderName: String
) {

    @Path("{podId}/changes")
    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun stream(
        @PathParam("podId") podIdParam: String,
        @QueryParam("resumeToken") @Parameter(
            description = "The HTTP response of this operation includes a resume token as a HTTP Response header (`X-Kvasir-Resume-Token`). Use the value of this header to continue streaming from when the client was disconnected. This feature allows clients to handle temporary connection interruptions",
            required = false
        )
        resumeToken: Optional<String>,
        @QueryParam("receiveBacklog") @Parameter(
            description = "Whether or not the client should receive all data points currently in the Kvasir buffer (`true`), or start streaming data points that are being added from the moment the connection is established (`false`, default).",
            required = false
        )
        receiveBacklog: Optional<Boolean>, // Only has effect when a new session is created (i.e. no resume token)
    ): Multi<ChangeRecords> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        val streamId = resumeToken.orElse(UUID.randomUUID().toString())
        requestContext.serverResponse().setResponseHeader(resumeTokenHeaderName, streamId)
        return streamFrom(
            Channels.OUTBOX_TOPIC, ChangeReport::class.java, "sse-consumer-$streamId",
            receiveBacklog.orElse(false),
            true
        )
            .filter { msg -> msg.payload.podId == fqPodId }
            .onItem()
            .transformToMultiAndConcatenate { msg ->
                knowledgeGraph.streamChangeRecords(
                    ChangeRecordRequest(
                        podId = msg.payload.podId,
                        changeRequestId = msg.payload.id
                    )
                )
            }
            .group().intoLists().of(bufferSize, Duration.ofMillis(bufferingMaxDelayMs))
            .map { buffer ->
                buffer.groupBy { it.changeRequestId }.map { (changeRequestId, records) ->
                    ChangeRecords(
                        mapOf("kss" to KvasirVocab.baseUri),
                        changeRequestId,
                        records.first().timestamp,
                        records.filter { it.type == ChangeRecordType.DELETE }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) },
                        records.filter { it.type == ChangeRecordType.INSERT }
                            .map { it.statement }.takeIf { it.isNotEmpty() }
                            ?.let { RDFTransformer.statementsToJsonLD(it) }
                    )
                }
            }
            .onItem().disjoint<ChangeRecords>()
    }

    @Path("{podId}/query-events")
    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun streamQueryEvents(
        @PathParam("podId") podIdParam: String,
        @QueryParam("resumeToken") @Parameter(
            description = "The HTTP response of this operation includes a resume token as a HTTP Response header (`X-Kvasir-Resume-Token`). Use the value of this header to continue streaming from when the client was disconnected. This feature allows clients to handle temporary connection interruptions",
            required = false
        )
        resumeToken: Optional<String>,
        @QueryParam("receiveBacklog") @Parameter(
            description = "Whether or not the client should receive all event currently in the buffer (`true`), or start streaming events that are being added from the moment the connection is established (`false`, default).",
            required = false
        )
        receiveBacklog: Optional<Boolean>, // Only has effect when a new session is created (i.e. no resume token)
    ): Multi<JSONObject> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        val streamId = resumeToken.orElse(UUID.randomUUID().toString())
        requestContext.serverResponse().setResponseHeader(resumeTokenHeaderName, streamId)
        return streamFrom(
            Channels.QUERY_REQUESTS_TOPIC, QueryRequestEvent::class.java, "sse-consumer-$streamId",
            receiveBacklog.orElse(false),
            true
        )
            .filter { it.payload.podId == fqPodId }
            .map { event -> JsonLdHelper.encode(event.payload, event.payload.context) }
    }

    @Path("{podId}/life-cycle-events")
    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun streamLifeCycleEvents(
        @PathParam("podId") podIdParam: String,
        @QueryParam("resumeToken") @Parameter(
            description = "The HTTP response of this operation includes a resume token as a HTTP Response header (`X-Kvasir-Resume-Token`). Use the value of this header to continue streaming from when the client was disconnected. This feature allows clients to handle temporary connection interruptions",
            required = false
        )
        resumeToken: Optional<String>,
        @QueryParam("receiveBacklog") @Parameter(
            description = "Whether or not the client should receive all event currently in the buffer (`true`), or start streaming events that are being added from the moment the connection is established (`false`, default).",
            required = false
        )
        receiveBacklog: Optional<Boolean>, // Only has effect when a new session is created (i.e. no resume token)
    ): Multi<JSONObject> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        val streamId = resumeToken.orElse(UUID.randomUUID().toString())
        requestContext.serverResponse().setResponseHeader(resumeTokenHeaderName, streamId)
        return streamFrom(
            Channels.LIFECYCLE_EVENTS_TOPIC, LifeCycleEvent::class.java, "sse-consumer-$streamId",
            receiveBacklog.orElse(false),
            true
        )
            .filter { it.payload.podId == fqPodId }
            .map { event -> JsonLdHelper.encode(event.payload, event.payload.context) }
    }

    @Path("{podId}/s3-events")
    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun streamStorageMutationEvents(
        @PathParam("podId") podIdParam: String,
        @QueryParam("resumeToken") @Parameter(
            description = "The HTTP response of this operation includes a resume token as a HTTP Response header (`X-Kvasir-Resume-Token`). Use the value of this header to continue streaming from when the client was disconnected. This feature allows clients to handle temporary connection interruptions",
            required = false
        )
        resumeToken: Optional<String>,
        @QueryParam("receiveBacklog") @Parameter(
            description = "Whether or not the client should receive all event currently in the buffer (`true`), or start streaming events that are being added from the moment the connection is established (`false`, default).",
            required = false
        )
        receiveBacklog: Optional<Boolean>, // Only has effect when a new session is created (i.e. no resume token)
    ): Multi<JSONObject> {
        val fqPodId = uriInfo.getResourceUri().getParentUri().toASCIIString()
        val streamId = resumeToken.orElse(UUID.randomUUID().toString())
        requestContext.serverResponse().setResponseHeader(resumeTokenHeaderName, streamId)
        return streamFrom(
            Channels.STORAGE_EVENTS_TOPIC, StorageEvent::class.java, "sse-consumer-$streamId",
            receiveBacklog.orElse(false),
            true
        )
            .filter { it.payload.podId == fqPodId }
            .map { event -> JsonLdHelper.encode(event.payload, KvasirVocab.context) }
    }

    private fun <T> streamFrom(
        targetTopic: String,
        payloadType: Class<T>,
        consumerName: String,
        receiveBacklog: Boolean = false,
        enableAutoCommit: Boolean = true
    ): Multi<Message<T>> {
        val config = mutableMapOf(
            "bootstrap.servers" to kafkaBootstrapServers,
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "group.id" to consumerName,
            "auto.offset.reset" to if (receiveBacklog) "earliest" else "latest",
            "enable.auto.commit" to enableAutoCommit.toString()
        )
        val consumer = KafkaConsumer.create<String, String>(vertx, config)
        return consumer.subscribe(targetTopic)
            .onItem()
            .transformToMulti {
                consumer.toMulti().map { record ->
                    Message.of(Json.decodeValue(record.value(), payloadType))
                        .withAck { consumer.commit().subscribeAsCompletionStage() }
                }
            }
    }

}
