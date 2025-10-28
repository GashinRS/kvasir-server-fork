package kvasir.plugins.http.common.extensions

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import io.quarkus.logging.Log
import io.vertx.core.json.JsonObject
import jakarta.inject.Inject
import jakarta.ws.rs.ext.*
import kvasir.definitions.rdf.FgaVocab
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.http.KvasirUriInfo

private const val MAIN_MEDIA_TYPE = "application"
private const val SUB_MEDIA_TYPE = "ld+json"

private val defaultContext = mapOf("kss" to KvasirVocab.baseUri, "kss-fga" to FgaVocab.baseUri)

@Provider
class JsonLDBodyInterceptor : WriterInterceptor, ReaderInterceptor {

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var uriInfo: KvasirUriInfo

    override fun aroundWriteTo(ctx: WriterInterceptorContext) {
        if( !isApplicable()) {
            ctx.proceed()
            return
        }
        val startTs = System.currentTimeMillis()
        try {
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
                        val entityList = mapper.convertValue(ctx.entity, object : TypeReference<List<JSONObject>>() {})
                        mapOf(
                            JsonLdKeywords.context to defaultContext,
                            JsonLdKeywords.graph to entityList.map {
                                JsonLdProcessor.compact(it, defaultContext, JsonLdOptions())
                                    .minus(JsonLdKeywords.context)
                            }
                        )
                    }
                } else {
                    val jsonld = JsonObject.mapFrom(content).map
                    JsonLdProcessor.compact(jsonld, jsonld[JsonLdKeywords.context] ?: defaultContext, JsonLdOptions())
                }
            }
            ctx.proceed()
        } finally {
            Log.debug("Serializing JSON-LD response body took ${System.currentTimeMillis() - startTs} ms")
        }
    }

    override fun aroundReadFrom(ctx: ReaderInterceptorContext): Any? {
        if( !isApplicable()) {
            return ctx.proceed()
        }
        val startTs = System.currentTimeMillis()
        try {
            return if (ctx.mediaType?.type == MAIN_MEDIA_TYPE && ctx.mediaType?.subtype == SUB_MEDIA_TYPE) {
                val jsonLd = JsonUtils.fromInputStream(ctx.inputStream) as Map<String, Any>
                val context = jsonLd[JsonLdKeywords.context] as Map<String, Any>? ?: defaultContext
                val fqJsonLd = JsonLdHelper.toCompactFQForm(jsonLd)
                // TODO: Only add context if type has a field with @JsonProperty("@context")
                JsonObject(mapOf(JsonLdKeywords.context to context) + fqJsonLd).mapTo(ctx.type)
            } else {
                ctx.proceed()
            }
        } finally {
            Log.debug("Parsing JSON-LD request body took ${System.currentTimeMillis() - startTs} ms")
        }
    }

    private fun isApplicable(): Boolean {
        val podPath = uriInfo.delegate.path.removePrefix("/").substringAfter("/")
        return !podPath.startsWith("s3")  && !podPath.startsWith("solid")
    }
}