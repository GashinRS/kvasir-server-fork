package kvasir.services.api.kg.streams

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import io.smallrye.mutiny.Multi
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.kafka.client.consumer.KafkaConsumer
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.ChangeResult
import kvasir.definitions.messaging.Channels
import kvasir.definitions.rdf.JsonLdKeywords
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.resteasy.reactive.RestStreamElementType
import java.util.UUID

@Path("{podId}")
class StreamApi(
    private val vertx: Vertx,
    @ConfigProperty(
        name = "kafka.bootstrap.servers"
    )
    private val kafkaBootstrapServers: String
) {

    val mapper = jsonMapper {
        addModule(kotlinModule())
    }.setSerializationInclusion(JsonInclude.Include.NON_DEFAULT)

    @Path("stream")
    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun stream(@PathParam("podId") podId: String): Multi<Map<String, Any>> {
        val consumerId = UUID.randomUUID().toString()
        return streamFrom(Channels.OUTBOX_TOPIC, ChangeResult::class.java, consumerId)
            .filter { msg -> msg.payload.podId == podId }
            .map { msg ->
                val jsonld = mapper.convertValue(msg.payload, object : TypeReference<Map<String, Object>>() {})
                JsonLdProcessor.compact(jsonld, jsonld[JsonLdKeywords.context], JsonLdOptions())
            }
    }

    @Path("slices/{sliceId}/stream")
    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun streamSlice(@PathParam("podId") podId: String, @PathParam("sliceId") sliceId: String): Multi<Map<String, Any>> {
        val consumerId = UUID.randomUUID().toString()
        return streamFrom(
            Channels.outboxTopicForSlice(podId, sliceId),
            ChangeResult::class.java,
            consumerId
        )
            .filter { msg -> msg.payload.podId == podId && msg.payload.sliceId == sliceId }
            .map { msg ->
                val jsonld = JsonObject.mapFrom(msg.payload).map
                JsonLdProcessor.compact(jsonld, jsonld[JsonLdKeywords.context], JsonLdOptions())
            }
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