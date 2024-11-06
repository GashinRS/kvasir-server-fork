package kvasir.utils.kg

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.Scalars.GraphQLBoolean
import graphql.Scalars.GraphQLID
import graphql.Scalars.GraphQLInt
import graphql.Scalars.GraphQLString
import graphql.introspection.Introspection
import graphql.schema.DataFetcher
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLList
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLUnionType
import graphql.schema.TypeResolver
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.WiringFactory
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.ChangeRequest
import kvasir.definitions.kg.ChangeResult
import kvasir.definitions.kg.KnowledgeGraph
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.QueryResult
import kvasir.definitions.kg.RDFStatement
import kvasir.definitions.kg.ReferenceLoader
import kvasir.definitions.kg.changeops.ChangeAssertionException
import kvasir.definitions.kg.changeops.InvalidTemplateException
import kvasir.definitions.reactive.skipToLast
import org.dataloader.DataLoaderRegistry
import java.time.Instant

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
    private val outboxEmitter: MutinyEmitter<ChangeResult>
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
            GraphQLArgument.newArgument().name("first").type(GraphQLInt).build(),
            GraphQLArgument.newArgument().name("skip").type(GraphQLInt).build(),
            GraphQLArgument.newArgument().name("orderBy").type(GraphQLString).build()
        )
    }

    override fun process(request: ChangeRequest): Uni<Void> {
        val start = System.currentTimeMillis()
        val changeRequestTs = Instant.now()
        val changeProcessor = ChangeProcessor(request, this, assertionCheckingParallelism)
        return changeProcessor.executeAssertions()
            .chain { _ ->
                if (request.insertFromRefs.isNotEmpty() || request.deleteFromRefs.isNotEmpty()) {
                    // Delete from external sources
                    Multi.createFrom().iterable(request.deleteFromRefs)
                        .onItem().transformToMultiAndConcatenate { ref ->
                            loadReference(request.podId, "", ref)
                        }
                        .group().intoLists().of(referenceHandlingBuffer)
                        .onItem().transformToUni { deleteTuples ->
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
                                    loadReference(request.podId, "", ref)
                                }
                                .group().intoLists().of(referenceHandlingBuffer)
                                .onItem().transformToUni { insertTuples ->
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
                            persist(
                                request.podId,
                                changeProcessor.toStatements(deleteJsonLd).map {
                                    ChangeRecord(
                                        request.id, changeRequestTs,
                                        ChangeRecordType.DELETE, it
                                    )
                                }
                            ).map { _ -> deleteJsonLd }
                                .chain { deleteJsonLd ->
                                    val insertJsonLd = changeProcessor.materializeRecords(request.insert, bindings)
                                    persist(
                                        request.podId,
                                        changeProcessor.toStatements(insertJsonLd).map {
                                            ChangeRecord(
                                                request.id, changeRequestTs,
                                                ChangeRecordType.INSERT, it
                                            )
                                        }
                                    ).map { _ -> deleteJsonLd to insertJsonLd }
                                }
                        }
                        .chain { (effectiveDeletes, effectiveInserts) ->
                            outboxEmitter.send(ChangeResult.success(request, effectiveDeletes, effectiveInserts))
                        }
                }
            }
            .onItem()
            .invoke { _ -> Log.debug("Processed change request with id '${request.id}' in ${System.currentTimeMillis() - start} ms.") }
            .onFailure(ChangeAssertionException::class.java).recoverWithUni { e ->
                Log.warn("Failed to process change request due to assertion error: $request", e)
                Uni.createFrom().voidItem()
            }
            .onFailure(
                InvalidTemplateException::class.java
            ).recoverWithUni { e ->
                Log.warn("Failed to process change request due to invalid template expression: $request", e)
                Uni.createFrom().voidItem()
            }
    }

    override fun query(request: QueryRequest): Uni<QueryResult> {
        val subscribeToExecutableSchema = if (request.predefinedSchema != null) {
            Uni.createFrom().item(setupPredefinedSchema(request))
        } else {
            buildSchema(request.podId, request.context)
                .map { generatedSchema ->
                    val codeRegistry =
                        GraphQLCodeRegistry.newCodeRegistry()
                            .defaultDataFetcher { _ -> buildDatafetcher(request.podId, request.context) }
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
            val build = GraphQL.newGraphQL(executableSchema).build()
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

    protected open fun setupPredefinedSchema(request: QueryRequest): GraphQLSchema {
        val typeDefinitionRegistry = SchemaParser().parse(request.predefinedSchema)
        typeDefinitionRegistry.addKvasirDirectives()
        val dynamicWiringFactory = object : WiringFactory {

            override fun getDefaultDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> {
                return buildDatafetcher(request.podId, request.context)
            }

            // TODO: provide type resolvers for union and interface types

        }
        val runtimeWiring = RuntimeWiring.newRuntimeWiring().wiringFactory(dynamicWiringFactory).build()
        val executableSchema =
            graphql.schema.idl.SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
        return executableSchema
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
    open fun loadReference(podId: String, targetGraph: String, reference: Map<String, Any>): Multi<RDFStatement> {
        return referenceLoaders.firstOrNull { loader -> loader.isSupported(reference) }
            ?.loadReference(podId, targetGraph, reference)
            ?: Multi.createFrom().failure(RuntimeException("Unsupported reference type: $reference"))
    }

    /**
     * Map the execution result to a query result. By default, it returns the data and errors as is.
     * Override this method when additional post-processing is required.
     */
    open fun mapExecutionResult(request: QueryRequest, result: ExecutionResult): QueryResult {
        return QueryResult(
            data = result.getData<Map<String, Any>>(),
            errors = result.errors?.map { error -> mapOf("message" to error.message) }
        )
    }

    /**
     * Persist a list of RDF statements inserts or deletes (should be provided by the concrete implementation).
     */
    abstract fun persist(podId: String, statements: List<ChangeRecord>): Uni<Void>

    /**
     * Delete an entire graph from the knowledge graph (should be provided by the concrete implementation).
     */
    abstract fun deleteGraph(podId: String, graph: String): Uni<Void>

    /**
     * Build the entry point data fetcher for a specific pod (should be provided by the concrete implementation).
     */
    abstract fun buildDatafetcher(
        podId: String,
        context: Map<String, Any>
    ): DataFetcher<Any>

    abstract fun buildUnionTypeResolver(
        podId: String,
        unionType: GraphQLUnionType,
        context: Map<String, Any>
    ): TypeResolver

    /**
     * Get the type information for a specific pod (should be provided by the concrete implementation).
     */
    abstract fun getTypeInfo(podId: String): Uni<List<KGType>>

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