package kvasir.plugins.kg.clickhouse.client

import idlab.obelisk.core.plugins.datastore.clickhouse.client.ClickhouseClientConfig
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.buffer.Buffer
import io.vertx.mutiny.ext.web.client.HttpRequest
import io.vertx.mutiny.ext.web.client.WebClient
import jakarta.enterprise.context.ApplicationScoped

private const val CLICKHOUSE_USER_HEADER = "X-ClickHouse-User"
private const val CLICKHOUSE_PASSWORD_HEADER = "X-ClickHouse-Key"

@ApplicationScoped
class ClickhouseClient(
    vertx: Vertx,
    private val clickhouseConfig: ClickhouseClientConfig
) {

    private val httpClient = WebClient.create(
        vertx,
        WebClientOptions().setDefaultHost(clickhouseConfig.host()).setDefaultPort(clickhouseConfig.port())
            .setDecompressionSupported(true)
    )

    init {
        Log.debug(
            "Initialized Clickhouse DataStore client, connecting to ${clickhouseConfig.host()}:${clickhouseConfig.port()} ${
                clickhouseConfig.user().map { " with user '$it'" }.orElse("")
            }"
        )
    }

    fun <S : InsertRecordSpec<T>, T> insert(spec: S, batch: List<T>): Uni<Void> {
        val requestBody = batch.joinToString(separator = " ") { record ->
            spec.toRecord(record).encode()
        }
        return httpClient
            .post("/")
            .addAuthHeaders(clickhouseConfig)
            .addQueryParam("database", spec.database)
            .addQueryParam(
                "query",
                "INSERT INTO ${spec.table} (${spec.columns.joinToString()}) FORMAT JSONCompactEachRow"
            )
            .addQueryParam("date_time_input_format", "best_effort")
            .sendBuffer(Buffer.buffer(requestBody))
            .chain { response ->
                if (response.statusCode() !in (200..399)) {
                    Uni.createFrom()
                        .failure { RuntimeException("Could not process Clickhouse store request: \n${response.bodyAsString()}") }
                } else {
                    Uni.createFrom().voidItem()
                }
            }
    }

    fun <S : QuerySpec<T, *>, T> query(spec: S, sql: String): Uni<List<T>> {
        Log.debug("Executing Clickhouse select query: $sql")
        return httpClient.get("/")
            .putHeader("X-ClickHouse-Format", "JSONCompact")
            .addAuthHeaders(clickhouseConfig)
            .addQueryParam("database", spec.database)
            .addQueryParam("date_time_input_format", "best_effort")
            .addQueryParam("date_time_output_format", "iso")
            .addQueryParam("query", sql).send()
            .chain { response ->
                if (response.statusCode() in 200..399) {
                    val results = response.bodyAsJsonObject().getJsonArray("data")
                    Uni.createFrom().item(results.map { record ->
                        spec.fromRecord(record as ClickhouseRecord)
                    })
                } else {
                    Uni.createFrom()
                        .failure { RuntimeException("Failed to execute Clickhouse query '$sql': ${response.bodyAsString()}") }
                }
            }
    }

    fun execute(sql: String, database: String? = null): Uni<Void> {
        Log.debug("Executing Clickhouse SQL statement: $sql")
        return httpClient.post("/").putHeader("X-ClickHouse-Format", "JSONCompact")
            .addAuthHeaders(clickhouseConfig)
            .apply {
                database?.let { addQueryParam("database", it) } ?: this
            }
            .addQueryParam("date_time_input_format", "best_effort")
            .sendBuffer(Buffer.buffer(sql))
            .chain { response ->
                if (response.statusCode() in 200..399) {
                    Uni.createFrom().voidItem()
                } else {
                    Uni.createFrom()
                        .failure { RuntimeException("Failed to execute Clickhouse query '$sql': ${response.bodyAsString()}") }
                }
            }
    }

}

typealias ClickhouseRecord = JsonArray

abstract class InsertRecordSpec<T>(val database: String, val table: String, val columns: List<String>) {
    abstract fun toRecord(t: T): ClickhouseRecord
}

abstract class QuerySpec<T, F>(val database: String, val table: String, val selectedFields: List<F>) {
    abstract fun fromRecord(record: ClickhouseRecord): T
}

internal fun HttpRequest<Buffer>.addAuthHeaders(config: ClickhouseClientConfig): HttpRequest<Buffer> {
    config.user().ifPresent { putHeader(CLICKHOUSE_USER_HEADER, it) }
    config.password().ifPresent { putHeader(CLICKHOUSE_PASSWORD_HEADER, it) }
    return this
}