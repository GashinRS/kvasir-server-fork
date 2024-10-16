package kvasir.plugins.kg.clickhouse.graphql

import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.AndNode
import cz.jirutka.rsql.parser.ast.ComparisonNode
import cz.jirutka.rsql.parser.ast.Node
import cz.jirutka.rsql.parser.ast.RSQLOperators
import graphql.TypeResolutionEnvironment
import graphql.language.BooleanValue
import graphql.language.Field
import graphql.language.FloatValue
import graphql.language.InlineFragment
import graphql.language.ScalarValue
import graphql.language.StringValue
import graphql.language.VariableReference
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.TypeResolver
import io.quarkus.logging.Log
import io.vertx.core.json.JsonArray
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.RDFVocab
import kvasir.plugins.kg.clickhouse.client.ClickhouseClient
import kvasir.plugins.kg.clickhouse.databaseFromPodId
import kvasir.plugins.kg.clickhouse.specs.DATA_TABLE
import kvasir.plugins.kg.clickhouse.specs.GenericQuerySpec
import kvasir.utils.kg.AbstractKnowledgeGraph
import org.dataloader.BatchLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.dataloader.DataLoaderRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

object SubOptimalResolver {

    private const val DATA_LOADER_MAX_CONCURRENCY = 25

    fun getDataLoaderRegistry(
        podId: String,
        clickhouseClient: ClickhouseClient,
        context: Map<String, Any>
    ): DataLoaderRegistry {
        return DataLoaderRegistry()
            .register(
                "entrypoints",
                DataLoaderFactory.newDataLoader(
                    EntrypointTargetSelectionLoader(
                        clickhouseClient,
                        databaseFromPodId(podId)
                    ), DataLoaderOptions.newOptions().setMaxBatchSize(DATA_LOADER_MAX_CONCURRENCY)
                )
            )
            .register(
                "targets",
                DataLoaderFactory.newDataLoader(
                    PredicateTargetSelectionLoader(
                        clickhouseClient,
                        databaseFromPodId(podId)
                    ), DataLoaderOptions.newOptions().setMaxBatchSize(DATA_LOADER_MAX_CONCURRENCY)
                )
            )
            .register(
                "predicateValues",
                DataLoaderFactory.newDataLoader(
                    PredicateValueLoader(
                        clickhouseClient,
                        databaseFromPodId(podId),
                        context
                    )
                )
            )
    }

    fun getDatafetcher(
        podId: String,
        context: Map<String, Any>
    ): DataFetcher<Any> {
        val fetchingHandler = ClickhouseDataFetchingHandler(context)
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

}

class ClickhouseDataFetchingHandler(
    private val context: Map<String, Any>
) {

    fun handleEntrypoint(env: DataFetchingEnvironment): Any {
        val targetSelectionLoader = env.getDataLoader<EntryPointKey, List<Target>>("entrypoints")!!
        val outputType = GraphQLTypeUtil.unwrapAll(env.fieldDefinition.type) as GraphQLDirectiveContainer
        val filter = listOfNotNull(
            getNodeFilter(env.field),
            getArgsFilter(env, env.field)
        ).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it.first() else AndNode(it) }
        return targetSelectionLoader.load(
            EntryPointKey(
                context,
                getFQName(outputType),
                filter
            )
        ).thenApply { values -> handleFieldMultiplicity(env, values) }
    }

    fun handleScalar(env: DataFetchingEnvironment): Any {
        val parentSubject = env.getSource<Target>()!!
        return if (env.field.name == "id") {
            return parentSubject.id
        } else {
            val predicate = getFQName(env.fieldDefinition)
            val predicateValueLoader = env.getDataLoader<PredicateValueKey, List<Any>>("predicateValues")!!
            predicateValueLoader.load(PredicateValueKey(parentSubject.id, predicate))
        }.thenCompose { values ->
            if (env.field.hasDirective(AbstractKnowledgeGraph.optionalDirective.name) || values.isNotEmpty()) {
                CompletableFuture.completedFuture(values)
            } else {
                CompletableFuture.failedFuture(NoResultsException())
            }
        }.thenApply { values -> handleFieldMultiplicity(env, values) }
    }

    fun handleRelation(env: DataFetchingEnvironment): Any {
        val parentSubject = env.getSource<Target>()!!
        val targetSelectionLoader = env.getDataLoader<PredicateTargetSelectionKey, List<Target>>("targets")!!
        val filter = listOfNotNull(
            getNodeFilter(env.field),
            getArgsFilter(env, env.field)
        ).takeIf { it.isNotEmpty() }?.let { if (it.size == 1) it.first() else AndNode(it) }
        return targetSelectionLoader.load(
            PredicateTargetSelectionKey(
                context,
                parentSubject.id,
                getFQName(env.fieldDefinition),
                filter
            )
        ).thenCompose { targets ->
            if (env.field.hasDirective(AbstractKnowledgeGraph.optionalDirective.name) || targets.isNotEmpty()) {
                CompletableFuture.completedFuture(targets)
            } else {
                CompletableFuture.failedFuture(NoResultsException())
            }
        }.thenApply { values -> handleFieldMultiplicity(env, values) }
    }

    // TODO: rewrite this quick and dirty implementation
    private fun getArgsFilter(env: DataFetchingEnvironment, field: Field): Node? {
        val argFilters =
            field.arguments.filter { it.name !in AbstractKnowledgeGraph.defaultRelationArguments.map { it.name } || it.name == "id" }
                .map { argument ->
                    when (argument.value) {
                        is List<*> -> ComparisonNode(
                            RSQLOperators.IN,
                            argument.name,
                            (argument.value as List<Any>).flatMap {
                                if (it is VariableReference) {
                                    val value = env.variables[it.name]!!
                                    if (value is List<*>) {
                                        value.map { it.toString() }
                                    } else {
                                        listOf(value.toString())
                                    }
                                } else {
                                    listOf(unboxScalar(it as ScalarValue<*>))
                                }
                            })

                        is VariableReference -> {
                            val value = env.variables[(argument.value as VariableReference).name]!!
                            if (value is List<*>) {
                                ComparisonNode(
                                    RSQLOperators.IN,
                                    argument.name,
                                    value.map { it.toString() }
                                )
                            } else {
                                ComparisonNode(
                                    RSQLOperators.EQUAL,
                                    argument.name,
                                    listOf(value.toString())
                                )
                            }
                        }

                        else -> ComparisonNode(
                            RSQLOperators.EQUAL,
                            argument.name,
                            listOf(unboxScalar(argument.value as ScalarValue<*>))
                        )
                    }
                }
        return argFilters.takeIf { it.isNotEmpty() }?.let {
            if (it.size == 1) it.first() else AndNode(it)
        }
    }

    private fun unboxScalar(scalar: ScalarValue<*>): String {
        return when (scalar) {
            is StringValue -> scalar.value.toString()
            is BooleanValue -> scalar.isValue.toString()
            is FloatValue -> scalar.value.toString()
            else -> throw IllegalArgumentException("Scalar type '${scalar::class.simpleName}' is not supported as argument")
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

    private fun handleFieldMultiplicity(env: DataFetchingEnvironment, values: List<Any>): Any? {
        return if (GraphQLTypeUtil.isList(env.fieldDefinition.type) || GraphQLTypeUtil.isList(
                GraphQLTypeUtil.unwrapOne(
                    env.fieldDefinition.type
                )
            )
        ) {
            values
        } else {
            values.firstOrNull()
        }
    }

    private fun getFQName(node: GraphQLDirectiveContainer): String {
        return JsonLdHelper.getFQName(node.name, context, "_")?.takeIf { it != node.name }
            ?: run {
                node.getAppliedDirective("predicate")?.getArgument("iri")?.getValue<String>()
                    ?: node.getAppliedDirective("type")?.getArgument("iri")?.getValue<String>()

            } ?: throw IllegalArgumentException("No semantic context found for ${node.name}")
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

class EntrypointTargetSelectionLoader(private val clickhouse: ClickhouseClient, private val database: String) :
    BatchLoader<EntryPointKey, List<Target>> {
    override fun load(keys: List<EntryPointKey>): CompletionStage<List<List<Target>>> {
        Log.debug("Loading entrypoint targets for:  ${keys.map { it.typeUri + " (filter: ${it.filter})" }}")
        val q = keys.mapIndexed { index, key ->
            val optionalFilter =
                key.filter?.let {
                    " AND subject IN (SELECT subject FROM $database.$DATA_TABLE WHERE ${
                        nodeToFilter(
                            it,
                            key.context
                        )
                    })"
                } ?: ""
            "SELECT $index AS index, ARRAY_AGG(subject) AS targets FROM $database.$DATA_TABLE WHERE predicate = '${RDFVocab.type}' AND object = '${key.typeUri}'$optionalFilter"
        }.joinToString(" UNION ALL ")
        return clickhouse.query(GenericQuerySpec(database, DATA_TABLE, listOf("index", "targets")), q).map { results ->
            val resultMap = results.groupBy { it["index"] as Int }
            keys.mapIndexed { index, key ->
                resultMap[index]?.firstOrNull()
                    ?.let { (it["targets"] as JsonArray).map { Target(it as String, listOf(key.typeUri)) } }
                    ?: emptyList()
            }
        }.convert().toCompletableFuture()
    }
}

class PredicateTargetSelectionLoader(private val clickhouse: ClickhouseClient, private val database: String) :
    BatchLoader<PredicateTargetSelectionKey, List<Target>> {

    override fun load(keys: List<PredicateTargetSelectionKey>): CompletionStage<List<List<Target>>> {
        Log.debug("Loading predicate targets for: ${keys.map { it.subject + " -> " + it.predicate + " (filter: ${it.filter})" }}")
        val q =
            keys.mapIndexed { index, key ->
                val optionalFilter =
                    key.filter?.let {
                        " AND subject IN (SELECT subject FROM $database.$DATA_TABLE WHERE ${
                            nodeToFilter(
                                it,
                                key.context
                            )
                        })"
                    }
                        ?: ""
                "SELECT $index AS index, ARRAY_AGG([object, types]) AS targets FROM $database.$DATA_TABLE LEFT JOIN (SELECT subject AS targetSubject, ARRAY_AGG(object) as types FROM $database.$DATA_TABLE WHERE predicate = '${RDFVocab.type}'$optionalFilter GROUP BY subject) type ON object = targetSubject WHERE predicate = '${key.predicate}' AND subject = '${key.subject}'"
            }.joinToString(" UNION ALL ")
        return clickhouse.query(GenericQuerySpec(database, DATA_TABLE, listOf("index", "targets")), q)
            .invoke { _ -> Log.debug("Completed predicate target loader query.") }.map { results ->
                val resultMap = results.groupBy { it["index"] as Int }
                keys.mapIndexed { index, key ->
                    resultMap[index]?.firstOrNull()?.let {
                        val targets = it["targets"] as JsonArray
                        targets.map {
                            it as JsonArray
                            val (id, types) = it.getString(0) to it.getJsonArray(1).map { it as String }
                            Target(id as String, types)
                        }
                    } ?: emptyList()
                }
            }.convert().toCompletableFuture()
    }

}

class PredicateValueLoader(
    private val clickhouse: ClickhouseClient,
    private val database: String,
    context: Map<String, Any>
) :
    BatchLoader<PredicateValueKey, List<Any>> {

    private val language = context[JsonLdKeywords.language] as String?

    override fun load(keys: List<PredicateValueKey>): CompletionStage<List<List<Any>>> {
        Log.debug("Loading values for keys: $keys")
        val filter = keys.groupBy { it.predicate }.toList().joinToString(
            " OR ",
            " WHERE "
        ) { (predicate, keys) ->
            val optionalLangFilter = language?.let { " AND language IN ('', '$it')" } ?: ""
            "predicate = '$predicate' AND subject IN (${keys.joinToString { "'${it.subject}'" }})$optionalLangFilter"
        }
        val q = "SELECT subject, predicate, object FROM $database.$DATA_TABLE $filter"
        return clickhouse.query(GenericQuerySpec(database, DATA_TABLE, listOf("subject", "predicate", "object")), q)
            .map { results ->
                val resultMap =
                    results.groupBy { PredicateValueKey(it["subject"] as String, it["predicate"] as String) }
                val output = keys.map { key ->
                    resultMap[key]?.map { it["object"]!! } ?: emptyList()
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