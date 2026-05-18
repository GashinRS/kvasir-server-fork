package kvasir.definitions.kg.changes

import graphql.execution.instrumentation.Instrumentation
import graphql.schema.DataFetcher
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.*

/**
 * Interface defining a KG storage backend.
 */
interface ChangeRecordBackend : ChangeRecordSink {

    /**
     * Fetch the change records persisted to this storage backend for a specific change request.
     */
    fun get(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>>

    /**
     * Stream the change records persisted to this storage backend for a specific change request.
     */
    fun stream(request: ChangeRecordRequest): Multi<ChangeRecord>

    fun finalize(request: ChangeFinalizeRequest): Uni<Void>

    /**
     * Rollback the specified change request for this storage backend
     */
    fun rollback(request: ChangeRollbackRequest): Uni<Void>

    /**
     * Provide a GraphQL datafetcher for this storage backend (used for executing queries).
     */
    fun datafetcher(
        podId: String,
        context: Map<String, Any>,
        atChangeId: String?
    ): DataFetcher<Any>

    /**
     * Provide GraphQL instrumentation to be used when executing queries against this storage backend (e.g. for pagination support).
     */
    fun instrumentation(
        podId: String,
        context: Map<String, Any>,
        atChangeId: String?
    ): Instrumentation

}

interface ChangeRecordBackendLifecycleManager {

    fun initializeForPod(podId: String): Uni<Void>

    fun cleanupForPod(podId: String): Uni<Void>

}

data class EntryPointRelation(val fromSubject: String, val relationPredicate: String)