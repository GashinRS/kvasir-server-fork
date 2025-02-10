package kvasir.utils.rdf

import kvasir.definitions.rdf.XSDVocab
import java.time.Instant
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
            XSDVocab.boolean -> literalValue.toBooleanStrictOrNull()
            XSDVocab.dateTime -> {
                try {
                    Instant.parse(literalValue)
                } catch (err: DateTimeParseException) {
                    // The datatime string is maybe missing a 'Z'?
                    Instant.parse("${literalValue}Z")
                }
            }

            else -> null
        } ?: literalValue
    }
}