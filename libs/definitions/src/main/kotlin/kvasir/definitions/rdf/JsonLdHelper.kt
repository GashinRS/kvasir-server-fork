package kvasir.definitions.rdf

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject

typealias JSONObject = Map<String, Any>

const val JSON_LD_MEDIA_TYPE = "application/ld+json"

object JsonLdKeywords {
    const val context = "@context"
    const val id = "@id"
    const val type = "@type"
    const val graph = "@graph"
    const val reverse = "@reverse"
    const val language = "@language"
    const val vocab = "@vocab"
    const val value = "@value"
}

object JsonLdHelper {

    fun toCompactFQForm(doc: JSONObject, options: JsonLdOptions = JsonLdOptions()): JSONObject {
        return JsonLdProcessor.compact(JsonLdProcessor.expand(doc), emptyMap<String, Any>(), options)
    }

    fun compactUri(uri: String, context: JSONObject, separator: String = ":"): String {
        val compactedString = JsonLdProcessor.compact(mapOf(uri to uri), context, JsonLdOptions())
            .filter { it.key != "@context" }.keys.first()
        return if (compactedString == uri) {
            // Nothing to compact given the context
            uri
        } else {
            compactedString.split(":", limit = 2).takeIf { parts -> parts.size == 2 }?.let { (prefix, rest) ->
                "${prefix}${separator}${rest}"
            } ?: compactedString

        }
    }

    /**
     * Returns the fully qualified name of a prefixed name. Or null if the name is prefixed but the prefix is unknown.
     */
    fun getFQName(name: String, context: JSONObject, separator: String = ":"): String? {
        return if (!name.contains(separator)) {
            name
        } else {
            val (prefix, localName) = name.split(separator, limit = 2)
            context[prefix]?.let { ns -> "$ns$localName" }
        }
    }

    fun encode(any: Any, context: JSONObject): JSONObject {
        val effectiveContext = if (!context.values.contains(KvasirVocab.baseUri)) {
            context.plus(KvasirVocab.context)
        } else {
            context
        }
        return JsonLdProcessor.compact(JsonObject.mapFrom(any).map, effectiveContext, JsonLdOptions())
    }
}

fun <T> JSONObject.getJsonArray(key: String): List<T>? {
    return this[key]?.let { result ->
        when (result) {
            is List<*> -> result as List<T>
            is Iterable<*> -> (result as Iterable<T>).toList()
            else -> throw IllegalArgumentException("Cannot convert value for key '$key' to List, value is: '$result'")
        }
    }
}

fun JSONObject.getJsonObject(key: String): JSONObject? {
    return this[key]?.let { result ->
        if (result is Map<*, *>) {
            result as JSONObject
        } else {
            throw IllegalArgumentException("Cannot convert value for key '$key' to Map, value is: '$result'")
        }
    }
}