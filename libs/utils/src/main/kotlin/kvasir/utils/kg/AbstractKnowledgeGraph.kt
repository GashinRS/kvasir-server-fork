package kvasir.utils.kg

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.Scalars.*
import graphql.execution.instrumentation.Instrumentation
import graphql.introspection.Introspection
import graphql.schema.*
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.WiringFactory
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import io.vertx.core.json.JsonObject
import kvasir.definitions.kg.*
import kvasir.definitions.kg.changeops.ChangeAssertionException
import kvasir.definitions.kg.changeops.InvalidTemplateException
import kvasir.definitions.reactive.skipToLast
import org.dataloader.DataLoaderRegistry
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * This class is used to implement common logic for knowledge graph implementations, including:
 * - handling assertions, with clauses and external references for change request.
 * - generating a schema from the type info returned by the underlying storage.
 * - setting up the GraphQL framework (schema and runtime wiring) for the query requests.
 */
abstract class AbstractKnowledgeGraph(
    private val referenceLoaders: List<ReferenceLoader>,
    private val assertionCheckingParallelism: Int,
    private val referenceHandlingBuffer: Int,
    private val outboxEmitter: MutinyEmitter<ChangeReport>
) : KnowledgeGraph {

    companion object {
        val DEFAULT_PAGE_SIZE = 100

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
    }

    override fun process(request: ChangeRequest): Uni<Void> {
        val start = System.currentTimeMillis()
        val changeRequestTs = Instant.now()
        val changeProcessor = ChangeProcessor(request, this, assertionCheckingParallelism)
        val nrOfInserts = AtomicLong(0)
        val nrOfDeletes = AtomicLong(0)
        return changeProcessor.executeAssertions()
            .chain { _ ->
                if (request.insertFromRefs.isNotEmpty() || request.deleteFromRefs.isNotEmpty()) {
                    // Delete from external sources
                    Multi.createFrom().iterable(request.deleteFromRefs)
                        .onItem().transformToMultiAndConcatenate { ref ->
                            loadReference(request.podId, ref)
                        }
                        .group().intoLists().of(referenceHandlingBuffer)
                        .onItem().transformToUni { deleteTuples ->
                            nrOfDeletes.addAndGet(deleteTuples.size.toLong())
                            persist(
                                request.podId,
                                deleteTuples.map {
                                    ChangeRecord(
                                        request.id,
                                        changeRequestTs,
                                        ChangeRecordType.DELETE,
                                        it
                                    )
                                })
                        }
                        .concatenate()
                        .skipToLast()
                        .chain { _ ->
                            // Insert from external sources
                            Multi.createFrom().iterable(request.insertFromRefs)
                                .onItem().transformToMultiAndConcatenate { ref ->
                                    loadReference(request.podId, ref)
                                }
                                .group().intoLists().of(referenceHandlingBuffer)
                                .onItem().transformToUni { insertTuples ->
                                    nrOfInserts.addAndGet(insertTuples.size.toLong())
                                    persist(
                                        request.podId,
                                        insertTuples.map {
                                            ChangeRecord(
                                                request.id,
                                                changeRequestTs,
                                                ChangeRecordType.INSERT,
                                                it
                                            )
                                        }
                                    )
                                }
                                .concatenate()
                                .skipToLast()
                        }
                } else {
                    // Execute embedded inserts/deletes
                    changeProcessor.bindWhere()
                        .chain { bindings ->
                            // Delete the specified records
                            val deleteJsonLd = changeProcessor.materializeRecords(request.delete, bindings)
                            val deleteStatements = changeProcessor.toStatements(deleteJsonLd)
                            nrOfDeletes.addAndGet(deleteStatements.size.toLong())
                            persist(
                                request.podId,
                                deleteStatements.map {
                                    ChangeRecord(
                                        request.id, changeRequestTs,
                                        ChangeRecordType.DELETE, it
                                    )
                                }
                            )
                                .chain { _ ->
                                    val insertJsonLd = changeProcessor.materializeRecords(request.insert, bindings)
                                    val insertStatements = changeProcessor.toStatements(insertJsonLd)
                                    nrOfInserts.addAndGet(insertStatements.size.toLong())
                                    persist(
                                        request.podId,
                                        insertStatements.map {
                                            ChangeRecord(
                                                request.id, changeRequestTs,
                                                ChangeRecordType.INSERT, it
                                            )
                                        }
                                    )
                                }
                        }
                }
            }
            .onItem()
            .transformToUni { _ ->
                val report = ChangeReport(
                    id = request.id,
                    podId = request.podId,
                    timestamp = changeRequestTs,
                    resultCode = ChangeResultCode.COMMITTED,
                    sliceId = request.sliceId,
                    nrOfInserts = nrOfInserts.get(),
                    nrOfDeletes = nrOfDeletes.get()
                )
                logChangeReport(request.podId, report).map { report }
            }
            .invoke { _ -> Log.debug("Processed change request with id '${request.id}' in ${System.currentTimeMillis() - start} ms.") }
            .onFailure(ChangeAssertionException::class.java).recoverWithUni { e ->
                Log.warn("Failed to process change request due to assertion error: $request", e)
                logFailedChangeRequest(
                    changeRequestTs,
                    request,
                    ChangeResultCode.ASSERTION_FAILED,
                    e.message ?: "Assertion failed"
                )
            }
            .onFailure(
                InvalidTemplateException::class.java
            ).recoverWithUni { e ->
                Log.warn("Failed to process change request due to invalid template expression: $request", e)
                logFailedChangeRequest(
                    changeRequestTs,
                    request,
                    ChangeResultCode.VALIDATION_ERROR,
                    e.message ?: "Invalid template expression"
                )
            }
            .onFailure().recoverWithUni { e ->
                Log.error("Failed to process change request: $request", e)
                logFailedChangeRequest(
                    changeRequestTs,
                    request,
                    ChangeResultCode.INTERNAL_ERROR,
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
                            buildUnionTypeResolver(request.podId, unionType, request.context)
                        )
                    }
                    generatedSchema.schemaBuilder.codeRegistry(codeRegistry.build()).build()
                }
        }
        return subscribeToExecutableSchema.chain { executableSchema ->
            val build = GraphQL.newGraphQL(executableSchema).instrumentation(
                provideInstrumentation(
                    request.podId,
                    request.context,
                    request.atTimestamp
                )
            ).build()
            Uni.createFrom().future(
                build.executeAsync(
                    ExecutionInput.newExecutionInput()
                        .dataLoaderRegistry(buildDataLoaderRegistry(request.podId, request.context))
                        .apply {
                            if (request.variables != null) {
                                this.variables(request.variables)
                            }
                        }
                        .query(request.query)
                        .build()
                )
            )
                .map { result -> mapExecutionResult(request, result) }
        }
    }

    private fun getRequestedStateAtTimestamp(request: QueryRequest): Uni<Instant> {
        return when {
            request.atTimestamp != null -> Uni.createFrom().item(request.atTimestamp)
            request.atChangeRequestId != null -> getChange(
                ChangeHistoryRequest(
                    request.podId,
                    changeRequestId = request.atChangeRequestId
                )
            )
                .map { change -> change?.timestamp }

            else -> Uni.createFrom().nullItem()
        }
    }

    protected open fun setupPredefinedSchema(request: QueryRequest): Uni<GraphQLSchema> {
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

    open fun buildSchema(podId: String, context: Map<String, Any>): Uni<SchemaGeneratorResult> {
        return getTypeInfo(podId)
            .map { typeInfo -> SchemaGenerator(typeInfo, context).process() }
    }

    /**
     * Build the data loader registry for a specific pod. By default, it returns an empty registry.
     * Override this method to provide a custom implementation (i.e. when using data loaders).
     */
    open fun buildDataLoaderRegistry(podId: String, context: Map<String, Any>): DataLoaderRegistry {
        return DataLoaderRegistry()
    }

    /**
     * Load a reference from an external source and return it as a Mutiny stream (Multi).
     */
    open fun loadReference(podId: String, reference: Map<String, Any>): Multi<RDFStatement> {
        return referenceLoaders.firstOrNull { loader -> loader.isSupported(reference) }
            ?.loadReference(podId, reference)
            ?: Multi.createFrom().failure(RuntimeException("Unsupported reference type: $reference"))
    }

    /**
     * Map the execution result to a query result. By default, it returns the data and errors as is.
     * Override this method when additional post-processing is required.
     */
    open fun mapExecutionResult(request: QueryRequest, result: ExecutionResult): QueryResult {
        return QueryResult(
            data = result.getData<Map<String, Any>>(),
            errors = result.errors?.map { ex -> JsonObject.mapFrom(ex).map },
            extensions = result.extensions as? Map<String, Any>
        )
    }

    /**
     * Persist a list of RDF statements inserts or deletes (should be provided by the concrete implementation).
     */
    abstract fun persist(podId: String, statements: List<ChangeRecord>): Uni<Void>

    /**
     * Log a change report (should be provided by the concrete implementation).
     */
    abstract fun logChangeReport(podId: String, changeReport: ChangeReport): Uni<Void>

    /**
     * Delete an entire graph from the knowledge graph (should be provided by the concrete implementation).
     */
    abstract fun deleteGraph(podId: String, graph: String): Uni<Void>

    /**
     * Build the entry point data fetcher for a specific pod (should be provided by the concrete implementation).
     */
    abstract fun buildDatafetcher(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): DataFetcher<Any>

    abstract fun buildUnionTypeResolver(
        podId: String,
        unionType: GraphQLUnionType,
        context: Map<String, Any>
    ): TypeResolver

    abstract fun provideInstrumentation(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): Instrumentation

    /**
     * Get the type information for a specific pod (should be provided by the concrete implementation).
     */
    abstract fun getTypeInfo(podId: String): Uni<List<KGType>>

    private fun logFailedChangeRequest(
        timestamp: Instant,
        request: ChangeRequest,
        resultCode: ChangeResultCode,
        errorMessage: String
    ): Uni<ChangeReport> {
        val report = ChangeReport(
            id = request.id,
            podId = request.podId,
            timestamp = timestamp,
            resultCode = resultCode,
            sliceId = request.sliceId,
            errorMessage = errorMessage
        )
        return logChangeReport(
            request.podId,
            report
        ).map { report }
    }

}

data class MetadataEntry(
    val typeUri: String,
    val propertyUri: String,
    val propertyKind: KGPropertyKind,
    val propertyRef: String
)

data class KGType(
    val uri: String,
    val properties: List<KGProperty>
)

data class KGProperty(
    val uri: String,
    val kind: KGPropertyKind,
    val typeRefs: Set<String>
)

enum class KGPropertyKind {
    Literal, IRI
}