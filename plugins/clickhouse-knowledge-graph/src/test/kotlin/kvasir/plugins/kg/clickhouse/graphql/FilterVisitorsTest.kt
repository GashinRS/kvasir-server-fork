package kvasir.plugins.kg.clickhouse.graphql

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class FilterVisitorsTest {

    private val visitor = ToSQLFilterVisitor(emptyMap())

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("customOperatorCases")
    fun `translates all custom operators to SQL`(expression: String, expectedSql: String) {
        val parsed = newFilterParser().parse(expression)
        val sql = visitor.visitNode(parsed)
        assertEquals(normalize(expectedSql), normalize(sql))
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("numericCastCases")
    fun `wraps selector in toFloat64 for numeric arguments`(expression: String, expectedSql: String) {
        val parsed = newFilterParser().parse(expression)
        val sql = visitor.visitNode(parsed)
        assertEquals(normalize(expectedSql), normalize(sql))
    }

    @Test
    fun `isNumericArgument returns true for integers and decimals`() {
        assertTrue(visitor.isNumericArgument("42"))
        assertTrue(visitor.isNumericArgument("0"))
        assertTrue(visitor.isNumericArgument("-7"))
        assertTrue(visitor.isNumericArgument("3.14"))
        assertTrue(visitor.isNumericArgument("1.0"))
        assertTrue(visitor.isNumericArgument("100"))
    }

    @Test
    fun `isNumericArgument returns false for strings and null`() {
        assertFalse(visitor.isNumericArgument(null))
        assertFalse(visitor.isNumericArgument("Alice"))
        assertFalse(visitor.isNumericArgument("true"))
        assertFalse(visitor.isNumericArgument("http://example.org/foo"))
        assertFalse(visitor.isNumericArgument(""))
    }

    private fun normalize(sql: String): String = sql
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        @JvmStatic
        fun customOperatorCases(): Stream<Arguments> = Stream.of(
            Arguments.of("labels$ARRAY_HAS_ITEM_SYMBOL work", "has(labels, 'work')"),
            Arguments.of("labels$ARRAY_DOES_NOT_HAVE_ITEM_SYMBOL work", "NOT has(labels, 'work')"),
            Arguments.of("name$REGEX_MATCH_SYMBOL'^A.+$'", "match(toString(name), '^A.+$')"),
            Arguments.of("name$REGEX_NOT_MATCH_SYMBOL'^A.+$'", "NOT match(toString(name), '^A.+$')"),
            Arguments.of("name$STRING_LEN_EQ_SYMBOL 5", "lengthUTF8(toString(name)) = 5"),
            Arguments.of("name$STRING_LEN_NEQ_SYMBOL 5", "lengthUTF8(toString(name)) != 5"),
            Arguments.of("name$STRING_LEN_GT_SYMBOL 5", "lengthUTF8(toString(name)) > 5"),
            Arguments.of("name$STRING_LEN_GTE_SYMBOL 5", "lengthUTF8(toString(name)) >= 5"),
            Arguments.of("name$STRING_LEN_LT_SYMBOL 5", "lengthUTF8(toString(name)) < 5"),
            Arguments.of("name$STRING_LEN_LTE_SYMBOL 5", "lengthUTF8(toString(name)) <= 5")
        )

        @JvmStatic
        fun numericCastCases(): Stream<Arguments> = Stream.of(
            // Numeric arguments → toFloat64() cast on selector
            Arguments.of("score==42", "toFloat64OrNull(score) = 42"),
            Arguments.of("score!=42", "toFloat64OrNull(score) != 42"),
            Arguments.of("score>10", "toFloat64OrNull(score) > 10"),
            Arguments.of("score>=10", "toFloat64OrNull(score) >= 10"),
            Arguments.of("score<100", "toFloat64OrNull(score) < 100"),
            Arguments.of("score<=100", "toFloat64OrNull(score) <= 100"),
            Arguments.of("ratio>=1.5", "toFloat64OrNull(ratio) >= 1.5"),
            // String arguments → no cast, plain field reference
            Arguments.of("name==Alice", "name = 'Alice'"),
            Arguments.of("name!=Bob", "name != 'Bob'"),
            Arguments.of("status==active", "status = 'active'")
        )
    }
}
