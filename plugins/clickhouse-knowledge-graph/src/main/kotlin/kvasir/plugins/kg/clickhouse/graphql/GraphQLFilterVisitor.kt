package kvasir.plugins.kg.clickhouse.graphql

import cz.jirutka.rsql.parser.ast.*
import graphql.language.Field
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
                val fqValue = JsonLdHelper.getFQName(value, context, ":")
                "'$fqValue'"
            }
        }
    }
}

class FieldRefFilterVisitor(private val field: Field) : NoArgRSQLVisitorAdapter<Node>() {

    companion object {
        private const val SELF_REF = "it"
    }

    override fun visit(node: AndNode): Node {
        return AndNode(node.children.map { visitNode(it) })
    }

    override fun visit(node: OrNode): Node {
        return OrNode(node.children.map { visitNode(it) })
    }

    override fun visit(node: ComparisonNode): Node {
        val selector = if (node.selector == SELF_REF) field.name else node.selector
        return ComparisonNode(node.operator, selector, node.arguments)
    }

    private fun visitNode(node: Node): Node {
        return when (node) {
            is AndNode -> visit(node)
            is OrNode -> visit(node)
            is ComparisonNode -> visit(node)
            else -> throw IllegalArgumentException("Unknown node type: $node")
        }
    }
}