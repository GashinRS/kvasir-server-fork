package kvasir.plugins.policyagent.openfga.extractors

import idlab.quarkus.ext.pep.openfga.model.extractors.context.ContextExtractParams
import idlab.quarkus.ext.pep.openfga.model.extractors.context.OpenFgaContextExtractor
import io.smallrye.mutiny.Uni
import kvasir.plugins.policyagent.openfga.OpenFgaConstants

class DefaultContextExtractor : OpenFgaContextExtractor {
    override fun extract(params: ContextExtractParams): Uni<Map<String, Any?>> {
        return Uni.createFrom().item(extract())
    }

    fun extract(): Map<String, Any?> {
        return mapOf(OpenFgaConstants.CHECK_TYPE to OpenFgaConstants.OPENFGA_CHECK_TYPE)
    }
}