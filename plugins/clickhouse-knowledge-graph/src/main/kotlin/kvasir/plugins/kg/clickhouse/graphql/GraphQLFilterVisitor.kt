package kvasir.plugins.kg.clickhouse.graphql

import cz.jirutka.rsql.parser.ast.*
import kvasir.definitions.rdf.JsonLdHelper

/**
 * Converts the RSQL expression of a GraphQL filter directive into SQL.
 */
open class GraphQLFilterVisitor(private val context: Map<String, Any>) :
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

    override fun visit(cmp: ComparisonNode): String {
        val arguments = cmp.arguments.map { toSQLValue(it) }
        val fieldPart = cmp.selector
        val op = cmp.operator
        val pattern = parsePattern(cmp)
        return when {
            pattern != null -> pattern
            op == RSQLOperators.EQUAL -> "$fieldPart = ${arguments[0]}"
            op == RSQLOperators.NOT_EQUAL -> "${fieldPart.replace(" AND", " AND NOT")} = ${arguments[0]}"
            op == RSQLOperators.GREATER_THAN -> "$fieldPart > ${arguments[0]}"
            op == RSQLOperators.GREATER_THAN_OR_EQUAL -> "$fieldPart >= ${arguments[0]}"
            op == RSQLOperators.LESS_THAN -> "$fieldPart < ${arguments[0]}"
            op == RSQLOperators.LESS_THAN_OR_EQUAL -> "$fieldPart <= ${arguments[0]}"
            op == RSQLOperators.IN -> "$fieldPart IN (${arguments.joinToString(", ")})"
            op == RSQLOperators.NOT_IN -> "$fieldPart NOT IN (${arguments.joinToString(", ")})"
            else -> throw IllegalArgumentException("Unknown operator: ${cmp.operator}")
        }
    }

    fun visitNode(node: Node): String {
        return when (node) {
            is AndNode -> visit(node)
            is OrNode -> visit(node)
            is ComparisonNode -> visit(node)
            else -> throw IllegalArgumentException("Unknown node type: $node")
        }
    }

    protected fun toSQLValue(value: String): String {
        return when {
            value.toBooleanStrictOrNull() != null -> value
            value.toLongOrNull() != null -> value
            value.toDoubleOrNull() != null -> value
            else -> {
                // If getFQName returns null, use the original value
                val fqValue = JsonLdHelper.getFQName(value, context, ":") ?: value
                "'$fqValue'"
            }
        }
    }

    protected fun parsePattern(cmp: ComparisonNode): String? {
        val pattern = cmp.arguments.firstOrNull()
            ?.replace("%", "\\%")
            ?.replace("_", "\\_")
        return if (cmp.operator in setOf(
                RSQLOperators.EQUAL,
                RSQLOperators.NOT_EQUAL
            ) && pattern?.let { FIQL_WILDCARD_PATTERN.containsMatchIn(it) } == true
        ) {
            val sign = if (cmp.operator == RSQLOperators.NOT_EQUAL) "NOT " else ""
            val likePattern = FIQL_WILDCARD_PATTERN.replace(pattern, "%")
            "${sign}ilike(toString(${cmp.selector}), '$likePattern')"
        } else {
            null
        }
    }
}

internal const val SELF_REF_SELECTOR = "it"

class SelectorReplacingFilterVisitor(val currentSelector: String, val newSelector: String) :
    NoArgRSQLVisitorAdapter<Node>() {

    override fun visit(node: AndNode): Node {
        return AndNode(node.children.map { visitNode(it) })
    }

    override fun visit(node: OrNode): Node {
        return OrNode(node.children.map { visitNode(it) })
    }

    override fun visit(node: ComparisonNode): Node {
        val selector = if (node.selector == currentSelector) newSelector else node.selector
        return ComparisonNode(node.operator, selector, node.arguments)
    }

    fun visitNode(node: Node): Node {
        return when (node) {
            is AndNode -> visit(node)
            is OrNode -> visit(node)
            is ComparisonNode -> visit(node)
            else -> throw IllegalArgumentException("Unknown node type: $node")
        }
    }
}