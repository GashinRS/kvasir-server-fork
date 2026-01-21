package kvasir.plugins.kg.clickhouse.persistence

import cz.jirutka.rsql.parser.ast.*
import kvasir.definitions.persistence.PersistentEntity
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

class CHFilterVisitor<T : PersistentEntity>(private val entityClass: KClass<T>) :
    NoArgRSQLVisitorAdapter<String>() {
    companion object {
        private val FIQL_WILDCARD_PATTERN = Regex("(?<!\\\\)\\*")
    }

    override fun visit(and: AndNode): String {
        return and.joinToString(" AND ", "(", ")") { visitNode(it) }
    }

    override fun visit(or: OrNode): String {
        return or.joinToString(" OR ", "(", ")") { visitNode(it) }
    }

    fun visitNode(node: Node): String {
        return when (node) {
            is AndNode -> visit(node)
            is OrNode -> visit(node)
            is ComparisonNode -> visit(node)
            else -> throw IllegalArgumentException("Unknown node type: $node")
        }
    }

    override fun visit(node: ComparisonNode): String {
        val valueFetcher = mapSelector(entityClass, node.selector)
        val sqlOp = mapOperator(node.operator)
        val pattern = parsePattern(valueFetcher, node)
        return when {
            // Use this first is pattern was matched
            pattern != null -> pattern

            node.operator.arity.max() > 1 -> {
                val serializedArgList =
                    node.arguments.filterNotNull()
                        .joinToString(",", "(", ")") { mapValue(node.selector, it) }
                "$valueFetcher $sqlOp $serializedArgList"
            }

            else -> {
                val rawValue =
                    node.arguments.firstOrNull() ?: throw IllegalArgumentException("Condition value cannot be null!")
                "$valueFetcher $sqlOp ${mapValue(node.selector, rawValue)}"
            }
        }
    }

    private fun mapValue(propertyName: String, propertyValue: String): String {
        val property = entityClass.memberProperties.find { it.name == propertyName }
            ?: throw RuntimeException("No backing property found for filter on '$propertyName' in entity '${entityClass.simpleName}'")
        return when (property.returnType.jvmErasure) {
            String::class -> "'$propertyValue'"
            Instant::class -> "${Instant.parse(propertyValue).toEpochMilli()}::DateTime64"
            else -> propertyValue
        }
    }

    private fun mapOperator(operator: ComparisonOperator): String {
        return when (operator) {
            RSQLOperators.EQUAL -> "="
            RSQLOperators.NOT_EQUAL -> "!="
            RSQLOperators.GREATER_THAN -> ">"
            RSQLOperators.GREATER_THAN_OR_EQUAL -> ">="
            RSQLOperators.LESS_THAN -> "<"
            RSQLOperators.LESS_THAN_OR_EQUAL -> "<="
            RSQLOperators.IN -> "IN"
            RSQLOperators.NOT_IN -> "NOT IN"
            else -> throw IllegalArgumentException("Operator '$operator' is not supported for Clickhouse backed repositories!")
        }
    }

    /**
     * Try to parse values IF operator is EQUALS or NOT EQUALS AND the pattern contains a *.
     * If so: a parsed string is returned.
     */
    private fun parsePattern(valueFetcher: String, node: ComparisonNode): String? {
        val pattern = node.arguments.firstOrNull()
        return if (node.operator in setOf(
                RSQLOperators.EQUAL,
                RSQLOperators.NOT_EQUAL,
            ) && pattern?.let {
                FIQL_WILDCARD_PATTERN.containsMatchIn(it as CharSequence)
            } == true
        ) {
            val sign = if (node.operator == RSQLOperators.NOT_EQUAL) "NOT " else ""
            val likePattern = FIQL_WILDCARD_PATTERN.replace(pattern as CharSequence, "%")
            "${sign}ilike(toString($valueFetcher), '$likePattern')"
        } else {
            null
        }
    }
}

internal fun mapSelector(entityClass: KClass<*>, selector: String): String {
    return when (selector) {
        "id" -> "id"
        "writeTs" -> "write_ts"
        else -> {
            val property = entityClass.memberProperties.find { it.name == selector }
                ?: throw RuntimeException("No backing property found for filter on '$selector' in entity '${entityClass.simpleName}'")
            val propertyType = property.returnType.jvmErasure
            val valueExtractorFunction = when (propertyType) {
                Boolean::class -> "simpleJSONExtractBool"
                Int::class, Long::class -> "simpleJSONExtractInt"
                Double::class, Float::class -> "simpleJSONExtractFloat"
                else -> "simpleJSONExtractString"
            }
            if (propertyType == Instant::class) {
                "toDateTime64($valueExtractorFunction(json_representation, '$selector')::String, 3)"
            } else {
                "$valueExtractorFunction(json_representation, '$selector')"
            }
        }
    }
}