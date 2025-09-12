package kvasir.utils.rdf

import kvasir.definitions.rdf.XSDVocab
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetTime
import java.time.format.DateTimeParseException

object RDFLiteralUtils {

    /**
     * Get the value of an RDF Literal as a Java compatible primitive (if not supported, the string representation is used).
     */
    fun getCompatibleRawValue(literalValue: String, datatype: String): Any {
        return when (datatype) {
            XSDVocab.int, XSDVocab.integer -> literalValue.toIntOrNull()
            XSDVocab.double, XSDVocab.decimal -> literalValue.toDoubleOrNull()
            XSDVocab.float -> literalValue.toFloatOrNull()
            XSDVocab.long -> literalValue.toLongOrNull()
            XSDVocab.boolean -> literalValue.toBoolean()
            XSDVocab.dateTime -> tryAlternatives(
                { Instant.parse(literalValue) },
                {
                    // The datatime string is maybe missing a 'Z'?
                    Instant.parse("${literalValue}Z")
                }
            )

            XSDVocab.date -> tryAlternatives(
                { LocalDate.parse(literalValue) }
            )

            XSDVocab.time -> tryAlternatives(
                { LocalTime.parse(literalValue) },
                { OffsetTime.parse(literalValue) }
            )

            else -> null
        } ?: literalValue
    }

    private fun tryAlternatives(vararg alternatives: () -> Any?): Any? {
        for (alternative in alternatives) {
            try {
                val result = alternative()
                return result
            } catch (_: Throwable) {
                // ignore and try next
            }
        }
        // If none of the alternatives worked, return null
        return null
    }

}