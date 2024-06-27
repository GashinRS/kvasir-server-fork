package kvasir.services.api.kg.query.impl

import com.github.jsonldjava.core.JsonLdOptions
import com.github.jsonldjava.core.JsonLdProcessor
import com.github.jsonldjava.utils.JsonUtils
import io.vertx.core.json.JsonObject
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.ext.MessageBodyWriter
import jakarta.ws.rs.ext.Provider
import kvasir.definitions.rdf.KvasirVocab
import kvasir.services.api.kg.query.ContextualizedQueryResult
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Type

@Provider
class QueryResultWriter : MessageBodyWriter<ContextualizedQueryResult> {

    private val defaultContext = mapOf(
        "results" to KvasirVocab.results,
        "nextCursor" to KvasirVocab.nextCursor
    )

    override fun isWriteable(
        clazz: Class<*>,
        type: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType
    ): Boolean {
        return clazz == ContextualizedQueryResult::class.java
    }

    override fun writeTo(
        instance: ContextualizedQueryResult,
        clazz: Class<*>,
        type: Type,
        annotations: Array<out Annotation>,
        mediaType: MediaType,
        headers: MultivaluedMap<String, Any>,
        out: OutputStream
    ) {
        val context = instance.context.plus(defaultContext)
        val doc = JsonLdProcessor.expand(
            mapOf("@context" to defaultContext, "@type" to KvasirVocab.QueryResult).plus(
                JsonObject.mapFrom(instance.result).map
            )
        )
        val outputDoc = JsonLdProcessor.compact(doc, context, JsonLdOptions())
        OutputStreamWriter(out).use { writer ->
            JsonUtils.write(writer, outputDoc)
        }
    }
}