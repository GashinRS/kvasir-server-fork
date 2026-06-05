package kvasir.plugins.kg.clickhouse.graphql

import cz.jirutka.rsql.parser.RSQLParser
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
        // When a numeric argument is compared against a string-typed ClickHouse column, wrap the
        // selector in toFloat64() so ClickHouse can perform the comparison without a type mismatch.
        val possibleNumericFieldPart = if (isNumericArgument(cmp.arguments.firstOrNull())) "toFloat64OrNull($fieldPart)" else fieldPart
        return when {
            pattern != null -> pattern
            op == RSQLOperators.EQUAL -> "$possibleNumericFieldPart = ${arguments[0]}"
            op == RSQLOperators.NOT_EQUAL -> "$possibleNumericFieldPart != ${arguments[0]}"
            op == RSQLOperators.GREATER_THAN -> "$possibleNumericFieldPart > ${arguments[0]}"
            op == RSQLOperators.GREATER_THAN_OR_EQUAL -> "$possibleNumericFieldPart >= ${arguments[0]}"
            op == RSQLOperators.LESS_THAN -> "$possibleNumericFieldPart < ${arguments[0]}"
            op == RSQLOperators.LESS_THAN_OR_EQUAL -> "$possibleNumericFieldPart <= ${arguments[0]}"
            op == RSQLOperators.IN -> "$possibleNumericFieldPart IN (${arguments.joinToString(", ")})"
            op == RSQLOperators.NOT_IN -> "$possibleNumericFieldPart NOT IN (${arguments.joinToString(", ")})"
            op == ARRAY_HAS_ITEM_OP -> "has($fieldPart, ${arguments[0]})"
            op == ARRAY_DOES_NOT_HAVE_ITEM_OP -> "NOT has($fieldPart, ${arguments[0]})"
            op == REGEX_MATCH_OP -> "match(toString($fieldPart), ${arguments[0]})"
            op == REGEX_NOT_MATCH_OP -> "NOT match(toString($fieldPart), ${arguments[0]})"
            op == STRING_LEN_EQ_OP -> "lengthUTF8(toString($fieldPart)) = ${arguments[0]}"
            op == STRING_LEN_NEQ_OP -> "lengthUTF8(toString($fieldPart)) != ${arguments[0]}"
            op == STRING_LEN_GT_OP -> "lengthUTF8(toString($fieldPart)) > ${arguments[0]}"
            op == STRING_LEN_GTE_OP -> "lengthUTF8(toString($fieldPart)) >= ${arguments[0]}"
            op == STRING_LEN_LT_OP -> "lengthUTF8(toString($fieldPart)) < ${arguments[0]}"
            op == STRING_LEN_LTE_OP -> "lengthUTF8(toString($fieldPart)) <= ${arguments[0]}"
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

    /**
     * Returns `true` if the raw RSQL argument string represents a numeric value (integer or decimal).
     * Used to decide whether the ClickHouse column selector needs a `toFloat64()` cast to avoid
     * a NO_COMMON_TYPE error when comparing string-typed columns against numeric literals.
     */
    internal fun isNumericArgument(value: String?): Boolean {
        if (value == null) return false
        return value.toLongOrNull() != null || value.toDoubleOrNull() != null
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

// Custom RSQL operator symbols
internal const val ARRAY_HAS_ITEM_SYMBOL = "=has="
internal const val ARRAY_DOES_NOT_HAVE_ITEM_SYMBOL = "=nhas="
internal const val REGEX_MATCH_SYMBOL = "=match="
internal const val REGEX_NOT_MATCH_SYMBOL = "=nmatch="
internal const val STRING_LEN_EQ_SYMBOL = "=len="
internal const val STRING_LEN_NEQ_SYMBOL = "=nlen="
internal const val STRING_LEN_GT_SYMBOL = "=lengt="
internal const val STRING_LEN_GTE_SYMBOL = "=lenge="
internal const val STRING_LEN_LT_SYMBOL = "=lenlt="
internal const val STRING_LEN_LTE_SYMBOL = "=lenle="

private val ARRAY_HAS_ITEM_OP = ComparisonOperator(ARRAY_HAS_ITEM_SYMBOL, Arity.nary(1))
private val ARRAY_DOES_NOT_HAVE_ITEM_OP = ComparisonOperator(ARRAY_DOES_NOT_HAVE_ITEM_SYMBOL, Arity.nary(1))
private val REGEX_MATCH_OP = ComparisonOperator(REGEX_MATCH_SYMBOL, Arity.nary(1))
private val REGEX_NOT_MATCH_OP = ComparisonOperator(REGEX_NOT_MATCH_SYMBOL, Arity.nary(1))
private val STRING_LEN_EQ_OP = ComparisonOperator(STRING_LEN_EQ_SYMBOL, Arity.nary(1))
private val STRING_LEN_NEQ_OP = ComparisonOperator(STRING_LEN_NEQ_SYMBOL, Arity.nary(1))
private val STRING_LEN_GT_OP = ComparisonOperator(STRING_LEN_GT_SYMBOL, Arity.nary(1))
private val STRING_LEN_GTE_OP = ComparisonOperator(STRING_LEN_GTE_SYMBOL, Arity.nary(1))
private val STRING_LEN_LT_OP = ComparisonOperator(STRING_LEN_LT_SYMBOL, Arity.nary(1))
private val STRING_LEN_LTE_OP = ComparisonOperator(STRING_LEN_LTE_SYMBOL, Arity.nary(1))

private val CUSTOM_RSQL_OPERATORS = setOf(
    ARRAY_HAS_ITEM_OP,
    ARRAY_DOES_NOT_HAVE_ITEM_OP,
    REGEX_MATCH_OP,
    REGEX_NOT_MATCH_OP,
    STRING_LEN_EQ_OP,
    STRING_LEN_NEQ_OP,
    STRING_LEN_GT_OP,
    STRING_LEN_GTE_OP,
    STRING_LEN_LT_OP,
    STRING_LEN_LTE_OP
)

/**
 * Creates an RSQL parser with Kvasir-specific operators enabled.
 */
fun newFilterParser(): RSQLParser = RSQLParser(RSQLOperators.defaultOperators() + CUSTOM_RSQL_OPERATORS)
