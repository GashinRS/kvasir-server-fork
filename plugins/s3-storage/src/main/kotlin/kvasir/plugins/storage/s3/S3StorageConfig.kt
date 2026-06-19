package kvasir.plugins.storage.s3

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import kvasir.definitions.storage.StorageEventType
import java.util.*

@ConfigMapping(prefix = "kvasir.storage.s3")
interface S3StorageConfig {
    fun endpoint(): String
    fun accessKey(): String
    fun secretKey(): String
    fun region(): String

    /**
     * Maximum number of concurrent HTTP connections from the storage-api reverse proxy to the S3 backend.
     * Raise this value when running high concurrency workloads to avoid connection pool exhaustion.
     */
    fun proxyPoolSize(): Int

    /**
     * Maximum size of a single HTTP chunk (in bytes) used by the Vert.x reverse-proxy client when
     * streaming data to/from S3. Set to -1 to use the Vert.x default (8192 bytes).
     * Larger values (e.g. 65536) reduce allocation overhead for large files but add overhead for
     * small payloads.
     */
    @WithDefault("-1")
    fun proxyMaxChunkSize(): Int

    /**
     * Socket receive buffer size (in bytes) for the Vert.x reverse-proxy client connections to S3.
     * Set to -1 to use the OS default.
     */
    @WithDefault("-1")
    fun proxyReceiveBufferSize(): Int

    /**
     * Socket send buffer size (in bytes) for the Vert.x reverse-proxy client connections to S3.
     * Set to -1 to use the OS default.
     */
    @WithDefault("-1")
    fun proxySendBufferSize(): Int

    /**
     * Configure which storage event types that should be published to Kafka.
     * E.g. for auditing purposes, all event types will be included. For the auto-ingest-rdf feature to work,
     *  at least all mutation type events should be included (default).
     */
    fun publishEventTypes(): Optional<List<StorageEventType>>
}
