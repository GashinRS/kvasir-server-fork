package kvasir.services.api.kg.streams

import io.smallrye.mutiny.Multi
import io.vertx.core.json.Json
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.kafka.client.consumer.KafkaConsumer
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.*
import kvasir.definitions.messaging.Channels
import kvasir.definitions.openapi.ApiDocTags
import kvasir.utils.rdf.RDFTransformer
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.resteasy.reactive.RestStreamElementType
import org.eclipse.microprofile.openapi.annotations.tags.Tag
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
    @ConfigProperty(name = "kvasir.base-uri", defaultValue = "http://localhost:8080/")
    private val baseUri: String
) {

    @Path("{podId}/changes")
    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun stream(@PathParam("podId") podIdParam: String): Multi<ChangeRecords> {
        val podId = "$baseUri$podIdParam"
        val consumerId = UUID.randomUUID().toString()
        return streamFrom(Channels.OUTBOX_TOPIC, ChangeReport::class.java, consumerId)
            .filter { msg -> msg.payload.podId == podId }
            .onItem()
            .transformToMultiAndConcatenate { msg ->
                knowledgeGraph.streamChangeRecords(
                    ChangeHistoryRequest(
                        podId = msg.payload.podId,
                        changeRequestId = msg.payload.id
                    )
                )
            }
            .group().intoLists().of(bufferSize, Duration.ofMillis(bufferingMaxDelayMs))
            .map { buffer ->
                buffer.groupBy { it.changeRequestId }.map { (changeRequestId, records) ->
                    ChangeRecords(
                        mapOf("kss" to baseUri),
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

    @Path("{podId}/slices/{sliceId}/stream")
    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun streamSlice(@PathParam("podId") podId: String, @PathParam("sliceId") sliceId: String): Multi<Map<String, Any>> {
        TODO()
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