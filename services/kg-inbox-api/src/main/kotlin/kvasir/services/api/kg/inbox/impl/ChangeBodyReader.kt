package kvasir.services.api.kg.inbox.impl

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import io.vertx.core.json.JsonObject
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.ext.MessageBodyReader
import jakarta.ws.rs.ext.Provider
import kvasir.definitions.kg.changeops.Assertion
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.KvasirVocab
import kvasir.services.api.kg.inbox.ChangeRequestInput
import java.io.InputStream
import java.lang.reflect.Type
import java.util.*

@Provider
class ChangeBodyReader(
    private val uriInfo: UriInfo
) : MessageBodyReader<ChangeRequestInput> {

    private val defaultContext = mapOf(
        "graph" to KvasirVocab.graph,
        "inserts" to KvasirVocab.inserts,
        "deletes" to KvasirVocab.deletes,
        "assertions" to KvasirVocab.assertions
    )

    override fun isReadable(
        clazz: Class<*>,
        type: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType
    ): Boolean {
        return clazz == ChangeRequestInput::class.java
    }

    override fun readFrom(
        clazz: Class<ChangeRequestInput>,
        type: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType,
        headers: MultivaluedMap<String, String>,
        inputStream: InputStream
    ): ChangeRequestInput {
        val jsonLD = JsonUtils.fromInputStream(inputStream) as MutableMap<String, Any>
        val userProvidedContext = jsonLD["@context"] as? Map<String, Any> ?: emptyMap()
        if (!jsonLD.containsKey("@context")) {
            jsonLD["@context"] =
                defaultContext.plus("@vocab" to uriInfo.requestUri.toASCIIString().removeSuffix("inbox"))
        }
        val resolvedJsonLD = JsonLdProcessor.compact(
            JsonLdProcessor.expand(jsonLD),
            JsonUtils.fromString("{}"),
            JsonLdOptions()
        )
        return ChangeRequestInput(
            graph = resolvedJsonLD[KvasirVocab.graph] as? String ?: "",
            assertions = resolvedJsonLD[KvasirVocab.assertions]?.let { assertions ->
                JsonLdHelper.valueAsJsonArray(assertions).map { JsonObject(it).mapTo(Assertion::class.java) }
            }
                ?: emptyList(),
            operations = resolvedJsonLD[KvasirVocab.operations]?.let { operations ->
                JsonLdHelper.valueAsJsonArray(operations)
            }
                ?: emptyList(),
            inserts = resolvedJsonLD[KvasirVocab.inserts]?.let { inserts ->
                JsonLdHelper.valueAsJsonArray(inserts).map { assignIds(it) }
            }
                ?: emptyList(),
            deletes = resolvedJsonLD[KvasirVocab.deletes]?.let { deletes -> JsonLdHelper.valueAsJsonArray(deletes) }
                ?: emptyList(),
            userProvidedContext = userProvidedContext
        )
    }

    private fun assignIds(entity: Map<String, Any>): Map<String, Any> {
        val id = (entity["@id"] as? String) ?: uriInfo.requestUri.resolve("#${UUID.randomUUID()}").toString()
        return mapOf("@id" to id).plus(entity.entries.filterNot { (key, _) -> key == "@id" }.associate { (key, value) ->
            key to when (value) {
                is Map<*, *> -> assignIds(value as Map<String, Any>)
                is List<*> -> value.map { if (it is Map<*, *>) assignIds(it as Map<String, Any>) else it }
                else -> value
            }
        })
    }
}