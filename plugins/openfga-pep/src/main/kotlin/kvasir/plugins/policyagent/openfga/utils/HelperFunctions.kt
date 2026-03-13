package kvasir.plugins.policyagent.openfga.utils

import dev.openfga.sdk.api.client.model.ClientTupleKey
import idlab.quarkus.ext.pep.openfga.model.util.Codec.Encoder.encObject
import idlab.quarkus.ext.pep.openfga.model.util.Codec.Encoder.encUser
import io.vertx.core.http.HttpServerResponse
import kvasir.definitions.config.JWTPrincipalExtractorConfig
import kvasir.definitions.config.JWTProviderConfig
import kvasir.definitions.config.PodConfig
import kvasir.plugins.policyagent.openfga.extractors.SimpleJWTPrincipalExtractor
import org.apache.http.HttpHeaders
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import org.jose4j.jwt.consumer.JwtContext
import java.util.*
import kotlin.jvm.optionals.getOrNull

private val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".toRegex()

internal fun contextualizeSubject(subject: String): String {
    val fqSubject = when {
        // The subject is an email address
        subject.matches(emailRegex) -> "mailto:$subject"
        // The subject is a valid URI
        isValidURI(subject) -> subject
        // The subject is not a valid URI, we assume it's a local identifier
        else -> "urn:kvasir-user:$subject"
    }
    // OpenFga-pep encodes internally
    return fqSubject
}

private fun isValidURI(uri: String): Boolean {
    try {
        SimpleValueFactory.getInstance().createIRI(uri)
        return true
    } catch (ex: Throwable) {
        return false
    }
}

internal fun getContextForParents(objectId: String): Collection<ClientTupleKey> {
    val normalizedPath = objectId.removePrefix("/").removeSuffix("/")
    val pathParts = normalizedPath.split("/")
    return if (pathParts.isEmpty()) {
        emptyList()
    } else {
        val tuples = pathParts.fold(emptyList<Pair<String, String>>()) { acc, segment ->
            val prev = if (acc.isEmpty()) "" else acc.last().second
            acc + (prev to "$prev/$segment")
        }.map { el ->
            ClientTupleKey()
                .user(encUser("resource:${el.first.ifBlank { "/" }}"))
                .relation("parent")
                ._object(encObject("resource:${el.second}"))
        }
        // Special case needs another parent relation for the root object
        if (objectId.endsWith("/")) {
            tuples + ClientTupleKey().user(encUser("resource:${objectId.removeSuffix("/")}"))
                .relation("parent")
                ._object(encObject("resource:$objectId"))
        } else {
            tuples
        }
    }
}

internal fun parseJWT(jwt: String): JwtContext {
    // No validation required here (not the responsibility of this function)
    // Parse the JWT in order to pass along the identity and other relevant information.
    val firstPassJwtConsumer = JwtConsumerBuilder()
        .setSkipAllValidators()
        .setDisableRequireSignature()
        .setSkipSignatureVerification()
        .build()

    val jwtContext = firstPassJwtConsumer.process(jwt)
    return jwtContext
}

internal fun getJWTProviderConfig(
    issuer: String,
    podConfig: PodConfig,
    builtInKeycloakIssuer: String
): JWTProviderConfig? {
    return listOfNotNull(
        podConfig.auth().oidc().getOrNull(),
        podConfig.auth().uma().getOrNull()
    ).find { it.serverUrl() == issuer } ?: run {
        if (issuer == builtInKeycloakIssuer) {
            // Construct OIDC config for built-in Keycloak
            object : JWTProviderConfig {
                override fun serverUrl() = builtInKeycloakIssuer

                override fun principalExtractor() = Optional.of(object : JWTPrincipalExtractorConfig {
                    override fun className() = SimpleJWTPrincipalExtractor::class.java.name

                    override fun config() = mapOf("attribute-name" to "preferred_username")

                } as JWTPrincipalExtractorConfig)

                override fun jwtAllowedClockSkewSeconds() = 30

            }
        } else {
            null
        }
    }
}

/**
 * Add another value to a possibly already existing Www-Authenticate header. This is required, because the fetch spec in
 * javascript cannot deal with multiple Www-Authenticate headers.
 */
internal fun HttpServerResponse.addWwwAuthenticateValue(headerValue: String): HttpServerResponse {
    val wwwAuth = this.headers().get(HttpHeaders.WWW_AUTHENTICATE);
    if (wwwAuth.isNullOrBlank()) {
        this.putHeader(HttpHeaders.WWW_AUTHENTICATE, headerValue)
    } else {
        this.putHeader(HttpHeaders.WWW_AUTHENTICATE, "$wwwAuth, $headerValue")
    }
    return this;
}