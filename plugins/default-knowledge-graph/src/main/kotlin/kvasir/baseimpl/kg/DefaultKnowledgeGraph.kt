package kvasir.baseimpl.kg

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphqlErrorBuilder
import graphql.execution.*
import graphql.scalars.ExtendedScalars
import graphql.schema.DataFetcher
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.*
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.smallrye.reactive.messaging.kafka.KafkaRecord
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changes.*
import kvasir.definitions.kg.exceptions.ChangeAssertionException
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.kg.exceptions.InvalidTemplateException
import kvasir.definitions.kg.graphql.KvasirTypes
import kvasir.definitions.kg.graphql.TYPE_MUTATION
import kvasir.definitions.kg.graphql.TYPE_SUBSCRIPTION
import kvasir.definitions.persistence.RepositoryFactory
import kvasir.definitions.persistence.Sort
import kvasir.definitions.reactive.conditionalUni
import kvasir.definitions.reactive.skipToLast
import kvasir.plugins.messaging.kafka.Channels
import kvasir.utils.cursors.OffsetBasedCursor
import kvasir.utils.graphql.HiddenFieldVisibility
import kvasir.utils.graphql.RDFClassTypeResolver
import kvasir.utils.graphql.SliceGraphQLSchema
import kvasir.utils.idgen.ChangeRequestId
import mutiny.zero.flow.adapters.AdaptersToFlow
import org.dataloader.DataLoaderRegistry
import org.eclipse.microprofile.reactive.messaging.Channel
import org.reactivestreams.Publisher
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * This class is used to implement common logic for knowledge graph implementations, including:
 * - handling assertions, with clauses and external references for change request.
 * - generating a schema from the type info returned by the underlying storage.
 * - setting up the GraphQL framework (schema and runtime wiring) for the query requests.
 */
@ApplicationScoped
class DefaultKnowledgeGraph(
    private val evaluateAssertions: EvaluateAssertions,
    private val materializeRecords: MaterializeRecords,
    private val materializeS3References: MaterializeS3References,
    private val sliceGraphQLBasedValidator: SliceGraphQLBasedValidator,
    private val changeRecordBackend: ChangeRecordBackend,
    private val repositoryFactory: RepositoryFactory,
    private val typeRegistry: TypeRegistry,
    @Channel(Channels.CHANGES_INCOMING_PUBLISH)
    private val changeRequestEmitter: MutinyEmitter<ChangeRequest>,
    @Channel(Channels.QUERY_REQUESTS_PUBLISH)
    private val queryRequestEventEmitter: MutinyEmitter<QueryRequestEvent>,
    private val streamingDatafetcherFactory: StreamingDatafetcherFactory
) : KnowledgeGraph {

    override fun processPlain(requests: Collection<ChangeRequest>): Uni<Collection<ProcessedChange>> {
        val mappedFailures = mutableMapOf<String, Throwable>()
        // Map stateId to stats
        val mappedStats = requests.associate { it.changeId to ChangeRequestStats() }
        val targetPods = requests.map { it.podId }.distinct()
        return if (targetPods.size == 1) {
            val targetPod = targetPods.first()
            Multi.createFrom().iterable(requests)
                .onItem().transformToMultiAndConcatenate { request ->
                    // Convert the JSON-LD insert/delete to change records
                    materializeRecords.process(request)
                        // Validate the records (only applies if the change is applied to a Slice)
                        .chain { records -> sliceGraphQLBasedValidator.process(request, records).map { records } }
                        .onFailure().recoverWithUni { err ->
                            mappedFailures[request.id] = err
                            Uni.createFrom().item(emptyList())
                        }
                        .onItem().disjoint<ChangeRecord>()
                }
                .group().intoLists().of(changeRecordBackend.preferredBatchSize)
                .onItem().transformToUniAndConcatenate { batch ->
                    // Write the records to the storage backend
                    changeRecordBackend.write(targetPod, batch).onFailure().recoverWithUni { err ->
                        // Map the failure to all change requests involved in this batch
                        val involvedRequests = batch.forEach { record ->
                            mappedFailures[record.changeId] = err
                        }
                        Uni.createFrom().voidItem()
                    }
                        .invoke { _ ->
                            // Update stats
                            batch.groupBy { it.changeId }.forEach { (stateId, records) ->
                                val stats = mappedStats[stateId]!!
                                records.forEach { record ->
                                    when (record.type) {
                                        ChangeRecordType.INSERT -> stats.insertCounter.incrementAndGet()
                                        ChangeRecordType.DELETE -> stats.deleteCounter.incrementAndGet()
                                    }
                                }
                            }
                        }
                }
                .skipToLast()
                .chain { _ ->
                    // Create change reports
                    Uni.createFrom()
                        .item(requests.map { req ->
                            createChangeReport(
                                req,
                                mappedStats[req.changeId]!!,
                                mappedFailures[req.id]
                            )
                        })
                }
        } else {
            Uni.createFrom()
                .failure(IllegalArgumentException("All change requests in a plain batch must target the same pod"))
        }
    }

    override fun processStateDependent(request: ChangeRequest): Uni<ProcessedChange> {
        val stats = ChangeRequestStats()
        val hasPostAssertions = request.assert.any { it.phase == AssertionPhase.POST }
        // Evaluate PRE assertions
        return evaluateAssertions.process(request, AssertionPhase.PRE)
            .chain { _ ->
                // Convert the JSON-LD insert/delete to change records and bind with-clauses
                materializeRecords.process(request)
            }
            .chain { records ->
                // Validate the records (only applies if the change is applied to a Slice)
                sliceGraphQLBasedValidator.process(request, records).map { records }
            }
            .chain { records ->
                // Write the records to the storage backend
                changeRecordBackend.write(request.podId, records).invoke { _ ->
                    // Update stats
                    records.forEach { record ->
                        when (record.type) {
                            ChangeRecordType.INSERT -> stats.insertCounter.incrementAndGet()
                            ChangeRecordType.DELETE -> stats.deleteCounter.incrementAndGet()
                        }
                    }
                }
            }
            .chain { _ ->
                // Evaluate POST assertions (after write); failure triggers rollback
                evaluateAssertions.process(request, AssertionPhase.POST)
            }
            .wrapExceptionsAndCreateReport(request, stats, hasPostAssertions)
    }

    override fun processReferenced(request: ChangeRequest): Uni<ProcessedChange> {
        val stats = ChangeRequestStats()
        return materializeS3References.process(request)
            .group().intoLists().of(changeRecordBackend.preferredBatchSize)
            .onItem().transformToUniAndConcatenate { batch ->
                // Write the records to the storage backend
                changeRecordBackend.write(request.podId, batch).invoke { _ ->
                    // Update stats
                    batch.forEach { record ->
                        when (record.type) {
                            ChangeRecordType.INSERT -> stats.insertCounter.incrementAndGet()
                            ChangeRecordType.DELETE -> stats.deleteCounter.incrementAndGet()
                        }
                    }
                }
            }
            .skipToLast()
            // Rollback on failure as there might have been intermediary state written to the backend
            .wrapExceptionsAndCreateReport(request, stats, true)
    }

    private fun Uni<Void>.wrapExceptionsAndCreateReport(
        request: ChangeRequest,
        stats: ChangeRequestStats,
        rollback: Boolean = false
    ): Uni<ProcessedChange> {
        return this
            .map {
                createChangeReport(request, stats, null)
            }
            .onFailure().recoverWithUni { err ->
                // There might have been intermediary state written to the backend, so perform rollback if required
                conditionalUni(rollback) { rollback(ChangeRollbackRequest(request.podId, request.changeId!!)) }
                    .onFailure().recoverWithUni { _ ->
                        Log.debug("Failed to rollback change ${request.changeId} on pod ${request.podId}")
                        Uni.createFrom().voidItem()
                    }
                    .map { createChangeReport(request, stats, err) }
            }
    }

    private fun createChangeReport(
        request: ChangeRequest,
        stats: ChangeRequestStats,
        throwable: Throwable?
    ): ProcessedChange {
        val terminalState = when (throwable) {
            is ChangeAssertionException -> ChangeStatusCode.ASSERTION_FAILED
            is InvalidTemplateException -> ChangeStatusCode.VALIDATION_ERROR
            is InvalidChangeRequestException -> ChangeStatusCode.VALIDATION_ERROR
            null -> ChangeStatusCode.COMMITTED
            else -> ChangeStatusCode.INTERNAL_ERROR
        }
        val report = ProcessedChange(
            id = request.changeId!!,
            origRequestId = request.id,
            podId = request.podId,
            createdBy = request.requestingUser,
            sliceId = request.sliceId,
            sliceTag = request.sliceTag,
            nrOfInserts = stats.insertCounter.get(),
            nrOfDeletes = stats.deleteCounter.get(),
            processingHistory = listOf(
                ChangeProcessingHistoryEntry(
                    ChangeRequestId.fromId(request.id).timestamp(),
                    ChangeStatusCode.QUEUED
                ),
                ChangeProcessingHistoryEntry(
                    Instant.now(),
                    terminalState,
                    throwable?.message
                )
            ),
            // Forward associated references (if any)
            associatedReferences = request.insertFromRefs.map { AssociatedReference(it, ChangeRecordType.INSERT) } +
                    request.deleteFromRefs.map { AssociatedReference(it, ChangeRecordType.DELETE) }
        )
        return report
    }

    override fun query(request: QueryRequest): Multi<QueryResult> {
        val requestTimestamp = Instant.now()
        val requestId = "urn:kvasir:queries:${UUID.randomUUID()}"
        val subscribeToExecutableSchema = if (request.predefinedSchema != null) {
            setupPredefinedSchema(request)
        } else {
            buildSchema(request.podId, request.context)
                .chain { generatedSchema -> evaluateChangeId(request).map { it to generatedSchema } }
                .map { (atChangeId, generatedSchema) ->
                    val codeRegistry =
                        GraphQLCodeRegistry.newCodeRegistry()
                            .defaultDataFetcher { _ -> buildDatafetcher(request, atChangeId) }
                            .fieldVisibility(HiddenFieldVisibility())
                    codeRegistry.typeResolver(KvasirTypes.Resource, RDFClassTypeResolver(request.context))
                    codeRegistry.typeResolver(KvasirTypes.RDFNode, RDFClassTypeResolver(request.context))
                    VersionedGraphQLSchema(
                        generatedSchema.schemaBuilder.codeRegistry(codeRegistry.build()).build(),
                        atChangeId
                    )
                }
        }
        return subscribeToExecutableSchema.onItem().transformToMulti { executableSchema ->
            val build = GraphQL.newGraphQL(executableSchema.schema)
                .defaultDataFetcherExceptionHandler(SanitizedExceptionHandler())
                .subscriptionExecutionStrategy(SubscriptionExecutionStrategy())
                .instrumentation(
                    changeRecordBackend.instrumentation(
                        request.podId,
                        request.context,
                        executableSchema.changeId
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
                            if (request.operationName != null) {
                                this.operationName(request.operationName)
                            }
                        }
                        .query(request.query)
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
        return changeRecordBackend.stream(request)
    }

    override fun finalize(request: ChangeFinalizeRequest): Uni<Void> {
        return changeRecordBackend.finalize(request)
    }

    override fun rollback(request: ChangeRollbackRequest): Uni<Void> {
        return changeRecordBackend.rollback(request)
    }

    /**
     * Evaluate the change ID or timestamp specified in the query request to determine which state (determined by a change id) to query.
     */
    private fun evaluateChangeId(request: QueryRequest): Uni<String> {
        return when {
            request.atTimestamp != null -> repositoryFactory.getRepository(ProcessedChange::class, request.podId)
                .find(
                    filter = "writeTs<'${request.atTimestamp}' and statusCode==COMMITTED",
                    sort = Sort.descending("writeTs"),
                    limit = 1
                )
                .map { results ->
                    results.items.firstOrNull()?.id
                }

            request.atChangeId != null -> Uni.createFrom().item(request.atChangeId)

            else -> Uni.createFrom().nullItem()
        }
    }

    protected fun setupPredefinedSchema(request: QueryRequest): Uni<VersionedGraphQLSchema> {
        return evaluateChangeId(request)
            .map { atChangeId ->
                val typeDefinitionRegistry =
                    SliceGraphQLSchema(request.predefinedSchema!!, request.context).getTypeDefinitionRegistry()
                val dataFetcher = buildDatafetcher(request, atChangeId)
                val typeResolver = RDFClassTypeResolver(request.context)
                val dynamicWiringFactory = object : WiringFactory {
                    override fun getDefaultDataFetcher(environment: FieldWiringEnvironment) = dataFetcher
                    override fun providesTypeResolver(environment: InterfaceWiringEnvironment) = true
                    override fun getTypeResolver(environment: InterfaceWiringEnvironment) = typeResolver
                    override fun providesTypeResolver(environment: UnionWiringEnvironment) = true
                    override fun getTypeResolver(environment: UnionWiringEnvironment) = typeResolver
                }
                val runtimeWiring =
                    RuntimeWiring.newRuntimeWiring()
                        .scalar(ExtendedScalars.Json)
                        .scalar(ExtendedScalars.Time)
                        .scalar(ExtendedScalars.Date)
                        .scalar(ExtendedScalars.DateTime)
                        .wiringFactory(dynamicWiringFactory)
                        .build()
                val executableSchema =
                    graphql.schema.idl.SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
                val schemaWithVisibility = executableSchema.transform { builder ->
                    builder.codeRegistry(executableSchema.codeRegistry.transform { cr ->
                        cr.fieldVisibility(HiddenFieldVisibility())
                    })
                }
                VersionedGraphQLSchema(schemaWithVisibility, atChangeId)
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

    fun buildDatafetcher(
        request: QueryRequest,
        atChangeId: String? = null,
        routeToSubscriptionHandler: Boolean = true
    ): DataFetcher<Any> {
        val podId = request.podId
        val context = request.context

        val queryDataFetcher = changeRecordBackend.datafetcher(
            podId,
            context,
            atChangeId
        )

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
                        changeRequestEmitter.sendMessage(
                            KafkaRecord.of(
                                request.podId,
                                mutationHandler.getChangeRequest()
                            )
                        )
                            .map {
                                val requestId = mutationHandler.changeRequestId
                                // Qualify the request ID as a URI
                                "${request.sliceId}/changes/pending/$requestId"
                            }.convert().toCompletionStage()
                    } else {
                        val requestId = mutationHandler.changeRequestId
                        // Qualify the request ID as a URI
                        "${request.sliceId}/changes/pending/$requestId"
                    }
                }

                else -> {
                    // Handle queries
                    queryDataFetcher.get(env)
                }
            }
        }
    }

}

class SanitizedExceptionHandler : DataFetcherExceptionHandler {
    override fun handleException(handlerParameters: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
        // Log the original exception
        Log.warn("Data fetching exception: ${handlerParameters.exception.message}", handlerParameters.exception)
        val error = GraphqlErrorBuilder.newError().message(handlerParameters.exception.message)
            .path(handlerParameters.path)
            .location(handlerParameters.sourceLocation)
            .build()
        return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
    }

}

private class ChangeRequestStats(
    val insertCounter: AtomicLong = AtomicLong(0),
    val deleteCounter: AtomicLong = AtomicLong(0)
)

data class VersionedGraphQLSchema(
    val schema: GraphQLSchema,
    val changeId: String?
)