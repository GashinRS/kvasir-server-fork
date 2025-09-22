package kvasir.plugins.policyagent.openfga.extractors

import idlab.quarkus.ext.pep.openfga.model.extractors.CommonExtractParams
import idlab.quarkus.ext.pep.openfga.model.extractors.subject.AnonymousPrincipalSubjectExtractor
import io.smallrye.mutiny.Uni
import kvasir.plugins.policyagent.openfga.utils.contextualizeSubject
import java.security.Principal

/**
 * Built upon the [AnonymousPrincipalSubjectExtractor], but encodes the subject so email addresses or URIs
 * can be used as subjects in OpenFGA.
 */
class DefaultSubjectExtractor : AnonymousPrincipalSubjectExtractor() {

    override fun extract(params: CommonExtractParams): Uni<String> {
        return super.extract(params).map { subjectTuple ->
            val (userType, userId) = subjectTuple.split(":", limit = 2)
            "${userType}:${contextualizeSubject(userId)}"
        }
    }

}