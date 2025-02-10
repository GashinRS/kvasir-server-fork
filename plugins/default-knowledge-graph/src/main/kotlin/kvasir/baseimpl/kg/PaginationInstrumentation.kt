package kvasir.baseimpl.kg

import com.google.common.hash.Hashing
import graphql.ExecutionResult
import graphql.execution.ExecutionStepInfo
import graphql.execution.FetchedValue
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetchingEnvironment
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.changes.StorageBackend
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.utils.cursors.OffsetBasedCursor
import kvasir.utils.graphql.*
import java.time.Instant
import java.util.concurrent.CompletableFuture

class PaginationInstrumentation(
    val storageBackends: Map<String?, StorageBackend>,
    val podId: String,
    val context: Map<String, Any>,
    val atTimestamp: Instant?
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
        state.environments[parameters.executionStepInfo.path.toString()] = parameters.environment
        return SimpleInstrumentationContext.noOp()
    }

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState
    ): InstrumentationContext<in Any> {
        state as PaginationInstrumentationState
        if (parameters.executionStepInfo.fieldDefinition.type.isList()) {
            val env = state.environments[parameters.executionStepInfo.path.toString()]!!
            val (pageSize, _) = env.field.getPaginationInfo()
            val outputSize = ((parameters.fetchedValue as? FetchedValue)?.fetchedValue as? List<*>)?.size
            if (outputSize != null && outputSize == pageSize) {
                return object : SimpleInstrumentationContext<Any>() {
                    override fun onCompleted(result: Any?, t: Throwable?) {
                        val count = storageBackends[env.getStorageClass()]!!.count(podId, context, atTimestamp, env)
                        state.addCountTarget(parameters.executionStepInfo, count)
                    }
                }
            }
        }
        return SimpleInstrumentationContext.noOp()
    }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState
    ): CompletableFuture<ExecutionResult> {
        return Multi.createFrom().iterable((state as PaginationInstrumentationState).state.entries)
            .onItem().transformToUni { (path, countPromise) ->
                val env = state.environments[path]!!
                val (pageSize, offset) = env.field.getPaginationInfo()
                countPromise
                    .map { totalCount ->
                        mapOf(
                            JsonLdKeywords.id to "kvasir:qr-page-info:${
                                Hashing.farmHashFingerprint64()
                                    .hashString(path, Charsets.UTF_8)
                            }",
                            "path" to path,
                            "parent" to env.getFromSource<String>("id"),
                            (if (env.executionStepInfo.path.parent.isRootPath) "class" else "predicate") to getFQName(
                                env.fieldDefinition,
                                context
                            ),
                            "totalCount" to totalCount,
                            "next" to if (offset + pageSize < totalCount) OffsetBasedCursor(offset + pageSize).encode() else null,
                            "previous" to if (offset - pageSize >= 0) OffsetBasedCursor(offset - pageSize).encode() else null
                        ).filterValues { it != null }
                    }
            }
            .merge().collect().asList()
            .map { pageData ->
                executionResult.transform { result ->
                    if (pageData.isNotEmpty()) {
                        result.extensions(mapOf(EXTENSION_ID to pageData))
                    }
                }
            }
            .convert().toCompletableFuture()
    }

}

class PaginationInstrumentationState(
    val state: MutableMap<String, Uni<Long>> = mutableMapOf(),
    val environments: MutableMap<String, DataFetchingEnvironment> = mutableMapOf()
) : InstrumentationState {

    fun addCountTarget(executionStepInfo: ExecutionStepInfo, countPromise: Uni<Long>) {
        state[executionStepInfo.path.toString()] = countPromise
    }

}