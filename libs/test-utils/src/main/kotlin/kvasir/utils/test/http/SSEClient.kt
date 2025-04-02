package kvasir.utils.test.http

import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.subscription.MultiEmitter
import io.vertx.core.json.JsonObject
import kvasir.definitions.rdf.JsonLdHelper
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface SSEClientEventMapper<T> {

    fun convert(eventData: String): T

}

object BasicSSEClientEventMappers {

    val EVENTS_AS_STRING = object : SSEClientEventMapper<String> {
        override fun convert(eventData: String): String {
            return eventData
        }
    }

    val EVENTS_AS_JSON = object : SSEClientEventMapper<JsonObject> {
        override fun convert(eventData: String): JsonObject {
            return JsonObject(eventData)
        }
    }

}

class ParseEventsFromJsonLD<T>(private val eventType: Class<T>) : SSEClientEventMapper<T> {
    override fun convert(eventData: String): T {
        val json = JsonObject(eventData).map
        return JsonObject(JsonLdHelper.toCompactFQForm(json)).mapTo(eventType)
    }

}

class SSEClient<T>(
    private val sseEndpoint: String,
    private val eventMapper: SSEClientEventMapper<T>,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) :
    AutoCloseable {

    private lateinit var connection: HttpURLConnection
    private var responseHeaders: Map<String, List<String>>? = null

    fun getResumeToken(): String {
        return responseHeaders?.let { responseHeaders!!["X-Kvasir-Resume-Token"]?.first() }
            ?: throw IllegalStateException("The SSE stream must be opened before getting the resume token!")
    }

    fun openStream(): Multi<T> {
        return Multi.createFrom().emitter { emitter: MultiEmitter<in T> ->
            val executor = Executors.newSingleThreadExecutor()
            executor.submit {
                try {
                    val url = URI.create(sseEndpoint).toURL()
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "text/event-stream")
                    responseHeaders = connection.headerFields
                    connection.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        var count = 0
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.startsWith("data:")) {
                                val data = line!!.removePrefix("data:")
                                emitter.emit(eventMapper.convert(data))
                            }
                        }
                        emitter.complete()
                    }
                } catch (e: Exception) {
                    emitter.fail(e)
                }
            }
        }
    }

    override fun close() {
        executor.shutdown()
    }

}