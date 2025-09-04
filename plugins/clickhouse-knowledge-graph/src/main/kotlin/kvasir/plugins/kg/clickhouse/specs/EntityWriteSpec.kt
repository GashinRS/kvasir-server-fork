package kvasir.plugins.kg.clickhouse.specs

import kvasir.definitions.persistence.PersistentEntity
import kvasir.plugins.kg.clickhouse.client.ClickhouseRecord
import kvasir.plugins.kg.clickhouse.client.InsertRecordSpec
import kvasir.plugins.kg.clickhouse.utils.getColumnNames
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

class EntityWriteSpec<T : PersistentEntity>(val entityClass: KClass<T>, database: String, tableName: String) :
    InsertRecordSpec<T>(
        database,
        tableName,
        getColumnNames(entityClass)
    ) {
    override fun toRecord(t: T): ClickhouseRecord {
        t.writeTs = Instant.now()
        return ClickhouseRecord(
            listOf(t.id) +
                entityClass.memberProperties
                    .filter { it.name != "id" }
                    .sortedBy { it.name }
                    .map { property -> property.get(t) })
    }

    fun getCHFilterValue(propertyName: String, propertyValue: String): String {
        val property = entityClass.memberProperties.find { it.name == propertyName }
            ?: throw RuntimeException("No backing property found for filter on '$propertyName' in entity '${entityClass.simpleName}'")
        return when (property.returnType.jvmErasure) {
            String::class -> "'$propertyValue'"
            Instant::class -> "${Instant.parse(propertyValue).toEpochMilli()}::DateTime64"
            else -> propertyValue
        }
    }

}