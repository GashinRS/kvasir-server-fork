package kvasir.plugins.kg.clickhouse.persistence

import cz.jirutka.rsql.parser.ast.*
import kvasir.definitions.persistence.PersistentEntity
import kvasir.plugins.kg.clickhouse.specs.EntityWriteSpec
import kvasir.plugins.kg.clickhouse.utils.toSnakeCase

class CHFilterVisitor<T : PersistentEntity>(private val writeSpec: EntityWriteSpec<T>) :
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
        val column = toSnakeCase(node.selector)
        val sqlOp = mapOperator(node.operator)
        val pattern = parsePattern(node)
        return when {
            // Use this first is pattern was matched
            pattern != null -> pattern

            node.operator.arity.max() > 1 -> {
                val serializedArgList =
                    node.arguments.filterNotNull()
                        .joinToString(",", "(", ")") { writeSpec.getCHFilterValue(node.selector, it) }
                "$column $sqlOp $serializedArgList"
            }

            else -> {
                val rawValue =
                    node.arguments.firstOrNull() ?: throw IllegalArgumentException("Condition value cannot be null!")
                "$column $sqlOp ${writeSpec.getCHFilterValue(node.selector, rawValue)}"
            }
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
    private fun parsePattern(node: ComparisonNode): String? {
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
            "${sign}ilike(toString(${node.selector}), '$likePattern')"
        } else {
            null
        }
    }
}