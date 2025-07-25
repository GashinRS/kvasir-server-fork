package kvasir.plugins.policyagent.openfga.extractors

import idlab.quarkus.ext.pep.openfga.runtime.extractors.CommonExtractParams
import idlab.quarkus.ext.pep.openfga.runtime.extractors.relation.OpenFgaRelationExtractor
import io.smallrye.mutiny.Uni
import io.vertx.core.http.HttpMethod

class DefaultRelationExtractor : OpenFgaRelationExtractor {

    override fun extract(params: CommonExtractParams): Uni<String> {
        val method = HttpMethod.valueOf(params.ctx.method.uppercase())
        return Uni.createFrom().item(extractRelation(method))
    }

    fun extractRelation(method: HttpMethod): String {
        return when (method) {
            HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS -> "can_read"
            // Delete interpreted as a form of write. This makes sense in a system with time-travel.
            HttpMethod.PUT, HttpMethod.POST, HttpMethod.PATCH -> "can_write"
            HttpMethod.DELETE -> "can_delete"
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }
    }
}