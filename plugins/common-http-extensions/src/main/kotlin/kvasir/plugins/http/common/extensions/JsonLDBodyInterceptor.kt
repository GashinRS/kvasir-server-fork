package kvasir.plugins.http.common.extensions

import io.quarkus.logging.Log
import jakarta.inject.Inject
import jakarta.ws.rs.ext.*
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.utils.http.KvasirUriInfo

private const val MAIN_MEDIA_TYPE = "application"
private const val SUB_MEDIA_TYPE = "ld+json"

@Provider
class JsonLDBodyInterceptor : WriterInterceptor, ReaderInterceptor {

    @Inject
    lateinit var uriInfo: KvasirUriInfo

    override fun aroundWriteTo(ctx: WriterInterceptorContext) {
        if (!isApplicable()) {
            ctx.proceed()
            return
        }
        val startTs = System.currentTimeMillis()
        try {
            if (ctx.mediaType?.type == MAIN_MEDIA_TYPE && ctx.mediaType?.subtype == SUB_MEDIA_TYPE) {
                val content = ctx.entity
                ctx.entity = JsonLdHelper.encode(content)
            }
            ctx.proceed()
        } finally {
            Log.debug("Serializing JSON-LD response body took ${System.currentTimeMillis() - startTs} ms")
        }
    }

    override fun aroundReadFrom(ctx: ReaderInterceptorContext): Any? {
        if (!isApplicable()) {
            return ctx.proceed()
        }
        val startTs = System.currentTimeMillis()
        try {
            return if (ctx.mediaType?.type == MAIN_MEDIA_TYPE && ctx.mediaType?.subtype == SUB_MEDIA_TYPE) {
                ctx.inputStream.use { inputStream ->
                    val jsonLdString = String(inputStream.readAllBytes())
                    JsonLdHelper.decode(jsonLdString, ctx.type)
                }
            } else {
                ctx.proceed()
            }
        } finally {
            Log.debug("Parsing JSON-LD request body took ${System.currentTimeMillis() - startTs} ms")
        }
    }

    private fun isApplicable(): Boolean {
        val podPath = uriInfo.delegate.path.removePrefix("/").substringAfter("/")
        return !podPath.startsWith("s3") && !podPath.startsWith("solid")
    }
}