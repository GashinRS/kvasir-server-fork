package kvasir.plugins.kg.clickhouse.specs

import com.fasterxml.jackson.annotation.JsonProperty
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kvasir.definitions.persistence.PersistentEntity
import kvasir.plugins.kg.clickhouse.client.ClickhouseRecord
import kvasir.plugins.kg.clickhouse.client.QuerySpec
import kvasir.plugins.kg.clickhouse.utils.getColumnNames
import kvasir.plugins.kg.clickhouse.utils.toSnakeCase
import java.time.Instant
import kotlin.math.exp
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.jvmErasure

/**
 * Query specification for ClickHouse entities, allowing to map ClickHouse records to PersistentEntity instances.
 *
 * @param entityClass The class of the PersistentEntity returned by the query.
 * @param tableName The name of the ClickHouse table to query.
 */
class EntityQuerySpec<T : PersistentEntity>(
    val entityClass: KClass<T>,
    database: String,
    tableName: String
) :
    QuerySpec<T, String>(database, tableName, getColumnNames(entityClass)) {
    override fun fromRecord(record: ClickhouseRecord): T {
        val instanceMap = selectedFields.mapIndexed { index, field ->
            val property = entityClass.memberProperties.find { toSnakeCase(it.name) == field }
                ?: throw RuntimeException("No backing property found for column '$field' in entity '${entityClass.simpleName}'")
            try {
                property as KMutableProperty<*>
                val value = fromCHValue(record.getValue(index), property.returnType)
                val key = property.javaGetter?.getAnnotation(JsonProperty::class.java)?.value ?: property.name
                key to value
            } catch (e: Throwable) {
                throw IllegalArgumentException(
                    "Error setting property '${property.name}' of type '${property.returnType}' on entity '${entityClass.simpleName}': ${e.message}",
                    e
                )
            }
        }.toMap()
        return JsonObject(instanceMap).mapTo(entityClass.java)
    }

    fun fromCHValue(value: Any?, expectedType: KType): Any? {
        if (expectedType.isMarkedNullable && value == "") {
            return null
        }
        if (value == null) {
            return null
        }
        return when (expectedType.jvmErasure) {
            Instant::class -> Instant.parse(value as String)
            Long::class -> (value as String).toLong()
            Map::class -> (Json.decodeValue(value as String) as JsonObject).map
            Collection::class, Set::class, List::class -> JsonArray(value as String).list
            else -> value
        }
    }

}