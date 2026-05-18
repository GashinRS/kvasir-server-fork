package kvasir.utils.http

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.config.HttpConfig
import java.net.URI

/**
 * Application-scoped holder for HttpConfig to avoid injection issues in request-scoped beans
 */
@ApplicationScoped
class HttpConfigHolder {

    @Inject
    lateinit var httpConfig: HttpConfig

    fun getBaseUri(): String = httpConfig.baseUri().removeSuffix("/")
}

@RequestScoped
class KvasirUriInfo {

    @Inject
    lateinit var delegate: UriInfo

    @Inject
    lateinit var configHolder: HttpConfigHolder

    /**
     * Get the resource URI (does not include query parameters). Used for id generation, etc.
     */
    fun getResourceUri(): URI {
        return URI.create("${getBaseUri()}${delegate.path}")
    }

    /**
     * Get the complete absolute URI.
     *
     * @param overrideQueryParams Override the specified query parameters, with the associated values.
     */
    fun getAbsoluteUri(vararg overrideQueryParams: Pair<String, String>): URI {
        val builder = UriBuilder.fromUri(getResourceUri())
        delegate.queryParameters.forEach { (name, values) -> builder.queryParam(name, *values.toTypedArray()) }
        overrideQueryParams.forEach { (queryParamName, queryParamValue) ->
            builder.replaceQueryParam(
                queryParamName,
                queryParamValue
            )
        }
        return builder.build()
    }

    fun getBaseUri(): String {
        return configHolder.getBaseUri()
    }
}

/**
 * Get a parent URI by going up the path segments, towards the specified level.
 * By default, the direct parent is returned (level 1).
 */
fun URI.getParentUri(level: Int = 1): URI {
    if (this.path.isEmpty()) {
        throw IllegalArgumentException("Cannot construct a parent URI for '${this.toASCIIString()}': already a root level!")
    }
    val parent = URI.create(this.toASCIIString().removeSuffix("/").substringBeforeLast("/"))
    return if (level > 1) parent.getParentUri(level - 1) else parent
}

fun URI.getChildUri(childId: String, separator: String = "/"): URI {
    return URI.create("${this.toASCIIString().removeSuffix("/")}$separator${childId.removePrefix("/")}")
}