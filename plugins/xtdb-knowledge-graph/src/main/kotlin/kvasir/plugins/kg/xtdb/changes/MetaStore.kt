package kvasir.plugins.kg.xtdb.changes

import com.github.jsonldjava.shaded.com.google.common.hash.Hashing
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.rdf.RDFSVocab
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.xtdb.*
import kvasir.utils.kg.KGProperty
import kvasir.utils.kg.KGPropertyKind
import kvasir.utils.kg.KGType

private const val CONCURRENCY = 32

@ApplicationScoped
class MetaStore(private val xtdbClient: XtdbClient) {

    fun syncMetaInfo(podId: String, statements: List<RDFStatement>): Uni<Void> {
        // Transform
        val typeUrisToSubjects = statements.filter { it.predicate == RDFVocab.type }.groupBy { it.`object` as String }
            .mapValues { statementsByType ->
                statementsByType.component2().map { it.subject }
            }
        // Reverse mapping
        val subjectsToTypeUris = statements.filter { it.predicate == RDFVocab.type }.groupBy { it.subject }
            .mapValues { statementsBySubject ->
                statementsBySubject.component2().map { it.`object` as String }
            }

        return Multi.createFrom().iterable(typeUrisToSubjects.entries).onItem()
            .transformToMulti { (typeUri, subjects) ->
                Multi.createFrom()
                    .iterable(statements.filter { it.subject in subjects && it.predicate != RDFVocab.type }.distinct())
                    .onItem().transformToMulti { statement ->
                        if (statement.dataType != null) {
                            Multi.createFrom().item(statement.dataType!!)
                        } else {
                            // First try to find local typeRefs
                            subjectsToTypeUris[statement.`object` as String]?.let { typeRefs ->
                                Multi.createFrom().iterable(typeRefs)
                            } ?: Multi.createFrom().item(RDFSVocab.Resource)
                        }
                            .map { typeRef ->
                                typeUri to
                                        KGProperty(
                                            uri = statement.predicate,
                                            kind = statement.dataType?.let { KGPropertyKind.Literal } ?: KGPropertyKind.IRI,
                                            typeRefs = setOf(typeRef)
                                        )
                            }
                    }
                    .merge(CONCURRENCY)
            }.merge(CONCURRENCY)
            .collect().asList()
            .onItem().transformToUni { inserts ->
                xtdbClient.execute(
                    SqlTransaction(
                        SqlOp(
                            sql = "INSERT INTO ${metaDbNameForPod(podId)} (_id, type_uri, property_uri, property_kind, property_ref) VALUES (?, ?, ?, ?, ?)",
                            argRows = inserts.map { (typeUri, property) ->
                                listOf(
                                    Hashing.farmHashFingerprint64()
                                        .hashString(
                                            "$typeUri-${property.uri}-${property.typeRefs.first()}",
                                            Charsets.UTF_8
                                        ).toString(),
                                    typeUri,
                                    property.uri,
                                    property.kind.name,
                                    property.typeRefs.first()
                                )
                            }
                        )
                    )
                )
            }
    }

    fun listTypes(podId: String): Uni<List<KGType>> {
        return xtdbClient.query(
            SqlQuery(
                "SELECT type_uri, ARRAY_AGG([property_uri, property_kind, property_ref]) AS properties FROM ${
                    metaDbNameForPod(
                        podId
                    )
                } GROUP BY type_uri"
            )
        )
            .onItem().transformToUni { results ->
                Uni.createFrom().item(
                    results.map { result ->
                        KGType(
                            uri = result["type_uri"] as String,
                            properties = (result["properties"] as List<List<String>>).groupBy { (propertyUri, propertyKind, _) -> propertyUri to propertyKind }
                                .map { groupedByProperty ->
                                    KGProperty(
                                        uri = groupedByProperty.key.first,
                                        kind = KGPropertyKind.valueOf(groupedByProperty.key.second),
                                        typeRefs = groupedByProperty.value.map { (_, _, propertyRef) -> propertyRef }
                                            .toSet()
                                    )
                                }
                        )
                    }
                )
            }
    }

}