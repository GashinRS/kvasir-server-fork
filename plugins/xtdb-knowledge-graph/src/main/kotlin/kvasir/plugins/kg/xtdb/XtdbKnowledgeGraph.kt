package kvasir.plugins.kg.xtdb

import com.google.common.hash.Hashing
import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.ExceptionWhileDataFetching
import graphql.ExecutionResult
import graphql.TypeResolutionEnvironment
import graphql.language.Field
import graphql.language.InlineFragment
import graphql.language.StringValue
import graphql.schema.DataFetcher
import graphql.schema.DataFetcherFactoryEnvironment
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolver
import io.quarkus.arc.All
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Singleton
import kvasir.definitions.kg.HistoryRequest
import kvasir.definitions.kg.HistoryResult
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.ReferenceLoader
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.xtdb.changes.MetaStore
import kvasir.plugins.kg.xtdb.query.FieldRefFilterVisitor
import kvasir.plugins.kg.xtdb.query.GraphQLFilterVisitor
import kvasir.utils.kg.AbstractKnowledgeGraph
import kvasir.utils.kg.KGType
import org.dataloader.BatchLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderRegistry
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@Singleton
class XtdbKnowledgeGraph(
    @All
    private val referenceLoaders: MutableList<ReferenceLoader>,
    private val metaStore: MetaStore,
    private val xtdbClient: XtdbClient,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.assertion-checking-parallelism", defaultValue = "4")
    private val assertionCheckingParallelism: Int,
    @ConfigProperty(name = "kvasir.plugins.kg.xtdb.ref-handling-buffer", defaultValue = "50000")
    private val refHandlingBuffer: Int
) : AbstractKnowledgeGraph(referenceLoaders, assertionCheckingParallelism, refHandlingBuffer) {
    override fun insertStatements(
        podId: String,
        statements: List<RDFStatement>
    ): Uni<Void> {
        val database = dbNameForPod(podId)
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "INSERT INTO $database (_id, s, p, o, t, g) VALUES (?, ?, ?, ?, ?, ?)",
                    statements.map { statement ->
                        listOf(
                            getRecordId(statement),
                            statement.subject,
                            statement.predicate,
                            statement.`object`,
                            listOf(
                                statement.dataType?.let { "Literal" } ?: "IRI",
                                statement.dataType ?: "n/a",
                                statement.language ?: "n/a"
                            ),
                            statement.graph
                        )
                    }
                )
            )
        ).chain { _ -> metaStore.syncMetaInfo(podId, statements) }
    }

    override fun deleteStatements(
        podId: String,
        statements: List<RDFStatement>
    ): Uni<Void> {
        val database = dbNameForPod(podId)
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "DELETE FROM $database WHERE _id = ?",
                    statements.map { statement -> listOf(getRecordId(statement)) }
                )
            )
        )
    }

    override fun deleteGraph(podId: String, graph: String): Uni<Void> {
        val database = dbNameForPod(podId)
        return xtdbClient.execute(
            SqlTransaction(
                SqlOp(
                    "DELETE FROM $database WHERE g = ?",
                    listOf(listOf(graph))
                )
            )
        )
    }

    override fun buildDataLoaderRegistry(podId: String, context: Map<String, Any>): DataLoaderRegistry {
        return DataLoaderRegistry()
            .register(
                "entrypoints",
                DataLoaderFactory.newDataLoader(
                    EntrypointTargetSelectionLoader(
                        xtdbClient,
                        dbNameForPod(podId)
                    )
                )
            )
            .register(
                "targets",
                DataLoaderFactory.newDataLoader(
                    PredicateTargetSelectionLoader(
                        xtdbClient,
                        dbNameForPod(podId)
                    )
                )
            )
            .register(
                "predicateValues",
                DataLoaderFactory.newDataLoader(
                    PredicateValueLoader(
                        xtdbClient,
                        dbNameForPod(podId),
                        context
                    )
                )
            )
    }

    override fun buildDatafetcher(
        podId: String,
        context: Map<String, Any>
    ): DataFetcher<Any> {
        val fetchingHandler = XtdbDataFetchingHandler(context)
        return object : DataFetcher<Any> {
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

    override fun buildUnionTypeResolver(
        podId: String,
        unionType: GraphQLUnionType,
        context: Map<String, Any>
    ): TypeResolver {
        return RDFClassTypeResolver(context)
    }

    override fun getTypeInfo(podId: String): Uni<List<KGType>> {
        return metaStore.listTypes(podId)
    }

    override fun history(request: HistoryRequest): Uni<HistoryResult> {
        TODO("Not yet implemented")
    }

    override fun mapExecutionResult(request: QueryRequest, result: ExecutionResult): QueryResult {
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
        return QueryResult(
            data = filteredData,
            errors = result.errors.filterNot { it is ExceptionWhileDataFetching && it.exception is NoResultsException }
                .map { JsonObject.mapFrom(it).map })
    }

    private fun getRecordId(statement: RDFStatement) =
        "kvasir:" + Hashing.farmHashFingerprint64()
            .hashString(
                "${statement.graph}${statement.subject}${statement.predicate}${statement.`object`}${statement.dataType ?: ""}${statement.language ?: ""}",
                Charsets.UTF_8
            )
}

internal fun dbNameForPod(podId: String): String {
    return podId
    //return "kvasir_" + Hashing.farmHashFingerprint64().hashString(podId, Charsets.UTF_8)
}

internal fun metaDbNameForPod(podId: String): String {
    return dbNameForPod(podId) + "_meta"
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
                JsonLdHelper.getFQName(outputType.name, context, "_")!!,
                filter
            )
        )
    }

    fun handleScalar(env: DataFetchingEnvironment): Any {
        val parentSubject = env.getSource<Target>()!!
        return if (env.field.name == "id") {
            return parentSubject.id
        } else {
            val predicate = JsonLdHelper.getFQName(env.field.name, context, "_")!!
            val predicateValueLoader = env.getDataLoader<PredicateValueKey, List<Any>>("predicateValues")!!
            predicateValueLoader.load(PredicateValueKey(parentSubject.id, predicate))
        }.thenCompose { values ->
            if (env.field.hasDirective(AbstractKnowledgeGraph.optionalDirective.name) || values.isNotEmpty()) {
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
                JsonLdHelper.getFQName(env.field.name, context, "_")!!,
                filter
            )
        ).thenCompose { targets ->
            if (env.field.hasDirective(AbstractKnowledgeGraph.optionalDirective.name) || targets.isNotEmpty()) {
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
            subField.directives.firstOrNull { it.name == AbstractKnowledgeGraph.filterDirective.name }
                ?.let { directive ->
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