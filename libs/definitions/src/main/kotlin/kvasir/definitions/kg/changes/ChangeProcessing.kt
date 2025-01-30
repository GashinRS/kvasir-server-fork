package kvasir.definitions.kg.changes

import graphql.language.Document
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import kvasir.definitions.kg.*
import java.time.Instant

/**
 * Interface defining a KG storage backend.
 * A KG may be backed by multiple storage backends (e.g. to provide optimized storage for specific types of data)
 *
 * A StorageBackend is also part of the ChangeRequest processing chain (thus extending ProcessingHook).
 * The implementation of the process function is expected to persist ChangeRecords that are relevant for the storage
 * backend, and then remove these from the transaction buffer, giving StorageBackends later in the chain the
 * opportunity to persist the remaining records.
 */
interface StorageBackend : ChangeProcessor {

    /**
     * Fetch the change records persisted to this storage backend for a specific change request.
     */
    fun get(request: ChangeRecordRequest): Uni<PagedResult<ChangeRecord>>

    /**
     * Stream the change records persisted to this storage backend for a specific change request.
     */
    fun stream(request: ChangeRecordRequest): Multi<ChangeRecord>

    /**
     * Rollback the specified change request for this storage backend
     */
    fun rollback(request: ChangeRollbackRequest): Uni<Void>

    /**
     * Allows the StorageBackend to modify a GraphQL query before it is executed by the Knowledge Graph.
     * E.g. the storage backend can use this to add @storage directives to signal which parts of the query path
     * that should be handled by its respective datafetcher.
     */
    fun prepareQuery(request: QueryRequest, queryDocument: Document): Document

    /**
     * Provide a GraphQL datafetcher for this storage backend (used for executing queries).
     * This implementation will only be used if the Knowledge Graph executor encounters a @storage directive
     * for which the id matches this specific storage backend.
     *
     * Return null to indicate that this backend does not participate in the fetching process.
     */
    fun datafetcher(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?
    ): DataFetcher<Any>?

    fun count(
        podId: String,
        context: Map<String, Any>,
        atTimestamp: Instant?,
        env: DataFetchingEnvironment
    ): Uni<Long>

}

/**
 * This interface represents a ChangeRequest shared workspace buffer.
 * When the buffer is created, a collection of ChangeRecords are materialized for the ChangeRequest,
 * taking into account with-clauses, insertFrom/deleteFrom S3 references, etc, specified in the request.
 *
 * This buffer is then passed along a processing chain before the change request is finalized and committed.
 * Each subsequent processor in the chain can add or remove records from the buffer, thus allowing various
 * transformations and operations to take place in a deterministic fashion.
 *
 * For example:
 * - Transformation processing functions can transform data in the request by removing individual records
 * and replacing these with different entries.
 * - Storage backend sink functions can persist the available records and then delete these records from the buffer
 *   (to prevent other sink functions from also persisting these records).
 */
interface ChangeRequestTxBuffer {

    /**
     * Reference to the original change request
     */
    val request: ChangeRequest

    /**
     * Timestamp at which request processing started
     */
    val requestTimestamp: Instant

    /**
     * Stream the records currently in the buffer
     */
    fun stream(
        filterByType: ChangeRecordType? = null,
        filterBySubject: String? = null,
        filterByPredicate: String? = null,
        filterByGraph: String? = null
    ): Multi<ChangeRecord>

    /**
     * Add change records (representing an RDF statement insert or delete) from the buffer.
     */
    fun add(records: List<ChangeRecord>): Uni<Void>

    /**
     * Remove change records (representing an RDF statement insert or delete) from the buffer.
     *
     * @param stored Boolean indicating if the record was stored by the processor that is removing it from the buffer.
     */
    fun remove(records: List<ChangeRecord>, stored: Boolean): Uni<Void>

    /**
     * Collect statistics about this buffer, e.g. total number of insert/delete records.
     * Useful for reporting, etc.
     */
    fun statistics(): Uni<ChangeRequestTxBufferStatistics>

    /**
     * Cleanup the resources hold by the buffer
     */
    fun destroy(): Uni<Void>

}

data class ChangeRequestTxBufferStatistics(val nrOfInserts: Long, val nrOfDeletes: Long)

/**
 * Interface for creating a ChangeRequest transaction buffer.
 * Provides the technology binding for a concrete implementation (e.g. in-memory or database backed).
 */
interface ChangeRequestTxBufferFactory {

    fun open(request: ChangeRequest): Uni<ChangeRequestTxBuffer>

}

/**
 * Interface representing a component in the change request processing chain.
 *
 * Can be a Kvasir native processing step, or an adapter implementation that integrates external processing.
 */
interface ChangeProcessor {

    /**
     * Perform the processing implemented by this Hook.
     *
     * @param buffer Reference to the ChangeRequestTxBuffer which allows fetching the queued Change Records and manipulating these.
     * @return A Uni representing the future execution of the operation. Completing with a Void means the processing was successful.
     */
    fun process(buffer: ChangeRequestTxBuffer): Uni<Void>

}