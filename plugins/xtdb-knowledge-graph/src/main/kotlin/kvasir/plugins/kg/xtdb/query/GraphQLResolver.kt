package kvasir.plugins.kg.xtdb.query

import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.ExceptionWhileDataFetching
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.Scalars.*
import graphql.TypeResolutionEnvironment
import graphql.introspection.Introspection
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.UnionWiringEnvironment
import graphql.schema.idl.WiringFactory
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFVocab
import kvasir.definitions.rdf.XSDVocab
import kvasir.plugins.kg.xtdb.SqlQuery
import kvasir.plugins.kg.xtdb.XtdbClient
import kvasir.plugins.kg.xtdb.changes.KGProperty
import kvasir.plugins.kg.xtdb.changes.KGPropertyKind
import kvasir.plugins.kg.xtdb.changes.MetaStore
import kvasir.plugins.kg.xtdb.dbNameForPod
import kvasir.plugins.kg.xtdb.processOutput
import org.dataloader.BatchLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderRegistry
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

private val optionalDirective =
    GraphQLDirective.newDirective().name("optional").validLocation(Introspection.DirectiveLocation.FIELD).build()
private val filterDirective =
    GraphQLDirective.newDirective().name("filter").validLocation(Introspection.DirectiveLocation.FIELD)
        .argument(GraphQLArgument.newArgument().name("if").type(GraphQLString).build()).build()
private val defaultRelationArguments = listOf(
    GraphQLArgument.newArgument().name("id").type(GraphQLList.list(GraphQLID)).build(),
    GraphQLArgument.newArgument().name("first").type(GraphQLInt).build(),
    GraphQLArgument.newArgument().name("skip").type(GraphQLInt).build(),
    GraphQLArgument.newArgument().name("orderBy").type(GraphQLString).build()
)

@ApplicationScoped
class GraphQLResolver(
    private val xtdbClient: XtdbClient,
    private val metaStore: MetaStore,
    // Options: graphql2sql, graphql-java
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.resolver", defaultValue = "graphql-java")
    private val resolver: String
) {

    fun resolve(request: QueryRequest): Uni<QueryResult> {
        return constructSchema(request.podId, request.context)
            .chain { (generatedSchema, unionTypes) ->
                val dataLoaderRegistry = DataLoaderRegistry()
                    .register(
                        "entrypoints",
                        DataLoaderFactory.newDataLoader(
                            EntrypointTargetSelectionLoader(
                                xtdbClient,
                                dbNameForPod(request.podId)
                            )
                        )
                    )
                    .register(
                        "targets",
                        DataLoaderFactory.newDataLoader(
                            PredicateTargetSelectionLoader(
                                xtdbClient,
                                dbNameForPod(request.podId)
                            )
                        )
                    )
                    .register(
                        "predicateValues",
                        DataLoaderFactory.newDataLoader(
                            PredicateValueLoader(
                                xtdbClient,
                                dbNameForPod(request.podId),
                                request.context
                            )
                        )
                    )
                val runtimeWiring = when (resolver) {
                    "graphql2sql" -> RuntimeWiring.newRuntimeWiring().type("Query") { builder ->
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

                    "graphql-java" -> customRuntimeWiring(
                        XtdbDataFetchingHandler(request.context),
                        request.context
                    )

                    else -> throw IllegalArgumentException("Unknown resolver: $resolver")
                }

                val fetchingHandler = XtdbDataFetchingHandler(request.context)
                val defaultTypeResolver = RDFClassTypeResolver(request.context)
                val codeRegistry = GraphQLCodeRegistry.newCodeRegistry().defaultDataFetcher { env ->
                    object : DataFetcher<Any> {
                        override fun get(env: DataFetchingEnvironment): Any {
                            if (env.executionStepInfo.path.parent.isRootPath) {
                                // Handle entrypoints
                                return fetchingHandler.handleEntrypoint(env)
                            } else if (isScalarType(env.fieldDefinition.type)) {
                                // Handle scalar types
                                return fetchingHandler.handleScalar(env)
                            } else {
                                // Handle relations
                                return fetchingHandler.handleRelation(env)
                            }
                        }
                    }
                }
                unionTypes.forEach { unionType -> codeRegistry.typeResolver(unionType, defaultTypeResolver) }
                val executableSchema = generatedSchema.codeRegistry(codeRegistry.build()).build()
                val build = GraphQL.newGraphQL(executableSchema).build()
                Uni.createFrom().future(
                    build.executeAsync(
                        ExecutionInput.newExecutionInput().dataLoaderRegistry(dataLoaderRegistry).query(request.query)
                            .build()
                    )
                ).map { result ->
                    // Use NonNullableValueCoercedAsNullException to filter out paths that have no results
                    val skipPositions = result.errors
                        .filter { it is ExceptionWhileDataFetching && it.exception is NoResultsException }
                        .groupBy { it.path.first() }
                        .mapValues { err -> err.value.map { it.path.drop(1).first() }.toSet() }
                    val filteredData = result?.getData<Map<String, Any>>()?.mapValues { entryPoint ->
                        skipPositions[entryPoint.key]?.let { positions ->
                            val values = entryPoint.value as List<Any>
                            values.mapIndexed { index, any -> if (index in positions) null else any }
                                .filterNotNull()
                        } ?: entryPoint.value
                    }
                    QueryResult(
                        data = filteredData,
                        errors = result.errors.filterNot { it is ExceptionWhileDataFetching && it.exception is NoResultsException }
                            .map { JsonObject.mapFrom(it).map })
                }
            }
    }

    private fun constructSchema(
        podId: String,
        context: Map<String, Any>
    ): Uni<Pair<GraphQLSchema.Builder, Set<GraphQLUnionType>>> {
        return metaStore.listTypes(podId)
            .map { types ->
                val unionTypes = mutableSetOf<GraphQLUnionType>()
                val graphQLObjects = types.map { type ->
                    val prefixedTypeName = JsonLdHelper.compactUri(type.uri, context, "_")
                    val idField = GraphQLFieldDefinition.newFieldDefinition().name("id").type(GraphQLID).build()
                    GraphQLObjectType.newObject().name(prefixedTypeName).description(type.uri).fields(
                        listOf(idField) + type.properties.map { property ->
                            val prefixedProperty = JsonLdHelper.compactUri(property.uri, context, "_")
                            val propertyType = getGraphQLPropertyType(property, unionTypes, context)
                            val propertyBuilder = GraphQLFieldDefinition.newFieldDefinition()
                                .arguments(if (KGPropertyKind.IRI == property.kind) defaultRelationArguments else emptyList())
                                .name(prefixedProperty)
                                .description(property.uri)
                                .type(GraphQLList.list(propertyType))
                            propertyBuilder.build()
                        }
                    ).build()
                }
                val rdfsResourceEntryPoint = GraphQLObjectType.newObject().name("rdfs_Resource")
                    .fields(graphQLObjects.flatMap { it.fields }.distinctBy { it.name }).build()
                val schema = GraphQLSchema.newSchema()
                    .query(
                        GraphQLObjectType.newObject().name("Query")
                            .fields((listOf(rdfsResourceEntryPoint) + graphQLObjects).map { type ->
                                GraphQLFieldDefinition.newFieldDefinition().name(type.name).type(GraphQLList.list(type))
                                    .arguments(defaultRelationArguments)
                                    .build()
                            }).build()
                    )
                    .additionalDirective(optionalDirective)
                    .additionalDirective(filterDirective)
                schema to unionTypes
            }
    }

    private fun getGraphQLPropertyType(
        property: KGProperty,
        unionTypes: MutableSet<GraphQLUnionType>,
        context: Map<String, Any>
    ): GraphQLOutputType {
        val outputTypes = property.typeRefs.map { typeRef ->
            when (property.kind) {
                KGPropertyKind.Literal -> when (typeRef) {
                    XSDVocab.boolean -> GraphQLBoolean
                    XSDVocab.int, XSDVocab.integer, XSDVocab.long -> GraphQLInt
                    XSDVocab.double, XSDVocab.decimal -> GraphQLFloat
                    XSDVocab.string, RDFVocab.langString -> GraphQLString
                    else -> ExtendedScalars.Json
                } as GraphQLOutputType

                KGPropertyKind.IRI -> {
                    val propertyTypeName = JsonLdHelper.compactUri(typeRef, context, "_")
                    GraphQLTypeReference.typeRef(propertyTypeName)
                }

                else -> throw IllegalArgumentException("Unsupported property kind: ${property.kind}")
            }
        }
        return if (outputTypes.size > 1) {
            if (outputTypes.any { it is GraphQLScalarType }) {
                // If there are scalar types in the union, use JSON
                ExtendedScalars.Json
            } else {
                val graphQLOutputTypeReferences = outputTypes.filterIsInstance<GraphQLTypeReference>()
                val unionName = graphQLOutputTypeReferences.joinToString("Or") { it.name }
                if (unionTypes.none { it.name == unionName }) {
                    val unionType = GraphQLUnionType.newUnionType()
                        .name(unionName)
                        .possibleTypes(*graphQLOutputTypeReferences.toTypedArray())
                        .build()
                    unionTypes.add(unionType)
                    unionType
                } else {
                    GraphQLTypeReference.typeRef(unionName)
                }
            }
        } else {
            outputTypes.first()
        }
    }

    private fun customRuntimeWiring(
        xtdbDataFetchingHandler: XtdbDataFetchingHandler,
        context: Map<String, Any>
    ): RuntimeWiring {
        val dynamicWiringFactory = object : WiringFactory {

            override fun providesTypeResolver(environment: UnionWiringEnvironment?): Boolean {
                return true
            }

            override fun providesDataFetcher(environment: FieldWiringEnvironment): Boolean {
                return true
            }

            override fun getDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> {
                return DataFetcher { runtimeEnv ->
                    if (isScalarType(runtimeEnv.fieldType)) {
                        // Handle scalar types
                        xtdbDataFetchingHandler.handleScalar(runtimeEnv)
                    } else if (runtimeEnv.executionStepInfo.path.parent.isRootPath) {
                        // Handle entrypoints
                        xtdbDataFetchingHandler.handleEntrypoint(runtimeEnv)
                    } else {
                        // Handle relations
                        xtdbDataFetchingHandler.handleRelation(runtimeEnv)
                    }
                }
            }

            override fun getTypeResolver(environment: UnionWiringEnvironment): TypeResolver {
                return RDFClassTypeResolver(context)
            }

        }

        return RuntimeWiring.newRuntimeWiring()
            .wiringFactory(dynamicWiringFactory)
            .build()
    }

}

class XtdbDataFetchingHandler(
    private val context: Map<String, Any>
) {

    fun handleEntrypoint(env: DataFetchingEnvironment): Any {
        val targetSelectionLoader = env.getDataLoader<EntryPointKey, List<String>>("entrypoints")!!
        val outputType = GraphQLTypeUtil.unwrapOne(env.fieldDefinition.type) as GraphQLNamedOutputType
        val filter = listOfNotNull(
            getNodeFilter(env.field),
            env.getArgument<List<String>>("id")?.let { idFilter -> ComparisonNode(RSQLOperators.IN, "id", idFilter) }
        ).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it.first() else AndNode(it) }
        return targetSelectionLoader.load(
            EntryPointKey(
                context,
                JsonLdHelper.getFQName(outputType.name, context, "_"),
                filter
            )
        )
    }

    fun handleScalar(env: DataFetchingEnvironment): Any {
        val parentSubject = env.getSource<Target>()!!
        return if (env.field.name == "id") {
            return parentSubject.id
        } else {
            val predicate = JsonLdHelper.getFQName(env.field.name, context, "_")
            val predicateValueLoader = env.getDataLoader<PredicateValueKey, List<Any>>("predicateValues")!!
            predicateValueLoader.load(PredicateValueKey(parentSubject.id, predicate))
        }.thenCompose { values ->
            if (env.field.hasDirective(optionalDirective.name) || values.isNotEmpty()) {
                CompletableFuture.completedFuture(values)
            } else {
                CompletableFuture.failedFuture(NoResultsException())
            }
        }
    }

    fun handleRelation(env: DataFetchingEnvironment): Any {
        val parentSubject = env.getSource<Target>()!!
        val targetSelectionLoader = env.getDataLoader<PredicateTargetSelectionKey, List<String>>("targets")!!
        val filter = listOfNotNull(
            getNodeFilter(env.field),
            env.getArgument<List<String>>("id")?.let { idFilter -> ComparisonNode(RSQLOperators.IN, "id", idFilter) }
        ).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it.first() else AndNode(it) }
        return targetSelectionLoader.load(
            PredicateTargetSelectionKey(
                context,
                parentSubject.id,
                JsonLdHelper.getFQName(env.field.name, context, "_"),
                filter
            )
        ).thenCompose { targets ->
            if (env.field.hasDirective(optionalDirective.name) || targets.isNotEmpty()) {
                CompletableFuture.completedFuture(targets)
            } else {
                CompletableFuture.failedFuture(NoResultsException())
            }
        }
    }

    private fun getNodeFilter(field: Field): Node? {
        val subFields = field.selectionSet?.selections?.flatMap {
            if (it is InlineFragment) it.selectionSet.selections else listOf(it)
        }?.filterIsInstance<Field>()?.filterNot { it.name == "id" }
        val filters = subFields?.mapNotNull { subField ->
            subField.directives.firstOrNull { it.name == filterDirective.name }?.let { directive ->
                val rsqlExpr = directive.getArgument("if")?.value?.let { (it as StringValue).value }
                    ?: throw IllegalArgumentException("Missing 'if' argument containing RSQL expression on filter directive")
                val rsqlParser = RSQLParser()
                rsqlParser.parse(rsqlExpr).accept(FieldRefFilterVisitor(subField))
            }
        }
        return filters?.takeIf { it.isNotEmpty() }?.let {
            if (it.size == 1) it.first() else AndNode(it)
        }
    }

}

private fun isScalarType(type: GraphQLType): Boolean {
    return when {
        GraphQLTypeUtil.isScalar(type) -> true
        GraphQLTypeUtil.isWrapped(type) -> isScalarType(GraphQLTypeUtil.unwrapOne(type))
        else -> false
    }
}

data class EntryPointKey(
    val context: Map<String, Any>,
    val typeUri: String,
    val filter: Node? = null
)

data class PredicateTargetSelectionKey(
    val context: Map<String, Any>,
    val subject: String,
    val predicate: String,
    val filter: Node? = null
)

private fun nodeToFilter(filter: Node, context: Map<String, Any>): String {
    return GraphQLFilterVisitor(context).visitNode(filter)
}

data class PredicateValueKey(val subject: String, val predicate: String)

class EntrypointTargetSelectionLoader(private val xtdbClient: XtdbClient, private val database: String) :
    BatchLoader<EntryPointKey, List<Target>> {
    override fun load(keys: List<EntryPointKey>): CompletionStage<List<List<Target>>> {
        val q = keys.mapIndexed { index, key ->
            val optionalFilter =
                key.filter?.let { " AND s IN (SELECT s FROM $database WHERE ${nodeToFilter(it, key.context)})" } ?: ""
            "SELECT $index AS index, ARRAY_AGG(s) AS targets FROM $database WHERE p = '${RDFVocab.type}' AND o = '${key.typeUri}'$optionalFilter"
        }.joinToString(" UNION ")
        return xtdbClient.query(SqlQuery(q)).map { results ->
            val resultMap = results.groupBy { it["index"] as Int }
            keys.mapIndexed { index, key ->
                resultMap[index]?.firstOrNull()
                    ?.let { (it["targets"] as List<String>).map { Target(it, listOf(key.typeUri)) } } ?: emptyList()
            }
        }.convert().toCompletableFuture()
    }
}

class PredicateTargetSelectionLoader(private val xtdbClient: XtdbClient, private val database: String) :
    BatchLoader<PredicateTargetSelectionKey, List<Target>> {

    override fun load(keys: List<PredicateTargetSelectionKey>): CompletionStage<List<List<Target>>> {
        val q =
            keys.mapIndexed { index, key ->
                val optionalFilter =
                    key.filter?.let { " AND s IN (SELECT s FROM $database WHERE ${nodeToFilter(it, key.context)})" }
                        ?: ""
                "SELECT $index AS index, ARRAY_AGG([o, types]) AS targets FROM $database JOIN (SELECT s AS subject, ARRAY_AGG(o) as types FROM $database WHERE p = '${RDFVocab.type}'$optionalFilter) type ON o = subject WHERE p = '${key.predicate}' AND s = '${key.subject}'"
            }.joinToString(" UNION ")
        return xtdbClient.query(SqlQuery(q)).map { results ->
            val resultMap = results.groupBy { it["index"] as Int }
            keys.mapIndexed { index, key ->
                resultMap[index]?.firstOrNull()?.let {
                    val targets = it["targets"] as List<List<Any>>
                    targets.map { (id, types) -> Target(id as String, types as List<String>) }
                } ?: emptyList()
            }
        }.convert().toCompletableFuture()
    }

}

class PredicateValueLoader(
    private val xtdbClient: XtdbClient,
    private val database: String,
    context: Map<String, Any>
) :
    BatchLoader<PredicateValueKey, List<Any>> {

    private val language = context[JsonLdKeywords.language] as String?

    override fun load(keys: List<PredicateValueKey>): CompletionStage<List<List<Any>>> {
        val filter = keys.groupBy { it.predicate }.toList().joinToString(
            " OR ",
            " WHERE "
        ) { (predicate, keys) ->
            val optionalLangFilter = language?.let { " AND t[3] IN ('n/a', '$it')" } ?: ""
            "p = '$predicate' AND s IN (${keys.joinToString { "'${it.subject}'" }})$optionalLangFilter"
        }
        val q = "SELECT s, p, o FROM $database $filter"
        return xtdbClient.query(SqlQuery(q)).map { results ->
            val resultMap = results.groupBy { PredicateValueKey(it["s"] as String, it["p"] as String) }
            val output = keys.map { key ->
                resultMap[key]?.map { it["o"]!! } ?: emptyList()
            }
            output
        }.convert().toCompletableFuture()
    }
}

class RDFClassTypeResolver(private val context: Map<String, Any>) : TypeResolver {
    override fun getType(env: TypeResolutionEnvironment): GraphQLObjectType {
        val target = env.getObject<Target>()
        // TODO: Implement type resolution, for now just return the first type in the list
        val prefixedTypeName = JsonLdHelper.compactUri(target.types.first(), context, "_")
        return env.schema.getObjectType(prefixedTypeName)
    }
}

data class Target(val id: String, val types: List<String>)
class NoResultsException() : RuntimeException()