package kvasir.definitions.rdf

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import kvasir.definitions.kg.Pod

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

    private val defaultContext = mapOf("kss" to KvasirVocab.baseUri, "kss-fga" to FgaVocab.baseUri)
    val mapper: ObjectMapper = ObjectMapper()

    init {
        mapper.registerModule(JavaTimeModule())
        mapper.registerModule(KotlinModule.Builder().configure(KotlinFeature.NullIsSameAsDefault, true).build())
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
        mapper.propertyNamingStrategy = PrefixedPropertyNamingStrategy(KvasirVocab.baseUri)
    }

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
            if (context.contains(JsonLdKeywords.vocab)) "${context[JsonLdKeywords.vocab]}$name" else name
        } else {
            val (prefix, localName) = name.split(separator, limit = 2)
            context[prefix]?.let { ns -> "$ns$localName" }
        }
    }

    fun encode(content: Any, context: JSONObject? = null): Any {
        return if (content is Iterable<*>) {
            // if the list contains JSON-LD, return as is
            if (content.any {
                    it is Map<*, *> && (it.containsKey(JsonLdKeywords.context) || it.containsKey(
                        JsonLdKeywords.graph
                    ))
                }) {
                content
            } else {
                val effectiveContext = (context ?: defaultContext)
                val entityList = mapper.convertValue(content, object : TypeReference<List<JSONObject>>() {})
                mapOf(
                    JsonLdKeywords.context to effectiveContext,
                    JsonLdKeywords.graph to entityList.map {
                        JsonLdProcessor.compact(it, effectiveContext, JsonLdOptions())
                            .minus(JsonLdKeywords.context)
                    }
                )
            }
        } else {
            val jsonLd = mapper.convertValue(content, JSONObject::class.java)
            val effectiveContext = when {
                jsonLd.containsKey(JsonLdKeywords.context) -> jsonLd[JsonLdKeywords.context]
                context != null -> if (!context.values.contains(KvasirVocab.baseUri)) context.plus(KvasirVocab.context) else context
                else -> defaultContext
            }
            return JsonLdProcessor.compact(
                jsonLd,
                effectiveContext,
                JsonLdOptions()
            )
        }
    }

    fun <T> decode(jsonLd: String, type: Class<T>): T {
        val jsonLd = JsonUtils.fromString(jsonLd) as Map<String, Any>
        val context = jsonLd[JsonLdKeywords.context] as Map<String, Any>? ?: defaultContext
        val fqJsonLd = toCompactFQForm(jsonLd)
        // Only include context if the target type has a context field
        val convertInput = if (type.declaredFields.any { it.name == "context" && it.type == JSONObject::class.java }) {
            mapOf(JsonLdKeywords.context to context) + fqJsonLd
        } else {
            fqJsonLd
        }
        return mapper.convertValue(convertInput, type)
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