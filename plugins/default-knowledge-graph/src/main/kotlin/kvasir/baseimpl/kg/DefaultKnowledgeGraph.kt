package kvasir.baseimpl.kg

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.execution.AbortExecutionException
import graphql.execution.SubscriptionExecutionStrategy
import graphql.language.AstPrinter
import graphql.parser.Parser
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import graphql.schema.idl.*
import io.quarkus.logging.Log
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.eventbus.EventBus
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.*
import kvasir.definitions.kg.exceptions.ChangeAssertionException
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.kg.graphql.KvasirTypes
import kvasir.definitions.kg.graphql.TYPE_MUTATION
import kvasir.definitions.kg.graphql.TYPE_SUBSCRIPTION
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.cursors.OffsetBasedCursor
import kvasir.utils.graphql.RDFClassTypeResolver
import kvasir.utils.graphql.addKvasirBuiltins
import kvasir.utils.graphql.getStorageClass
import kvasir.utils.idgen.ChangeRequestId
import mutiny.zero.flow.adapters.AdaptersToFlow
import org.dataloader.DataLoaderRegistry
import org.eclipse.microprofile.reactive.messaging.Channel
import org.reactivestreams.Publisher
import java.time.Instant
import java.util.UUID

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
    private val typeRegistry: TypeRegistry,
    @Channel(Channels.CHANGE_REQUESTS_PUBLISH)
    private val changeRequestEmitter: MutinyEmitter<ChangeRequest>,
    @Channel(Channels.QUERY_REQUESTS_PUBLISH)
    private val queryRequestEventEmitter: MutinyEmitter<QueryRequestEvent>,
    private val streamingDatafetcherFactory: StreamingDatafetcherFactory,
    private val eventBus: EventBus
) : KnowledgeGraph {

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
                InvalidChangeRequestException::class.java
            ).recoverWithUni { e ->
                Log.warn("Invalid change request: $request", e)
                logFailedChangeRequest(
                    request,
                    ChangeStatusCode.VALIDATION_ERROR,
                    e.message ?: "Invalid change request"
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

    override fun query(request: QueryRequest): Multi<QueryResult> {
        val requestTimestamp = Instant.now()
        val requestId = "urn:kvasir:queries:${UUID.randomUUID()}"
        val subscribeToExecutableSchema = if (request.predefinedSchema != null) {
            setupPredefinedSchema(request)
        } else {
            buildSchema(request.podId, request.context)
                .chain { generatedSchema -> getRequestedStateAtTimestamp(request).map { it to generatedSchema } }
                .map { (atTimestamp, generatedSchema) ->
                    val codeRegistry =
                        GraphQLCodeRegistry.newCodeRegistry()
                            .defaultDataFetcher { _ -> buildDatafetcher(request, atTimestamp) }
                    codeRegistry.typeResolver(KvasirTypes.Resource, RDFClassTypeResolver(request.context))
                    codeRegistry.typeResolver(KvasirTypes.RDFNode, RDFClassTypeResolver(request.context))
                    generatedSchema.schemaBuilder.codeRegistry(codeRegistry.build()).build()
                }
        }
        return subscribeToExecutableSchema.onItem().transformToMulti { executableSchema ->
            val storageBackends = pipelineConfig.pipeline().map { pipelineConfig ->
                processors.handles().find { it.bean.beanClass.name == pipelineConfig.className() }?.get()
                    ?: throw RuntimeException("Storage backend '${pipelineConfig.className()}' not found!")
            }.filterIsInstance<StorageBackend>()
                .associateBy { it::class.qualifiedName } + mapOf(null to defaultStorageBackend)
            val build = GraphQL.newGraphQL(executableSchema)
                .subscriptionExecutionStrategy(SubscriptionExecutionStrategy())
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
                .chain { result ->
                    // First emit query request event (for auditing purposes)
                    val resultCode =
                        if (result.errors?.isNotEmpty() == true) QueryRequestStatusCode.FAILED else QueryRequestStatusCode.COMPLETED
                    queryRequestEventEmitter.send(
                        QueryRequestEvent.fromQueryRequest(
                            requestId,
                            request,
                            resultCode,
                            if (resultCode == QueryRequestStatusCode.FAILED) result.errors.first().message else null,
                            requestTimestamp
                        )
                    ).map { result }
                }
                .onFailure().recoverWithUni { err ->
                    // Emit failed query request event
                    queryRequestEventEmitter.send(
                        QueryRequestEvent.fromQueryRequest(
                            requestId,
                            request,
                            QueryRequestStatusCode.FAILED,
                            err.message,
                            requestTimestamp
                        )
                    )
                        .chain { _ -> Uni.createFrom().failure(err) }
                }
                .onItem().transformToMulti { result ->
                    when (result.getData<Any>()) {
                        is Publisher<*> -> Multi.createFrom()
                            .publisher<ExecutionResult>(AdaptersToFlow.publisher(result.getData()))
                            .map { mapExecutionResult(request, it) }
                            .onFailure().recoverWithItem { err ->
                                mapExecutionResult(
                                    request,
                                    ExecutionResult.newExecutionResult().addError(AbortExecutionException(err)).build()
                                )
                            }

                        else -> Multi.createFrom().item(mapExecutionResult(request, result))
                    }
                }
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
                typeDefinitionRegistry.addKvasirBuiltins()
                val dynamicWiringFactory = object : WiringFactory {

                    override fun getDefaultDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> {
                        return buildDatafetcher(request, atTimestamp)
                    }

                    override fun providesTypeResolver(environment: InterfaceWiringEnvironment): Boolean {
                        return true
                    }

                    override fun getTypeResolver(environment: InterfaceWiringEnvironment): TypeResolver {
                        return RDFClassTypeResolver(request.context)
                    }

                    override fun providesTypeResolver(environment: UnionWiringEnvironment): Boolean {
                        return true
                    }

                    override fun getTypeResolver(environment: UnionWiringEnvironment): TypeResolver {
                        return RDFClassTypeResolver(request.context)
                    }

                }
                val runtimeWiring =
                    RuntimeWiring.newRuntimeWiring().scalar(ExtendedScalars.Json).wiringFactory(dynamicWiringFactory)
                        .build()
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
            errors = result.errors?.map { ex -> JsonObject.mapFrom(ex).map }?.takeIf { it.isNotEmpty() },
            extensions = (result.extensions as? Map<String, Any>)?.takeIf { it.isNotEmpty() }
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

    fun buildDatafetcher(
        request: QueryRequest,
        atTimestamp: Instant? = null,
        routeToSubscriptionHandler: Boolean = true
    ): DataFetcher<Any> {
        val podId = request.podId
        val context = request.context

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

        val mutationHandler = MutationToChangeRequest(request)
        val streamingHandler = streamingDatafetcherFactory.createDatafetcher(request)

        return DataFetcher { env ->
            when {
                routeToSubscriptionHandler && env.parentType.let { it is GraphQLNamedType && it.name == TYPE_SUBSCRIPTION } -> {
                    // Handle subscriptions
                    streamingHandler.get(env)
                }

                env.parentType.let { it is GraphQLNamedType && it.name == TYPE_MUTATION } -> {
                    // Handle mutations
                    mutationHandler.add(env)
                    if (mutationHandler.isComplete(env)) {
                        changeRequestEmitter.send(mutationHandler.getChangeRequest())
                            .map { mutationHandler.changeRequestId }.convert().toCompletionStage()
                    } else {
                        mutationHandler.changeRequestId
                    }
                }

                else -> {
                    // Handle queries
                    // Determine which datafetcher to use
                    val storageClass = env.getStorageClass()
                    if (storageClass != null) {
                        datafetcherMapping[storageClass]!!.get(env)
                    } else {
                        defaultDatafetcher.get(env)
                    }
                }
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
                ChangeReportStatusEntry(Instant.now(), resultCode, errorMessage)
            ),
            sliceId = request.sliceId,
            errorMessage = errorMessage
        )
        return changeHistory.register(report).map { report }
    }

}