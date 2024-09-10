package kvasir.plugins.kg.xtdb.changes

import com.google.common.hash.Hashing
import io.minio.GetObjectArgs
import io.minio.GetObjectResponse
import io.minio.MinioAsyncClient
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.definitions.rdf.XSDVocab
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.query.QueryResults
import org.eclipse.rdf4j.rio.RDFFormat
import kotlin.jvm.optionals.getOrNull

@ApplicationScoped
class ReferenceHandler(
    private val minioClient: MinioAsyncClient
) {

    fun handleReferences(refs: List<Map<String, Any>>, podId: String, targetGraph: String): Multi<List<Any?>> {
        return Multi.createFrom().iterable(refs)
            .onItem().transformToMulti { referenceInstance ->
                when (referenceInstance[JsonLdKeywords.type]) {
                    KvasirVocab.S3Reference -> handleS3Reference(
                        referenceInstance[KvasirVocab.Key] as String,
                        podId,
                        targetGraph
                    )

                    else -> Multi.createFrom().failure(IllegalArgumentException("Unsupported reference type"))
                }
            }
            .concatenate()
    }

    /**
     * Stream S3 ref containing linked-data as a Multi of Xtdb tuples.
     */
    fun handleS3Reference(key: String, podId: String, targetGraph: String): Multi<List<Any?>> {
        return Uni.createFrom()
            .future(minioClient.getObject(GetObjectArgs.builder().bucket(podId).`object`(key).build()))
            .onItem().transformToMulti { resp ->
                // TODO: do we need to set a baseURI here?
                Multi.createFrom().iterable(QueryResults.parseGraphBackground(resp, null, parseLang(resp)))
            }
            .map { statement ->
                listOf(
                    getRecordId(statement, targetGraph),
                    statement.subject.stringValue(),
                    statement.predicate.stringValue(),
                    if (statement.`object`.isLiteral) getCompatibleRawValue(statement.`object` as Literal) else statement.`object`.stringValue(),
                    listOf(
                        when {
                            statement.`object`.isIRI -> "IRI"
                            statement.`object`.isBNode -> "BlankNode"
                            statement.`object`.isLiteral -> "Literal"
                            else -> "Unknown"
                        },
                        statement.`object`.takeIf { it.isLiteral }?.let { it as Literal }?.datatype?.stringValue()
                            ?: "n/a",
                        statement.`object`.takeIf { it.isLiteral }?.let { it as Literal }?.language?.getOrNull()
                            ?: "n/a"
                    ),
                    targetGraph
                )
            }
    }

    private fun getRecordId(statement: Statement, targetGraph: String) =
        "kvasir:" + Hashing.farmHashFingerprint64().hashString(
            "${targetGraph}${statement.subject.stringValue()}${statement.predicate.stringValue()}${statement.`object`.stringValue()}",
            Charsets.UTF_8
        )

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
            XSDVocab.double -> literal.doubleValue()
            XSDVocab.long -> literal.longValue()
            XSDVocab.boolean -> literal.booleanValue()
            else -> literal.stringValue()
        }
    }

}