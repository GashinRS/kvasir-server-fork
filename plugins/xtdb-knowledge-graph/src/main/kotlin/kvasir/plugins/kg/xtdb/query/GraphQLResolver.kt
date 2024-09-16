package kvasir.plugins.kg.xtdb.query

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.Scalars.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.RDFVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.plugins.kg.xtdb.SqlQuery
import kvasir.plugins.kg.xtdb.XtdbClient
import kvasir.plugins.kg.xtdb.dbNameForPod
import kvasir.plugins.kg.xtdb.processOutput

@ApplicationScoped
class GraphQLResolver(
    private val xtdbClient: XtdbClient
) {

    private val CONCURRENCY = 64

    fun resolve(request: QueryRequest): Uni<QueryResult> {
        return constructSchemaFromDatabase(request.podId, request.context)
            .chain { generatedSchema ->
                val startTs = System.currentTimeMillis()
                val typeDefinitionRegistry = SchemaParser().parse(SchemaPrinter().print(generatedSchema))
                println("Parsed schema in ${System.currentTimeMillis() - startTs}ms")

                val runtimeWiring = RuntimeWiring.newRuntimeWiring().type("Query") { builder ->
                    builder.defaultDataFetcher { env ->
                        val queryMapping = GraphQLToSQL(request)
                        xtdbClient.query(SqlQuery(queryMapping.toSQL())).map { results ->
                            val qr = results.firstOrNull()
                                ?.let { processOutput(queryMapping, it) as Map<String, Any> }
                                ?: emptyMap()
                            qr[env.fieldDefinition.name]
                        }.convert().toCompletableFuture()
                    }
                }.build()

                val executableSchema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
                val build = GraphQL.newGraphQL(executableSchema).build()
                Uni.createFrom().future(
                    build.executeAsync(
                        ExecutionInput.newExecutionInput().query(request.query).build()
                    )
                ).map { result ->
                    QueryResult(data = result.getData(), errors = result.errors.map { JsonObject.mapFrom(it).map })
                }
            }
    }

    fun constructSchemaFromDatabase(podId: String, context: Map<String, Any>): Uni<GraphQLSchema> {
        val startTs = System.currentTimeMillis()
        val dbName = dbNameForPod(podId)
        // Query types
        val q = "SELECT o as type FROM $dbName WHERE p = '${RDFVocab.type}' GROUP BY o"
        return xtdbClient.query(SqlQuery(q)).onItem().transformToMulti { results ->
            Multi.createFrom().iterable(results.map { it["type"] as String })
        }
            .onItem()
            .transformToUni { type ->
                val prefixedTypeName = JsonLdHelper.compactUri(type, context, "_")
                val q =
                    "SELECT p, t FROM $dbName WHERE s IN (SELECT s FROM $dbName WHERE p = '${RDFVocab.type}' AND o = '$type') AND NOT p = '${RDFVocab.type}' GROUP BY p, t"
                xtdbClient.query(SqlQuery(q)).onItem()
                    .transformToMulti { results -> Multi.createFrom().iterable(results) }
                    .onItem().transformToUni { result ->
                        val property = result["p"] as String
                        val typeInfo = result["t"] as List<String>
                        fetchType(dbName, type, property, typeInfo, context).map { property to it }
                    }
                    .merge(CONCURRENCY)
                    .collect().asList()
                    .map { properties ->
                        val idField = GraphQLFieldDefinition.newFieldDefinition().name("id").type(GraphQLID).build()
                        GraphQLObjectType.newObject().name(prefixedTypeName).description(type).fields(
                            listOf(idField) + properties.map { (property, type) ->
                                val prefixedProperty = JsonLdHelper.compactUri(property, context, "_")
                                GraphQLFieldDefinition.newFieldDefinition().name(prefixedProperty).description(property)
                                    .type(GraphQLList.list(type)).build()
                            }
                        ).build()
                    }
            }
            .merge(CONCURRENCY)
            .collect()
            .asList()
            .map { types ->
                val rdfsResourceEntryPoint = GraphQLObjectType.newObject().name("rdfs_Resource")
                    .fields(types.flatMap { it.fields }.distinctBy { it.name }).build()
                val schema = GraphQLSchema.newSchema()
                    .query(
                        GraphQLObjectType.newObject().name("Query")
                            .fields((listOf(rdfsResourceEntryPoint) + types).map { type ->
                                GraphQLFieldDefinition.newFieldDefinition().name(type.name).type(GraphQLList.list(type))
                                    .build()
                            }).build()
                    )
                    .build()
                println("Generated schema in ${System.currentTimeMillis() - startTs}ms")
                schema
            }
    }

    fun fetchType(
        database: String,
        type: String,
        property: String,
        typeInfo: List<String>,
        context: Map<String, Any>
    ): Uni<GraphQLOutputType> {
        return if (typeInfo[0] == "Literal") {
            Uni.createFrom().item(
                when (typeInfo[1]) {
                    XSDVocab.boolean -> GraphQLBoolean
                    XSDVocab.int, XSDVocab.integer, XSDVocab.long -> GraphQLInt
                    XSDVocab.double, XSDVocab.decimal -> GraphQLFloat
                    else -> GraphQLString
                }
            )
        } else {
            // TODO: there can be multiple classes for a property, we need to handle that
            val q =
                "SELECT o FROM $database WHERE p = '${RDFVocab.type}' AND s IN (SELECT o FROM $database WHERE p = '$property') LIMIT 1"
            xtdbClient.query(SqlQuery(q)).map { results ->
                results.firstOrNull()?.let {
                    val propertyTypeName = JsonLdHelper.compactUri(results.first()["o"] as String, context, "_")
                    GraphQLTypeReference.typeRef(propertyTypeName)
                } ?: GraphQLID
            }
        }
    }

}

//fun main() {
//    val context = mapOf(
//        "voc" to "https://swapi.co/vocabulary/",
//        "commons" to "https://commons.wikimedia.org/wiki/Special:FilePath/",
//        "dc" to "http://purl.org/dc/elements/1.1/",
//        "dct" to "http://purl.org/dc/terms/",
//        "owl" to "http://www.w3.org/2002/07/owl#",
//        "p" to "http://www.wikidata.org/prop/",
//        "pq" to "http://www.wikidata.org/prop/qualifier/",
//        "ps" to "http://www.wikidata.org/prop/statement/",
//        "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
//        "rdfs" to "http://www.w3.org/2000/01/rdf-schema#",
//        "wd" to "http://www.wikidata.org/entity/",
//        "wds" to "http://www.wikidata.org/entity/statement/",
//        "wdt" to "http://www.wikidata.org/prop/direct/",
//        "wgs" to "http://www.w3.org/2003/01/geo/wgs84_pos#",
//        "wikibase" to "http://wikiba.se/ontology#",
//        "xml" to "http://www.w3.org/XML/1998/namespace",
//        "xsd" to "http://www.w3.org/2001/XMLSchema#"
//    )
//    val q = "query { rdfs_Resource { id } }"
//    val request = QueryRequest(
//        "swdemo",
//        graphQL = GraphQLUtils.parseDocumentWithContext(
//            q,
//            context
//        )
//    )
//    println(GraphQLResolver().resolve(request, q, context).await().indefinitely())
//}