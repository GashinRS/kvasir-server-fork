package kvasir.plugins.kg.clickhouse.graphql

import cz.jirutka.rsql.parser.ast.*
import kvasir.definitions.rdf.JsonLdHelper

/**
 * Converts the RSQL expression of a GraphQL filter directive into SQL.
 */
class GraphQLFilterVisitor(private val context: Map<String, Any>) :
    NoArgRSQLVisitorAdapter<String>() {
    override fun visit(and: AndNode): String {
        return and.joinToString(" AND ", "(", ")") { visitNode(it) }
    }

    override fun visit(or: OrNode): String {
        return or.joinToString(" OR ", "(", ")") { visitNode(it) }
    }

    override fun visit(cmp: ComparisonNode): String {
        val arguments = cmp.arguments.map { toSQLValue(it) }
        val fieldPart = when (cmp.selector) {
            "id" -> "subject"
            else -> {
                val predicate = JsonLdHelper.getFQName(cmp.selector, context, "_")
                "predicate = '$predicate' AND object"
            }
        }
        return when (cmp.operator) {
            RSQLOperators.EQUAL -> "$fieldPart = ${arguments[0]}"
            RSQLOperators.NOT_EQUAL -> "${fieldPart.replace(" AND", " AND NOT")} = ${arguments[0]}"
            RSQLOperators.GREATER_THAN -> "$fieldPart > ${arguments[0]}"
            RSQLOperators.GREATER_THAN_OR_EQUAL -> "$fieldPart >= ${arguments[0]}"
            RSQLOperators.LESS_THAN -> "$fieldPart < ${arguments[0]}"
            RSQLOperators.LESS_THAN_OR_EQUAL -> "$fieldPart <= ${arguments[0]}"
            RSQLOperators.IN -> "$fieldPart IN (${arguments.joinToString(", ")})"
            RSQLOperators.NOT_IN -> "$fieldPart NOT IN (${arguments.joinToString(", ")})"
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

    private fun toSQLValue(value: String): String {
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
}

class GraphQLFilterVisitor2(private val context: Map<String, Any>) :
    NoArgRSQLVisitorAdapter<String>() {
    override fun visit(and: AndNode): String {
        return and.joinToString(" AND ", "(", ")") { visitNode(it) }
    }

    override fun visit(or: OrNode): String {
        return or.joinToString(" OR ", "(", ")") { visitNode(it) }
    }

    override fun visit(cmp: ComparisonNode): String {
        val arguments = cmp.arguments.map { toSQLValue(it) }
        val fieldPart = cmp.selector
        return when (cmp.operator) {
            RSQLOperators.EQUAL -> "$fieldPart = ${arguments[0]}"
            RSQLOperators.NOT_EQUAL -> "${fieldPart.replace(" AND", " AND NOT")} = ${arguments[0]}"
            RSQLOperators.GREATER_THAN -> "$fieldPart > ${arguments[0]}"
            RSQLOperators.GREATER_THAN_OR_EQUAL -> "$fieldPart >= ${arguments[0]}"
            RSQLOperators.LESS_THAN -> "$fieldPart < ${arguments[0]}"
            RSQLOperators.LESS_THAN_OR_EQUAL -> "$fieldPart <= ${arguments[0]}"
            RSQLOperators.IN -> "$fieldPart IN (${arguments.joinToString(", ")})"
            RSQLOperators.NOT_IN -> "$fieldPart NOT IN (${arguments.joinToString(", ")})"
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

    private fun toSQLValue(value: String): String {
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

class GenericRSQLToSQLVisitor() : NoArgRSQLVisitorAdapter<String>() {
    override fun visit(and: AndNode): String {
        return and.joinToString(" AND ", "(", ")") { visitNode(it) }
    }

    override fun visit(or: OrNode): String {
        return or.joinToString(" OR ", "(", ")") { visitNode(it) }
    }

    override fun visit(node: ComparisonNode): String {
        val arguments = node.arguments.map { toSQLValue(it) }
        return when (node.operator) {
            RSQLOperators.EQUAL -> "${node.selector} = ${arguments[0]}"
            RSQLOperators.NOT_EQUAL -> "${node.selector} != ${arguments[0]}"
            RSQLOperators.GREATER_THAN -> "${node.selector} > ${arguments[0]}"
            RSQLOperators.GREATER_THAN_OR_EQUAL -> "${node.selector} >= ${arguments[0]}"
            RSQLOperators.LESS_THAN -> "${node.selector} < ${arguments[0]}"
            RSQLOperators.LESS_THAN_OR_EQUAL -> "${node.selector} <= ${arguments[0]}"
            RSQLOperators.IN -> "${node.selector} IN (${arguments.joinToString(", ")})"
            RSQLOperators.NOT_IN -> "${node.selector} NOT IN (${arguments.joinToString(", ")})"
            else -> throw IllegalArgumentException("Unknown operator: ${node.operator}")
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

    private fun toSQLValue(value: String): String {
        return when {
            value.toBooleanStrictOrNull() != null -> value
            value.toLongOrNull() != null -> value
            value.toDoubleOrNull() != null -> value
            else -> {
                "'$value'"
            }
        }
    }
}