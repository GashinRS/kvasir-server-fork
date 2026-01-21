package kvasir.services.api.kg.streams

import idlab.quarkus.ext.pep.openfga.model.annotations.OpenFgaPolicyEnforcer
import io.smallrye.mutiny.Multi
import io.vertx.core.json.Json
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.kafka.client.consumer.KafkaConsumer
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.ChangeReport
import kvasir.definitions.openapi.ApiDocTags
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.storage.StorageEvent
import kvasir.plugins.messaging.kafka.Channels
import kvasir.plugins.messaging.kafka.KafkaMessagingConfig
import kvasir.utils.http.KvasirUriInfo
import kvasir.utils.http.getParentUri
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponseSchema
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.resteasy.reactive.RestStreamElementType
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext
import java.time.Duration
import java.util.*

private const val STREAMING_BUFFER_SIZE = 500
private const val STREAMING_BUFFERING_MAX_DELAY_MS = 1000L
private const val RESUME_TOKEN_HTTP_HEADER_NAME = "X-Kvasir-Resume-Token"

@Path("")
class StreamApi(
    private val vertx: Vertx,
    private val knowledgeGraph: KnowledgeGraph,
    private val kafkaConfig: KafkaMessagingConfig,
    private val uriInfo: KvasirUriInfo,
    @Channel(Channels.QUERY_REQUESTS_SUBSCRIBE)
    private val queryRequestsSubscriber: Multi<QueryRequestEvent>,
    @Channel(Channels.LIFECYCLE_EVENTS_SUBSCRIBE)
    private val lifecycleEventsSubscriber: Multi<LifeCycleEvent>,
    @Channel(Channels.STORAGE_EVENTS_SUBSCRIBE)
    private val storageMutationSubscriber: Multi<StorageEvent>,
    private val requestContext: ServerRequestContext
) {

    @Path("{podId}/events/changes")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Tag(name = ApiDocTags.KG_EVENTS_API)
    @Operation(
        summary = "Stream changes made to the specified pod's Knowledge Graph.",
        description = "This endpoint allows clients to receive real-time updates about changes made to the Knowledge Graph of a specific pod. Only committed changes are streamed.",
    )
    @APIResponseSchema(value = ChangeRecords::class)
    @OpenFgaPolicyEnforcer
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
    ): Multi<JSONObject> {
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val streamId = resumeToken.orElse(UUID.randomUUID().toString())
        requestContext.serverResponse().setResponseHeader(RESUME_TOKEN_HTTP_HEADER_NAME, streamId)
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
            .group().intoLists().of(STREAMING_BUFFER_SIZE, Duration.ofMillis(STREAMING_BUFFERING_MAX_DELAY_MS))
            .map { buffer ->
                buffer.groupBy { it.changeRequestId }.map { (changeRequestId, records) ->
                    ChangeRecords(
                        KvasirVocab.context,
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
            .map { JsonLdHelper.encode(it, it.context) as JSONObject }
    }

    @Path("{podId}/events/query")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Tag(name = ApiDocTags.KG_EVENTS_API)
    @Operation(
        summary = "Stream query events for a specific pod.",
        description = "This endpoint allows clients to receive real-time updates about query requests made to the Knowledge Graph of a specific pod. E.g. can be used to generate an access log for auditing purposes.",
    )
    @APIResponseSchema(value = QueryRequestEvent::class)
    @OpenFgaPolicyEnforcer
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
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val streamId = resumeToken.orElse(UUID.randomUUID().toString())
        requestContext.serverResponse().setResponseHeader(RESUME_TOKEN_HTTP_HEADER_NAME, streamId)
        return streamFrom(
            Channels.QUERY_REQUESTS_TOPIC, QueryRequestEvent::class.java, "sse-consumer-$streamId",
            receiveBacklog.orElse(false),
            true
        )
            .filter { it.payload.podId == fqPodId }
            .map { event -> JsonLdHelper.encode(event.payload, event.payload.context) as JSONObject }
    }

    @Path("{podId}/events/life-cycle")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Tag(name = ApiDocTags.KG_EVENTS_API)
    @Operation(
        summary = "Stream life-cycle events for a specific pod.",
        description = "This endpoint allows clients to receive real-time updates about life-cycle events of a specific pod. E.g. can be used to be notified when a new Slice is created.",
    )
    @APIResponseSchema(value = LifeCycleEvent::class)
    @OpenFgaPolicyEnforcer
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
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val streamId = resumeToken.orElse(UUID.randomUUID().toString())
        requestContext.serverResponse().setResponseHeader(RESUME_TOKEN_HTTP_HEADER_NAME, streamId)
        return streamFrom(
            Channels.LIFECYCLE_EVENTS_TOPIC, LifeCycleEvent::class.java, "sse-consumer-$streamId",
            receiveBacklog.orElse(false),
            true
        )
            .filter { it.payload.podId == fqPodId }
            .map { event -> JsonLdHelper.encode(event.payload, event.payload.context) as JSONObject }
    }

    @Path("{podId}/events/s3")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Tag(name = ApiDocTags.KG_EVENTS_API)
    @Operation(
        summary = "Stream S3 events for a specific Pod.",
        description = "This endpoint allows clients to receive real-time updates about S3 storage events (e.g. file uploads, deletions) for a specific Pod. E.g. this allows triggering a pipeline when a file with a specific extension is created.",
    )
    @APIResponseSchema(value = StorageEvent::class)
    @OpenFgaPolicyEnforcer
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
        val fqPodId = uriInfo.getResourceUri().getParentUri(2).toASCIIString()
        val streamId = resumeToken.orElse(UUID.randomUUID().toString())
        requestContext.serverResponse().setResponseHeader(RESUME_TOKEN_HTTP_HEADER_NAME, streamId)
        return streamFrom(
            Channels.STORAGE_EVENTS_TOPIC, StorageEvent::class.java, "sse-consumer-$streamId",
            receiveBacklog.orElse(false),
            true
        )
            .filter { it.payload.podId == fqPodId }
            .map { event -> JsonLdHelper.encode(event.payload, KvasirVocab.context) as JSONObject }
    }

    private fun <T> streamFrom(
        targetTopic: String,
        payloadType: Class<T>,
        consumerName: String,
        receiveBacklog: Boolean = false,
        enableAutoCommit: Boolean = true
    ): Multi<Message<T>> {
        val config = mutableMapOf(
            "bootstrap.servers" to kafkaConfig.bootstrapServers(),
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
