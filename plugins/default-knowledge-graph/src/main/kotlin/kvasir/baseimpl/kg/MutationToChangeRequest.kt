package kvasir.baseimpl.kg

import graphql.language.*
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLArgument
import kvasir.definitions.kg.QueryRequest
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.rdf.JSONObject
import kvasir.utils.graphql.MutationInputConverter
import kvasir.utils.graphql.innerType
import kvasir.utils.idgen.ChangeRequestId

class MutationToChangeRequest(private val request: QueryRequest) {

    private val mutationFields = mutableListOf<Field>()
    val changeRequestId = ChangeRequestId.generate(request.requestingUser).encode()

    private val inserts = mutableListOf<Map<String, Any>>()
    private val deletes = mutableListOf<Map<String, Any>>()

    // Shared converter for scalar/object transformations
    private val converter = MutationInputConverter(request.context)

    // Delegate for update/set mutation compilation
    private var updateCompiler: UpdateMutationCompiler? = null

    fun add(env: DataFetchingEnvironment) {
        try {
            mutationFields.add(env.field)
            if (env.field.name.startsWith("update") || env.field.name.startsWith("set")) {
                val compiler = updateCompiler ?: UpdateMutationCompiler(request.context).also { updateCompiler = it }
                compiler.process(env)
            } else {
                val instances = env.field.arguments.associateWith { env.fieldDefinition.getArgument(it.name) }
                    .map { (argument, argumentDefinition) -> parseArgumentValue(env, argumentDefinition, argument.value) }
                    .filterNotNull().flatten()
                if (env.field.name.startsWith("add") || env.field.name.startsWith("insert")) {
                    inserts.addAll(instances)
                }
                if (env.field.name.startsWith("remove") || env.field.name.startsWith("delete")) {
                    deletes.addAll(instances)
                }
            }
        } catch (e: Throwable) {
            throw IllegalArgumentException("Failed to parse mutation field '${env.field.name}': ${e.message}", e)
        }
    }

    fun isComplete(env: DataFetchingEnvironment): Boolean {
        val mutationDefs = env.document.definitions.filterIsInstance<OperationDefinition>()
            .filter { it.operation == OperationDefinition.Operation.MUTATION }
        // When an operationName is specified, match the correct definition; otherwise fall back to the single/first one
        val operationName = env.operationDefinition?.name
        val activeMutation = if (operationName != null) {
            mutationDefs.firstOrNull { it.name == operationName }
        } else {
            mutationDefs.firstOrNull()
        }
        val totalOps = activeMutation?.selectionSet?.selections?.size
        return totalOps == mutationFields.size
    }

    fun getChangeRequest(): ChangeRequest {
        val compiled = updateCompiler?.compile()
        return ChangeRequest(
            id = changeRequestId,
            context = request.context,
            requestingUser = request.requestingUser,
            podId = request.podId,
            sliceId = request.sliceId,
            sliceTag = request.sliceTag,
            assert = compiled?.assertions.orEmpty().toMutableList(),
            with = compiled?.withQuery,
            insert = (compiled?.insertItems.orEmpty()) + inserts,
            delete = (compiled?.deleteItems.orEmpty()) + deletes
        )
    }

    /**
     * Parses a GraphQL argument value (from a mutation) into a list of JSON objects.
     * Supports variable references, object literals, and array literals.
     */
    private fun parseArgumentValue(
        env: DataFetchingEnvironment,
        argumentDefinition: GraphQLArgument,
        argValue: Value<*>
    ): List<JSONObject>? {
        return when (argValue) {
            // Separately handle variable references
            is VariableReference -> {
                val value = env.variables[argValue.name]
                when (value) {
                    is Map<*, *> -> listOf(converter.contextualizeJson(
                        @Suppress("UNCHECKED_CAST") (value as JSONObject),
                        argumentDefinition.type.innerType()
                    ))
                    is Iterable<*> -> value.map {
                        converter.contextualizeJson(
                            @Suppress("UNCHECKED_CAST") (it as JSONObject),
                            argumentDefinition.type.innerType()
                        )
                    }
                    else -> null
                }
            }

            is ObjectValue -> listOf(converter.objectValueToJSON(argValue, argumentDefinition.type.innerType()))
            is ArrayValue -> argValue.values.map {
                converter.objectValueToJSON(
                    it as ObjectValue,
                    argumentDefinition.type.innerType()
                )
            }

            else -> null
        }
    }
}

