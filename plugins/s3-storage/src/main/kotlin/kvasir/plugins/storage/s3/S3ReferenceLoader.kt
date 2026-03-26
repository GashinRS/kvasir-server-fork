package kvasir.plugins.storage.s3

import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.ReferenceLoader
import kvasir.definitions.kg.changes.Reference
import kvasir.definitions.kg.changes.S3Reference
import kvasir.definitions.rdf.RDFStatement
import kvasir.utils.rdf.RDFTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient

@ApplicationScoped
class S3ReferenceLoader(private val s3Client: S3AsyncClient) : ReferenceLoader {

    override fun isSupported(reference: Reference): Boolean {
        return reference is S3Reference
    }

    override fun loadReference(podOrSliceId: String, reference: Reference): Multi<RDFStatement> {
        reference as S3Reference
        val bucketId = S3Utils.getBucket(podOrSliceId)
        val docBaseUri = "${podOrSliceId.removeSuffix("/")}/s3/${reference.key}"
        return s3Client.getObject(bucketId, reference.key, reference.versionId)
            .onItem()
            .transformToMulti { resp -> RDFTransformer.toStatements(resp.inputStream, resp.contentType, docBaseUri) }
    }
}