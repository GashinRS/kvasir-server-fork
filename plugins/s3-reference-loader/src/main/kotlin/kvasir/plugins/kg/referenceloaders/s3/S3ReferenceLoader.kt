package kvasir.plugins.kg.referenceloaders.s3

import io.smallrye.mutiny.Multi
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.ReferenceLoader
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.http.getChildUri
import kvasir.utils.rdf.RDFLiteralUtils
import kvasir.utils.rdf.RDFTransformer
import kvasir.utils.rdf.ReactiveRDFParser
import kvasir.utils.s3.S3Utils
import kvasir.utils.s3.getObject
import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.rio.RDFFormat
import software.amazon.awssdk.services.s3.S3AsyncClient
import java.net.URI
import java.util.*
import kotlin.jvm.optionals.getOrNull

@ApplicationScoped
class S3ReferenceLoader(private val s3Client: S3AsyncClient) : ReferenceLoader {

    override fun isSupported(reference: Map<String, Any>): Boolean {
        return reference[JsonLdKeywords.type] == KvasirVocab.S3Reference
    }

    override fun loadReference(podOrSliceId: String, reference: Map<String, Any>): Multi<RDFStatement> {
        val key = reference[KvasirVocab.key] as String
        val versionId = reference[KvasirVocab.versionId] as String?
        val bucketId = S3Utils.getBucket(podOrSliceId)
        val docBaseUri = "${podOrSliceId.removeSuffix("/")}/s3/$key#"
        val bNodeIdMap = mutableMapOf<BNode, String>()
        return s3Client.getObject(bucketId, key, versionId)
            .onItem().transformToMulti { resp ->
                ReactiveRDFParser.parseRdf(resp.inputStream, parseLang(resp.contentType), docBaseUri)
            }
            .map { statement ->
                RDFStatement(
                    processedNonLiteralValue(statement.subject, podOrSliceId, bNodeIdMap),
                    RDFTransformer.ensureValidAbsoluteIri(statement.predicate.stringValue()),
                    if (statement.`object`.isLiteral) getCompatibleRawValue(statement.`object` as Literal) else processedNonLiteralValue(
                        statement.`object`,
                        podOrSliceId,
                        bNodeIdMap
                    ),
                    statement.context?.let { processedNonLiteralValue(it, podOrSliceId, bNodeIdMap) } ?: "",
                    statement.`object`.takeIf { it.isLiteral }?.let { it as Literal }?.datatype?.stringValue(),
                    statement.`object`.takeIf { it.isLiteral }?.let { it as Literal }?.language?.getOrNull(),
                )
            }
    }

    private fun processedNonLiteralValue(
        rdfValue: Value,
        podOrSliceId: String,
        bNodeIdMap: MutableMap<BNode, String>
    ): String {
        return if (rdfValue.isBNode) {
            bNodeIdMap.getOrPut(rdfValue as BNode) {
                URI.create(podOrSliceId).getChildUri("#${UUID.randomUUID()}").toString()
            }
        } else {
            RDFTransformer.ensureValidAbsoluteIri(rdfValue.stringValue())
        }
    }

    private fun parseLang(rawContentType: String): RDFFormat {
        return when (val contentType = MediaType.valueOf(rawContentType).let { "${it.type}/${it.subtype}" }) {
            "text/turtle" -> RDFFormat.TURTLE
            "text/n3" -> RDFFormat.N3
            "application/n-triples" -> RDFFormat.NTRIPLES
            "application/ld+json" -> RDFFormat.JSONLD
            else -> throw IllegalArgumentException("Unsupported content type: $contentType")
        }
    }

    private fun getCompatibleRawValue(literal: Literal): Any {
        return RDFLiteralUtils.getCompatibleRawValue(
            literal.stringValue(),
            literal.datatype.stringValue() ?: XSDVocab.string
        )
    }
}