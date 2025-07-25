package kvasir.plugins.policyagent.openfga.utils

import idlab.quarkus.ext.pep.openfga.runtime.util.Codec.Encoder.encObjectId
import idlab.quarkus.ext.pep.openfga.runtime.util.Codec.Encoder.encUserId
import io.quarkiverse.openfga.client.model.RelObject
import io.quarkiverse.openfga.client.model.RelTupleKey
import io.quarkiverse.openfga.client.model.RelTupleKeyed
import io.quarkiverse.openfga.client.model.RelUser
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import java.net.URLEncoder

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
    return fqSubject;
}

private fun isValidURI(uri: String): Boolean {
    try {
        SimpleValueFactory.getInstance().createIRI(uri)
        return true
    } catch (ex: Throwable) {
        return false
    }
}

internal fun getContextForParents(objectId: String): Collection<RelTupleKeyed> {
    val normalizedPath = objectId.removePrefix("/").removeSuffix("/")
    val pathParts = normalizedPath.split("/")
    return if (pathParts.isEmpty()) {
        emptyList()
    } else {
        val tuples = pathParts.fold(emptyList<Pair<String, String>>()) { acc, segment ->
            val prev = if (acc.isEmpty()) "" else acc.last().second
            acc + (prev to "$prev/$segment")
        }.map { el ->
            RelTupleKey.builder()
                .user(RelUser.of("resource", encUserId(el.first.ifBlank { "/" })))
                .relation("parent")
                .`object`(RelObject.of("resource", encObjectId(el.second)))
                .build()
        }
        // Special case needs another parent relation for the root object
        if (objectId.endsWith("/")) {
            tuples + RelTupleKey.builder()
                .user(RelUser.of("resource", encUserId(objectId.removeSuffix("/"))))
                .relation("parent")
                .`object`(RelObject.of("resource", encObjectId(objectId)))
                .build()
        } else {
            tuples
        }
    }
}