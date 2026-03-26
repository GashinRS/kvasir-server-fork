package kvasir.plugins.kg.clickhouse.graphql

import cz.jirutka.rsql.parser.ast.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper

/**
 * Converts the RSQL expression of a GraphQL filter directive into SQL.
 */
open class ToSQLFilterVisitor(
    // The JSON-LD context for the query
    private val context: JSONObject
) :
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
        val op = determineOperator(fieldPart, cmp.operator)
        val pattern = parsePattern(fieldPart, cmp)
        return when {
            pattern != null -> pattern
            op == RSQLOperators.EQUAL -> "$fieldPart = ${arguments[0]}"
            //op == RSQLOperators.NOT_EQUAL -> "${fieldPart.replace(" AND", " AND NOT")} = ${arguments[0]}" => what was the purpose of this?
            op == RSQLOperators.NOT_EQUAL -> "$fieldPart != ${arguments[0]}"
            op == RSQLOperators.GREATER_THAN -> "$fieldPart > ${arguments[0]}"
            op == RSQLOperators.GREATER_THAN_OR_EQUAL -> "$fieldPart >= ${arguments[0]}"
            op == RSQLOperators.LESS_THAN -> "$fieldPart < ${arguments[0]}"
            op == RSQLOperators.LESS_THAN_OR_EQUAL -> "$fieldPart <= ${arguments[0]}"
            op == RSQLOperators.IN -> "$fieldPart IN (${arguments.joinToString(", ")})"
            op == RSQLOperators.NOT_IN -> "$fieldPart NOT IN (${arguments.joinToString(", ")})"
            op == ARRAY_HAS_ITEM_OP -> "has($fieldPart, ${arguments[0]})"
            op == ARRAY_DOES_NOT_HAVE_ITEM_OP -> "NOT has($fieldPart, ${arguments[0]})"
            else -> throw IllegalArgumentException("Unknown operator: ${cmp.operator}")
        }
    }

    fun determineOperator(selector: String, op: ComparisonOperator): ComparisonOperator {
        return if (selector == "_types") {
            when (op) {
                RSQLOperators.EQUAL -> ARRAY_HAS_ITEM_OP
                RSQLOperators.NOT_EQUAL -> ARRAY_DOES_NOT_HAVE_ITEM_OP
                else -> throw IllegalArgumentException("Operator ${op.symbol} is not supported for _types selector.")
            }
        } else {
            op
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

    protected fun parsePattern(targetExpr: String, cmp: ComparisonNode): String? {
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
            "${sign}ilike(toString(${targetExpr}), '$likePattern')"
        } else {
            null
        }
    }
}

internal const val SELF_REF_SELECTOR = "it"

/**
 * Replaces any selector that is encountered, which matches any of the current selectors, with a new selector.
 *
 * @param throwErrorForOtherSelectors If true, the set of current selectors in combination with the new selector is treated as a whitelist, and an error is thrown if a selector is encountered that is not in this set.
 */
class SelectorReplacingFilterVisitor(val currentSelectors: Set<String>, val newSelector: String, val throwErrorForOtherSelectors: Boolean = false) :
    NoArgRSQLVisitorAdapter<Node>() {

    private val matchingSelectors = currentSelectors.plus(newSelector)

    constructor(currentSelector: String, newSelector: String, throwErrorForOtherSelectors: Boolean = false) : this(setOf(currentSelector), newSelector, throwErrorForOtherSelectors)

    override fun visit(node: AndNode): Node {
        return AndNode(node.children.map { visitNode(it) })
    }

    override fun visit(node: OrNode): Node {
        return OrNode(node.children.map { visitNode(it) })
    }

    override fun visit(node: ComparisonNode): Node {
        val selector = when {
            matchingSelectors.contains(node.selector) -> newSelector
            throwErrorForOtherSelectors -> throw IllegalArgumentException("Illegal selector for filter: '${node.selector}' not in set of allowed selectors ($matchingSelectors).")
            else -> node.selector
        }
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

/**
 * A visitor that extracts the set of selectors (field name references) in an RSQL filter expression.
 */
class SelectorExtractingVisitor : NoArgRSQLVisitorAdapter<Set<String>>() {
    override fun visit(and: AndNode): Set<String> {
        return and.flatMap { visitNode(it) }.toSet()
    }

    override fun visit(or: OrNode): Set<String> {
        return or.flatMap { visitNode(it) }.toSet()
    }

    override fun visit(node: ComparisonNode): Set<String> {
        return setOf(node.selector)
    }

    fun visitNode(node: Node): Set<String> {
        return when (node) {
            is AndNode -> visit(node)
            is OrNode -> visit(node)
            is ComparisonNode -> visit(node)
            else -> throw IllegalArgumentException("Unknown node type: $node")
        }
    }
}

private val ARRAY_HAS_ITEM_OP = ComparisonOperator("=has=", Arity.nary(1))
private val ARRAY_DOES_NOT_HAVE_ITEM_OP = ComparisonOperator("=nhas=", Arity.nary(1))