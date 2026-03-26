package kvasir.plugins.kg.clickhouse.graphql

import com.google.common.hash.Hashing
import graphql.ExecutionResult
import graphql.execution.FetchedValue
import graphql.execution.ResultPath
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.Field
import graphql.schema.DataFetchingEnvironment
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.plugins.kg.clickhouse.graphql.resolver.COUNT
import kvasir.utils.cursors.OffsetBasedCursor
import kvasir.utils.graphql.aliasOrName
import kvasir.utils.graphql.getFQName
import kvasir.utils.graphql.getFromSource
import kvasir.utils.graphql.getPaginationInfo
import java.util.concurrent.CompletableFuture

class PaginationInstrumentation(
    val podId: String,
    val context: Map<String, Any>
) : SimplePerformantInstrumentation() {

    companion object {
        const val EXTENSION_ID = "pagination"
    }

    override fun createState(parameters: InstrumentationCreateStateParameters?): InstrumentationState? {
        return PaginationInstrumentationState()
    }

    override fun beginFieldFetch(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState
    ): InstrumentationContext<in Any> {
        state as PaginationInstrumentationState
        state.environments[parameters.executionStepInfo.path] = parameters.environment
        return SimpleInstrumentationContext.noOp()
    }

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState
    ): InstrumentationContext<in Any> {
        state as PaginationInstrumentationState
        val env = state.environments[parameters.executionStepInfo.path]!!
        val selectionSet = parameters.executionStepInfo.field.singleField.selectionSet?.selections
        val fetchedValue = (parameters.fetchedValue as? FetchedValue)?.fetchedValue
        if (env.executionStepInfo.path.parent.isRootPath && parameters.executionStepInfo.field.singleField.getPaginationInfo(
                env.variables
            ) != null
        ) {
            when (fetchedValue) {
                is Iterable<*> -> fetchedValue.firstOrNull()?.let { instance ->
                    instance as Map<*, *>
                    state.counts[parameters.executionStepInfo.path] = instance[COUNT].toString().toLong()
                }

                is Map<*, *> -> state.counts[parameters.executionStepInfo.path] =
                    fetchedValue[COUNT].toString().toLong()
            }
        }
        selectionSet?.filterIsInstance<Field>()?.filter { it.getPaginationInfo(env.variables) != null }?.forEach { field ->
            when (fetchedValue) {
                is Iterable<*> -> fetchedValue.forEachIndexed { index, instance ->
                    instance as Map<*, *>
                    state.counts[parameters.executionStepInfo.path.segment(index)
                        .segment(field.aliasOrName())] =
                        instance["${COUNT}_${field.aliasOrName()}"].toString().toLong()
                }

                is Map<*, *> -> state.counts[parameters.executionStepInfo.path.segment(field.aliasOrName())] =
                    fetchedValue["${COUNT}_${field.aliasOrName()}"].toString().toLong()
            }
        }
        return SimpleInstrumentationContext.noOp()
    }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState
    ): CompletableFuture<ExecutionResult> {
        state as PaginationInstrumentationState
        val pageDate = state.counts
            .map { (path, totalCount) ->
                val env = state.environments[path]!!
                val pageInfo = env.field.getPaginationInfo(env.variables)
                mapOf(
                    JsonLdKeywords.id to "kvasir:qr-page-info:${
                        Hashing.farmHashFingerprint64()
                            .hashString(path.toString(), Charsets.UTF_8)
                    }",
                    "path" to path.toString(),
                    "parent" to env.getFromSource<String>("id"),
                    (if (env.executionStepInfo.path.parent.isRootPath) "class" else "predicate") to try {
                        getFQName(
                            env.fieldDefinition,
                            context
                        )
                    } catch (ex: IllegalArgumentException) {
                        null
                    },
                    "totalCount" to totalCount,
                    "next" to pageInfo?.let { (pageSize, offset) ->
                        if (offset + pageSize < totalCount) OffsetBasedCursor(
                            offset + pageSize
                        ).encode() else null
                    },
                    "previous" to pageInfo?.let { (pageSize, offset) ->
                        if (offset - pageSize >= 0) OffsetBasedCursor(
                            offset - pageSize
                        ).encode() else null
                    }
                ).filterValues { it != null }
            }
        return CompletableFuture.completedFuture(executionResult.transform { result ->
            if (pageDate.isNotEmpty()) {
                result.extensions(mapOf(EXTENSION_ID to pageDate))
            }
        })
    }

}

class PaginationInstrumentationState(
    val counts: MutableMap<ResultPath, Long> = mutableMapOf(),
    val environments: MutableMap<ResultPath, DataFetchingEnvironment> = mutableMapOf()
) : InstrumentationState