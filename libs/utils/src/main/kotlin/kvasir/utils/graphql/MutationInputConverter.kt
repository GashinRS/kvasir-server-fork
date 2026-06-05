package kvasir.utils.graphql

import graphql.Scalars
import graphql.language.*
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLScalarType
import kvasir.definitions.kg.graphql.ARG_REVERSE_NAME
import kvasir.definitions.kg.graphql.DIRECTIVE_PREDICATE_NAME
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.rdf.RDFTransformer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.OffsetTime

/**
 * Shared conversion utilities for transforming GraphQL mutation input values
 * (both AST [Value] nodes and deserialized JSON variables) into JSON-LD objects
 * suitable for inclusion in a [kvasir.definitions.kg.changes.ChangeRequest].
 *
 * Used by both insert/delete and update/set mutation compilation.
 */
class MutationInputConverter(private val context: JSONObject) {

    /**
     * Resolves a raw ID value (AST [StringValue] or plain [String]) to a fully qualified,
     * validated IRI paired with the JSON-LD `@id` keyword.
     */
    fun toIDReference(rawValue: Any): Pair<String, String> {
        val rawValueStr = if (rawValue is StringValue) rawValue.value else rawValue as String
        val ref = JsonLdHelper.getFQName(rawValueStr, context) ?: rawValueStr
        return JsonLdKeywords.id to RDFTransformer.ensureValidAbsoluteIri(ref)
    }

    // ── AST Value → JSON-LD ────────────────────────────────────────────

    /**
     * Converts a single GraphQL AST [Value] to its JSON-LD representation.
     * Delegates to [convertScalar] for scalars and [objectValueToJSON] for nested objects.
     */
    fun singleValueToJSON(rawValue: Value<*>, fieldDefinition: GraphQLInputObjectField): Any {
        return if (rawValue is ScalarValue<*>) {
            val scalarType = fieldDefinition.type.innerType<GraphQLScalarType>()
            convertScalar(rawValue, scalarType)
        } else {
            objectValueToJSON(rawValue as ObjectValue, fieldDefinition.type.innerType())
        }
    }

    /**
     * Converts a GraphQL [ObjectValue] AST node to a JSON-LD map, resolving field names
     * to fully qualified IRIs and handling `@reverse` predicates.
     */
    fun objectValueToJSON(objectValue: ObjectValue, type: GraphQLInputObjectType): JSONObject {
        val typeFqName = getFQName(type, context)
        return objectValue.objectFields.associate { field ->
            val fieldDefinition = type.getField(field.name)
            val rawValue = field.value
            if (field.name == FIELD_ID_NAME) {
                toIDReference(rawValue)
            } else {
                val reverse =
                    fieldDefinition.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_REVERSE_NAME)
                        ?.getValue<Boolean>() ?: false
                val output = getFQName(type.getField(field.name), context) to if (rawValue is ArrayValue) {
                    rawValue.values.map { singleValueToJSON(it, fieldDefinition) }
                } else {
                    singleValueToJSON(rawValue, fieldDefinition)
                }
                if (reverse) {
                    JsonLdKeywords.reverse to mapOf(output)
                } else {
                    output
                }
            }
        }.plus(JsonLdKeywords.type to typeFqName)
    }

    /**
     * Converts a GraphQL AST [ScalarValue] to its JSON-LD representation using the target [GraphQLScalarType].
     */
    fun convertScalar(value: ScalarValue<*>, type: GraphQLScalarType): Any {
        return when {
            type == Scalars.GraphQLID -> mapOf(toIDReference(value))
            type == Scalars.GraphQLBoolean && value is BooleanValue -> value.isValue
            type == Scalars.GraphQLFloat && value is FloatValue -> value.value
            type == Scalars.GraphQLInt && value is IntValue -> value.value
            type == Scalars.GraphQLString && value is StringValue -> value.value
            type.name == ExtendedScalars.DateTime.name && value is StringValue -> mapOf(
                JsonLdKeywords.type to XSDVocab.dateTime,
                JsonLdKeywords.value to value.value
            )
            type.name == ExtendedScalars.Date.name && value is StringValue -> mapOf(
                JsonLdKeywords.type to XSDVocab.date,
                JsonLdKeywords.value to value.value
            )
            type.name == ExtendedScalars.Time.name && value is StringValue -> mapOf(
                JsonLdKeywords.type to XSDVocab.time,
                JsonLdKeywords.value to value.value
            )
            else -> throw IllegalArgumentException("Unsupported scalar value type: ${value.javaClass}")
        }
    }

    // ── Deserialized JSON variable → JSON-LD ───────────────────────────

    /**
     * Converts a single deserialized variable value to its JSON-LD representation.
     * Delegates to [convertJSONScalar] for scalars and [contextualizeJson] for nested objects.
     */
    fun contextualizeSingleValue(rawValue: Any, fieldDefinition: GraphQLInputObjectField): Any {
        return if (rawValue is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            contextualizeJson(rawValue as JSONObject, fieldDefinition.type.innerType())
        } else {
            val scalarType = fieldDefinition.type.innerType<GraphQLScalarType>()
            convertJSONScalar(rawValue, scalarType)
        }
    }

    /**
     * Contextualizes a deserialized JSON map (from a GraphQL variable) into a JSON-LD object,
     * resolving field names to fully qualified IRIs and handling `@reverse` predicates.
     */
    fun contextualizeJson(value: JSONObject, type: GraphQLInputObjectType): JSONObject {
        val typeFqName = getFQName(type, context)
        return value.entries.associate { (fieldName, fieldValue) ->
            val fieldDefinition = type.getField(fieldName)
            if (fieldName == FIELD_ID_NAME) {
                toIDReference(fieldValue)
            } else {
                val reverse =
                    fieldDefinition.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_REVERSE_NAME)
                        ?.getValue<Boolean>() ?: false
                val output = getFQName(type.getField(fieldName), context) to if (fieldValue is Iterable<*>) {
                    fieldValue.map { contextualizeSingleValue(it!!, fieldDefinition) }
                } else {
                    contextualizeSingleValue(fieldValue, fieldDefinition)
                }
                if (reverse) {
                    JsonLdKeywords.reverse to mapOf(output)
                } else {
                    output
                }
            }
        }.plus(JsonLdKeywords.type to typeFqName)
    }

    /**
     * Converts a deserialized JSON scalar value to its JSON-LD representation using the target [GraphQLScalarType].
     */
    fun convertJSONScalar(value: Any, type: GraphQLScalarType): Any {
        return when {
            type == Scalars.GraphQLID -> mapOf(toIDReference(value))
            type == Scalars.GraphQLBoolean && value is Boolean -> value
            type == Scalars.GraphQLFloat && (value is Float || value is Double) -> value
            type == Scalars.GraphQLInt && (value is Int || value is Long) -> value
            type == Scalars.GraphQLString && value is String -> value
            type.name == ExtendedScalars.DateTime.name && (value is String || value is OffsetDateTime) -> mapOf(
                JsonLdKeywords.type to XSDVocab.dateTime,
                JsonLdKeywords.value to value
            )
            type.name == ExtendedScalars.Date.name && (value is String || value is LocalDate) -> mapOf(
                JsonLdKeywords.type to XSDVocab.date,
                JsonLdKeywords.value to value
            )
            type.name == ExtendedScalars.Time.name && (value is String || value is OffsetTime) -> mapOf(
                JsonLdKeywords.type to XSDVocab.time,
                JsonLdKeywords.value to value
            )
            else -> throw IllegalArgumentException("Unsupported scalar value type: ${value.javaClass}")
        }
    }
}

