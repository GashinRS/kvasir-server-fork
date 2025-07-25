package kvasir.utils.http

import jakarta.enterprise.context.RequestScoped
import jakarta.ws.rs.core.UriBuilder
import jakarta.ws.rs.core.UriInfo
import kvasir.definitions.config.KvasirConfig
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI

@RequestScoped
class KvasirUriInfo(
    @ConfigProperty(name = KvasirConfig.BASE_URI_PROPERTY, defaultValue = KvasirConfig.BASE_URI_DEFAULT)
    private val baseUri: String,
    private val delegate: UriInfo
) {

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
        val builder = UriBuilder.fromUri(URI.create(getBaseUri()))
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
        return baseUri.removeSuffix("/")
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

fun URI.getChildUri(childId: String): URI {
    return URI.create("${this.toASCIIString().removeSuffix("/")}/${childId.removePrefix("/")}")
}