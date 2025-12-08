package kvasir.services.api.solid.impl

import io.vertx.ext.web.RoutingContext
import jakarta.inject.Singleton
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.config.HttpConfig
import kvasir.services.api.solid.SolidResourceRequestContext
import kvasir.services.api.solid.SolidResourceRequestContextProvider
import java.net.URI

@Singleton
class S3SolidResourceRequestContextProvider(
    private val httpConfig: HttpConfig
) : SolidResourceRequestContextProvider {
    override fun getContext(ctx: RoutingContext): SolidResourceRequestContext {
        return object : SolidResourceRequestContext {
            override fun isRoot() = ctx.request().path() == "/${ctx.pathParam("podId")}/solid/"

            override fun isContainerPath() = ctx.request().path().removeSuffix(METADATA_RESOURCE_SUFFIX).endsWith("/")

            override fun isMetadataRequest() = this.getResourceUri().path.endsWith(METADATA_RESOURCE_SUFFIX)

            override fun getResourceUri() = URI.create("${getBaseUri()}${ctx.request().path()}")

            override fun getBaseUri() = httpConfig.baseUri().removeSuffix("/")

            override fun getPodId() = "${getBaseUri()}/${this.getPodName()}"

            override fun getPodName() = ctx.pathParam("podId")

            override fun getRoot() = URI.create("${getBaseUri()}/${this.getPodName()}/solid/")

            override fun getContentType() = ctx.request().getHeader(HttpHeaders.CONTENT_TYPE)

            override fun getContentLength() = ctx.request().getHeader(HttpHeaders.CONTENT_LENGTH)?.toLongOrNull() ?: -1

        }
    }
}