package kvasir.baseimpl.kg

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.Scalars.*
import graphql.TypeResolutionEnvironment
import graphql.introspection.Introspection
import graphql.language.AstPrinter
import graphql.parser.Parser
import graphql.schema.*
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.WiringFactory
import io.quarkus.logging.Log
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import kvasir.definitions.kg.*
import kvasir.definitions.kg.exceptions.InvalidTemplateException
import kvasir.definitions.kg.TypeRegistry
import kvasir.definitions.kg.changes.*
import kvasir.definitions.messaging.Channels
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.reactive.skipToLast
import kvasir.utils.cursors.OffsetBasedCursor
import kvasir.utils.graphql.addKvasirDirectives
import kvasir.utils.graphql.getStorageClass
import kvasir.utils.idgen.ChangeRequestId
import org.dataloader.DataLoaderRegistry
import org.eclipse.microprofile.reactive.messaging.Channel
import java.time.Instant

@ConfigMapping(prefix = "kvasir.changes.processing")
interface ChangeRequestPipelineConfig {

    fun pipeline(): List<ChangeRequestPipelineProcessorConfig>

}

interface ChangeRequestPipelineProcessorConfig {

    fun className(): String

    @WithDefault("false")
    fun defaultStorage(): Boolean

}

/**
 * This class is used to implement common logic for knowledge graph implementations, including:
 * - handling assertions, with clauses and external references for change request.
 * - generating a schema from the type info returned by the underlying storage.
 * - setting up the GraphQL framework (schema and runtime wiring) for the query requests.
 */
@ApplicationScoped
class DefaultKnowledgeGraph(
    @Channel(Channels.OUTBOX_PUBLISH)
    private val outboxEmitter: MutinyEmitter<ChangeReport>,
    private val changeHistory: ChangeHistory,
    private val changeRequestTxBufferFactory: ChangeRequestTxBufferFactory,
    private val pipelineConfig: ChangeRequestPipelineConfig,
    private val processors: Instance<ChangeProcessor>,
    private val typeRegistry: TypeRegistry
) : KnowledgeGraph {

    companion object {

        val typeDirective = GraphQLDirective.newDirective().name("type").validLocations(
            Introspection.DirectiveLocation.INTERFACE,
            Introspection.DirectiveLocation.OBJECT
        )
            .argument(GraphQLArgument.newArgument().name("iri").type(GraphQLString).build()).build()
        val predicateDirective =
            GraphQLDirective.newDirective().name("predicate").validLocation(Introspection.DirectiveLocation.FIELD)
                .argument(GraphQLArgument.newArgument().name("iri").type(GraphQLString).build())
                .argument(GraphQLArgument.newArgument().name("reverse").type(GraphQLBoolean).build()).build()
        val optionalDirective =
            GraphQLDirective.newDirective().name("optional").validLocation(Introspection.DirectiveLocation.FIELD)
                .build()
        val filterDirective =
            GraphQLDirective.newDirective().name("filter").validLocation(Introspection.DirectiveLocation.FIELD)
                .argument(GraphQLArgument.newArgument().name("if").type(GraphQLString).build()).build()
        val defaultRelationArguments = listOf(
            GraphQLArgument.newArgument().name("id").type(GraphQLList.list(GraphQLID)).build(),
            GraphQLArgument.newArgument().name("pageSize").type(GraphQLInt).defaultValueProgrammatic(DEFAULT_PAGE_SIZE)
                .build(),
            GraphQLArgument.newArgument().name("cursor").type(GraphQLString).build(),
            GraphQLArgument.newArgument().name("orderBy").type(GraphQLList.list(GraphQLString)).build()
        )
        val graphDirective =
            GraphQLDirective.newDirective().name("graph").validLocations(
                Introspection.DirectiveLocation.QUERY
            )
                .argument(GraphQLArgument.newArgument().name("iri").type(GraphQLList.list(GraphQLString)).build())
                .build()

        val storageDirective = GraphQLDirective.newDirective().name("storage").validLocations(
            Introspection.DirectiveLocation.FIELD
        )
            .argument(GraphQLArgument.newArgument().name("class").type(GraphQLString).build()).build()

    }

    val defaultStorageBackend = (pipelineConfig.pipeline().find { it.defaultStorage() }?.let {
        processors.handles().find { backend -> backend.bean.beanClass.name == it.className() }
            ?.let { instanceHandle -> instanceHandle.get() as StorageBackend }
            ?: throw RuntimeException("Configured default storage backend '${it.className()}' not found!")
    } ?: processors.handles().map { it.get() }.filterIsInstance<StorageBackend>().firstOrNull())
        ?: throw RuntimeException("No storage backend found!")

    override fun process(request: ChangeRequest): Uni<Void> {
        val start = System.currentTimeMillis()
        val pipeline = pipelineConfig.pipeline().map { pipelineConfig ->
            processors.handles().find { it.bean.beanClass.name == pipelineConfig.className() }?.get()
                ?: throw RuntimeException("Change Request pipeline processor '${pipelineConfig.className()}' not found!")
        }
        return changeRequestTxBufferFactory.open(request)
            .chain { txBuffer ->
                Multi.createFrom().iterable(pipeline)
                    .onItem().transformToUni { processor -> processor.process(txBuffer) }
                    .concatenate().skipToLast().map { txBuffer }
            }
            .chain { txBuffer ->
                txBuffer.statistics().chain { stats ->
                    val report = ChangeReport(
                        id = request.id,
                        podId = request.podId,
                        statusEntry = listOf(
                            ChangeReportStatusEntry(
                                ChangeRequestId.fromId(request.id).timestamp(),
                                ChangeStatusCode.QUEUED
                            ),
                            ChangeReportStatusEntry(Instant.now(), ChangeStatusCode.COMMITTED),
                        ),
                        sliceId = request.sliceId,
                        nrOfInserts = stats.nrOfInserts,
                        nrOfDeletes = stats.nrOfDeletes
                    )
                    changeHistory.register(report).map { report }
                }
            }
            .invoke { _ -> Log.debug("Processed change request with id '${request.id}' in ${System.currentTimeMillis() - start} ms.") }
            .onFailure(ChangeAssertionException::class.java).recoverWithUni { e ->
                Log.warn("Failed to process change request due to assertion error: $request", e)
                logFailedChangeRequest(
                    request,
                    ChangeStatusCode.ASSERTION_FAILED,
                    e.message ?: "Assertion failed"
                )
            }
            .onFailure(
                InvalidTemplateException::class.java
            ).recoverWithUni { e ->
                Log.warn("Failed to process change request due to invalid template expression: $request", e)
                logFailedChangeRequest(
                    request,
                    ChangeStatusCode.VALIDATION_ERROR,
                    e.message ?: "Invalid template expression"
                )
            }
            .onFailure().recoverWithUni { e ->
                Log.error("Failed to process change request: $request", e)
                logFailedChangeRequest(
                    request,
                    ChangeStatusCode.INTERNAL_ERROR,
                    e.message ?: "An unexpected error occurred while processing the change request: '${e.message}'"
                )
            }
            .onItem().transformToUni { report -> outboxEmitter.send(report) }
    }

    override fun query(request: QueryRequest): Uni<QueryResult> {
        val subscribeToExecutableSchema = if (request.predefinedSchema != null) {
            setupPredefinedSchema(request)
        } else {
            buildSchema(request.podId, request.context)
                .chain { generatedSchema -> getRequestedStateAtTimestamp(request).map { it to generatedSchema } }
                .map { (atTimestamp, generatedSchema) ->
                    val codeRegistry =
                        GraphQLCodeRegistry.newCodeRegistry()
                            .defaultDataFetcher { _ -> buildDatafetcher(request.podId, request.context, atTimestamp) }
                    generatedSchema.unionTypes.forEach { unionType ->
                        codeRegistry.typeResolver(
                            unionType,
                            RDFClassTypeResolver(request.context)
                        )
                    }
                    generatedSchema.schemaBuilder.codeRegistry(codeRegistry.build()).build()
                }
        }
        return subscribeToExecutableSchema.chain { executableSchema ->
            val storageBackends = pipelineConfig.pipeline().map { pipelineConfig ->
                processors.handles().find { it.bean.beanClass.name == pipelineConfig.className() }?.get()
                    ?: throw RuntimeException("Storage backend '${pipelineConfig.className()}' not found!")
            }.filterIsInstance<StorageBackend>()
                .associateBy { it::class.qualifiedName } + mapOf(null to defaultStorageBackend)
            val build = GraphQL.newGraphQL(executableSchema)
                .instrumentation(
                    PaginationInstrumentation(
                        storageBackends,
                        request.podId,
                        request.context,
                        request.atTimestamp
                    )
                )
                .build()
            Uni.createFrom().future(
                build.executeAsync(
                    ExecutionInput.newExecutionInput()
                        .dataLoaderRegistry(buildDataLoaderRegistry(request.podId, request.context))
                        .apply {
                            if (request.variables != null) {
                                this.variables(request.variables)
                            }
                        }
                        .query(getPreprocessedQuery(request))
                        .build()
                )
            )
                .map { result -> mapExecutionResult(request, result) }
        }
    }

    override fun getChangeRecords(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>> {
        // Fetch result by composing the change records of all the registered Storage Backends
        val offset = request.cursor?.let { OffsetBasedCursor.fromString(it) }?.offset ?: 0
        val pageSize = request.pageSize
        // TODO: provide an efficient implementation instead of fetching all results and then skipping based on offset.
        return streamChangeRecords(request.copy(cursor = null))
            .skip().first(offset)
            .select().first(pageSize.toLong() + 1)
            .collect().asList()
            .map { result ->
                PagedResult(
                    items = result.take(pageSize),
                    nextCursor = if (result.size > pageSize) OffsetBasedCursor(offset + pageSize).encode() else null,
                    previousCursor = (offset - pageSize).takeIf { it >= 0 }?.let { OffsetBasedCursor(it).encode() })
            }
    }

    override fun streamChangeRecords(request: ChangeRecordRequest): Multi<ChangeRecord> {
        return Multi.createFrom().iterable(processors.filterIsInstance<StorageBackend>())
            .onItem()
            .transformToMultiAndConcatenate { backend -> backend.stream(request) }
    }

    override fun rollback(request: ChangeRollbackRequest): Uni<Void> {
        // Rollback the change by executing a rollback on all the registered Storage Backends
        return Multi.createFrom().iterable(processors.filterIsInstance<StorageBackend>())
            .onItem()
            .transformToUniAndMerge { backend -> backend.rollback(request) }
            .skipToLast()
    }

    private fun getRequestedStateAtTimestamp(request: QueryRequest): Uni<Instant> {
        return when {
            request.atTimestamp != null -> Uni.createFrom().item(request.atTimestamp)
            request.atChangeRequestId != null -> changeHistory.get(
                ChangeHistoryRequest(
                    request.podId,
                    changeRequestId = request.atChangeRequestId
                )
            )
                .map { change -> change?.statusEntry?.find { it.code == ChangeStatusCode.COMMITTED }?.timestamp }

            else -> Uni.createFrom().nullItem()
        }
    }

    protected fun setupPredefinedSchema(request: QueryRequest): Uni<GraphQLSchema> {
        return getRequestedStateAtTimestamp(request)
            .map { atTimestamp ->
                val typeDefinitionRegistry = SchemaParser().parse(request.predefinedSchema)
                typeDefinitionRegistry.addKvasirDirectives()
                val dynamicWiringFactory = object : WiringFactory {

                    override fun getDefaultDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> {
                        return buildDatafetcher(request.podId, request.context, atTimestamp)
                    }

                    // TODO: provide type resolvers for union and interface types

                }
                val runtimeWiring = RuntimeWiring.newRuntimeWiring().wiringFactory(dynamicWiringFactory).build()
                val executableSchema =
                    graphql.schema.idl.SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
                executableSchema
            }
    }

    fun buildSchema(podId: String, context: Map<String, Any>): Uni<SchemaGeneratorResult> {
        return typeRegistry.getTypeInfo(podId)
            .map { typeInfo -> SchemaGenerator(typeInfo, context).process() }
    }

    /**
     * Build the data loader registry for a specific pod. By default, it returns an empty registry.
     * Override this method to provide a custom implementation (i.e. when using data loaders).
     */
    fun buildDataLoaderRegistry(podId: String, context: Map<String, Any>): DataLoaderRegistry {
        return DataLoaderRegistry()
    }

    /**
     * Map the execution result to a query result. By default, it returns the data and errors as is.
     * Override this method when additional post-processing is required.
     */
    fun mapExecutionResult(request: QueryRequest, result: ExecutionResult): QueryResult {
        return QueryResult(
            data = result.getData<Map<String, Any>>(),
            errors = result.errors?.map { ex -> JsonObject.mapFrom(ex).map },
            extensions = result.extensions as? Map<String, Any>
        )
    }

    private fun getPreprocessedQuery(request: QueryRequest): String {
        val pipeline = pipelineConfig.pipeline().map { pipelineConfig ->
            processors.handles().find { it.bean.beanClass.name == pipelineConfig.className() }?.get()
                ?: throw RuntimeException("Change Request pipeline processor '${pipelineConfig.className()}' not found!")
        }.filterIsInstance<StorageBackend>()

        var queryDoc = Parser.parse(request.query)
        pipeline.forEach { backend ->
            queryDoc = backend.prepareQuery(request, queryDoc)
        }
        val processedQuery = AstPrinter.printAst(queryDoc)
        return processedQuery
    }

    private fun buildDatafetcher(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): DataFetcher<Any> {
        // Retrieve an ordered list of the available datafetchers (backends that do not provide a datafetcher are filtered out)
        val datafetcherMapping = pipelineConfig.pipeline().map { pipelineConfig ->
            processors.handles().find { it.bean.beanClass.name == pipelineConfig.className() }?.get()
                ?: throw RuntimeException("Storage backend '${pipelineConfig.className()}' not found!")
        }.filterIsInstance<StorageBackend>()
            .map { backend -> backend::class.qualifiedName to backend.datafetcher(podId, context, atTimestamp) }
            .filter { it.second != null }.toMap()

        val defaultDatafetcher = defaultStorageBackend.datafetcher(
            podId,
            context,
            atTimestamp
        ) ?: throw RuntimeException("The default storage backend must provide a non-null datafetcher!")

        return DataFetcher { env ->
            // Determine which datafetcher to use
            val storageClass = env.getStorageClass()
            if (storageClass != null) {
                datafetcherMapping[storageClass]!!.get(env)
            } else {
                defaultDatafetcher.get(env)
            }
        }
    }

    private fun logFailedChangeRequest(
        request: ChangeRequest,
        resultCode: ChangeStatusCode,
        errorMessage: String
    ): Uni<ChangeReport> {
        val report = ChangeReport(
            id = request.id,
            podId = request.podId,
            statusEntry = listOf(
                ChangeReportStatusEntry(ChangeRequestId.fromId(request.id).timestamp(), ChangeStatusCode.QUEUED),
                ChangeReportStatusEntry(Instant.now(), resultCode)
            ),
            sliceId = request.sliceId,
            errorMessage = errorMessage
        )
        return changeHistory.register(report).map { report }
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

