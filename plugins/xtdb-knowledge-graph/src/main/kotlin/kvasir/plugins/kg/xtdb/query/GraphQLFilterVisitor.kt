package kvasir.plugins.kg.xtdb.query

import cz.jirutka.rsql.parser.ast.*

/**
 * Converts the RSQL expression of a GraphQL filter directive into SQL.
 */
class GraphQLFilterVisitor(private val rsqlExpr: String, private val predicateMapping: Map<String, String>) :
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
            "id" -> "s"
            else -> {
                val predicate = predicateMapping[cmp.selector]
                    ?: throw IllegalArgumentException("Unknown field in filter directive with expression '$rsqlExpr': ${cmp.selector}")
                "p = '$predicate' AND o"
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

    private fun visitNode(node: Node): String {
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
            else -> "'$value'"
        }
    }
}