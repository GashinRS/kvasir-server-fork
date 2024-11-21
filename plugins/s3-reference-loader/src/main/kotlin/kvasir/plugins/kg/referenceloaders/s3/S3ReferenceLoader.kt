package kvasir.plugins.kg.referenceloaders.s3

import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.MinioAsyncClient
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.ReferenceLoader
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.s3.S3Utils
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.query.QueryResults
import org.eclipse.rdf4j.rio.RDFFormat
import kotlin.jvm.optionals.getOrNull

@ApplicationScoped
class S3ReferenceLoader(private val minioClient: MinioAsyncClient) : ReferenceLoader {

    override fun isSupported(reference: Map<String, Any>): Boolean {
        return reference[JsonLdKeywords.type] == KvasirVocab.S3Reference
    }

    override fun loadReference(podOrSliceId: String, reference: Map<String, Any>): Multi<RDFStatement> {
        val key = reference[KvasirVocab.key] as String
        val versionId = reference[KvasirVocab.versionId] as String
        val bucketId = S3Utils.getBucket(podOrSliceId)
        return Uni.createFrom()
            .future(
                minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucketId).`object`(key).versionId(versionId).build()
                )
            )
            .onItem().transformToMulti { resp ->
                // TODO: do we need to set a baseURI here?
                Multi.createFrom().iterable(QueryResults.parseGraphBackground(resp, null, parseLang(resp)))
            }
            .map { statement ->
                RDFStatement(
                    statement.subject.stringValue(),
                    statement.predicate.stringValue(),
                    if (statement.`object`.isLiteral) getCompatibleRawValue(statement.`object` as Literal) else statement.`object`.stringValue(),
                    statement.context?.stringValue() ?: "",
                    statement.`object`.takeIf { it.isLiteral }?.let { it as Literal }?.datatype?.stringValue(),
                    statement.`object`.takeIf { it.isLiteral }?.let { it as Literal }?.language?.getOrNull(),
                )
            }
    }

    private fun parseLang(resp: GetObjectResponse): RDFFormat {
        return when (val contentType = resp.headers()[HttpHeaders.CONTENT_TYPE]) {
            "text/turtle" -> RDFFormat.TURTLE
            "text/n3" -> RDFFormat.N3
            "application/n-triples" -> RDFFormat.NTRIPLES
            "application/ld+json" -> RDFFormat.JSONLD
            else -> throw IllegalArgumentException("Unsupported content type: $contentType")
        }
    }

    private fun getCompatibleRawValue(literal: Literal): Any {
        return when (literal.datatype.stringValue()) {
            XSDVocab.int, XSDVocab.integer -> literal.stringValue().let { it.toIntOrNull() ?: it.toLong() }
            XSDVocab.double, XSDVocab.decimal -> literal.doubleValue()
            XSDVocab.long -> literal.longValue()
            XSDVocab.boolean -> literal.booleanValue()
            else -> literal.stringValue()
        }
    }
}