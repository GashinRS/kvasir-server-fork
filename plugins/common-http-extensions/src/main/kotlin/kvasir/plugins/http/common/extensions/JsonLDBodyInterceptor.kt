package kvasir.plugins.http.common.extensions

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import jakarta.ws.rs.ext.*
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab

private const val MAIN_MEDIA_TYPE = "application"
private const val SUB_MEDIA_TYPE = "ld+json"

private val defaultContext = mapOf("kss" to KvasirVocab.baseUri)

@Provider
class JsonLDBodyInterceptor : WriterInterceptor, ReaderInterceptor {
    override fun aroundWriteTo(ctx: WriterInterceptorContext) {
        if (ctx.mediaType?.type == MAIN_MEDIA_TYPE && ctx.mediaType?.subtype == SUB_MEDIA_TYPE) {
            val content = ctx.entity
            ctx.entity = if (content is List<*>) {
                // if the list contains JSON-LD, return as is
                if (content.any {
                        it is Map<*, *> && (it.containsKey(JsonLdKeywords.context) || it.containsKey(
                            JsonLdKeywords.graph
                        ))
                    }) {
                    content
                } else {
                    // TODO: Optimize, prevent double serialization
                    mapOf(
                        JsonLdKeywords.context to defaultContext,
                        JsonLdKeywords.graph to (JsonUtils.fromString(Json.encode(ctx.entity)) as List<*>).map {
                            JsonLdProcessor.compact(it, defaultContext, JsonLdOptions()).minus(JsonLdKeywords.context)
                        }
                    )
                }
            } else {
                val jsonld = JsonObject.mapFrom(content).map
                JsonLdProcessor.compact(jsonld, jsonld[JsonLdKeywords.context] ?: defaultContext, JsonLdOptions())
            }
        }
        ctx.proceed()
    }

    override fun aroundReadFrom(ctx: ReaderInterceptorContext): Any {
        return if (ctx.mediaType?.type == MAIN_MEDIA_TYPE && ctx.mediaType?.subtype == SUB_MEDIA_TYPE) {
            val jsonLd = JsonUtils.fromInputStream(ctx.inputStream) as Map<String, Any>
            val context = jsonLd[JsonLdKeywords.context] as Map<String, Any>? ?: defaultContext
            val fqJsonLd = JsonLdHelper.toCompactFQForm(jsonLd)
            // TODO: Only add context if type has a field with @JsonProperty("@context")
            JsonObject(mapOf(JsonLdKeywords.context to context) + fqJsonLd).mapTo(ctx.type)
        } else {
            ctx.proceed()
        }
    }

}