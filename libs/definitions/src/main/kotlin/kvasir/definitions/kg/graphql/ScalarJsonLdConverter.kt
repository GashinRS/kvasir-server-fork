package kvasir.definitions.kg.graphql

import graphql.Scalars
import graphql.scalars.ExtendedScalars
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.XSDVocab

/**
 * Scalar type names that are treated as GraphQL scalars (leaves) during JSON-LD transformation.
 */
val GRAPHQL_SCALAR_NAMES: Set<String> = setOf(
    Scalars.GraphQLString.name,
    Scalars.GraphQLInt.name,
    Scalars.GraphQLFloat.name,
    Scalars.GraphQLBoolean.name,
    Scalars.GraphQLID.name,
    ExtendedScalars.DateTime.name,
    ExtendedScalars.Date.name,
    ExtendedScalars.Time.name,
    ExtendedScalars.Json.name
)

/**
 * Returns `true` if [typeName] is a known GraphQL scalar type that should be treated as a leaf value
 * during JSON-LD transformation.
 */
fun isGraphQLScalarName(typeName: String): Boolean = typeName in GRAPHQL_SCALAR_NAMES

/**
 * Converts a query result scalar value to its JSON-LD representation based on the GraphQL scalar type name.
 *
 * For query result values, IRIs in ID fields are already fully qualified — no prefix resolution is performed.
 *
 * Mapping:
 * - `ID`       → `{"@id": value}`
 * - `DateTime` → `{"@type": "xsd:dateTime", "@value": value}`
 * - `Date`     → `{"@type": "xsd:date", "@value": value}`
 * - `Time`     → `{"@type": "xsd:time", "@value": value}`
 * - Other scalars (`String`, `Int`, `Float`, `Boolean`, `JSON`) → pass through as-is
 *
 * @param value The scalar value from the GraphQL query result (already deserialized)
 * @param scalarTypeName The GraphQL scalar type name (e.g. `"ID"`, `"String"`, `"DateTime"`)
 * @return The JSON-LD representation of the value
 */
fun convertResultScalarToJsonLd(value: Any, scalarTypeName: String): Any {
    return when (scalarTypeName) {
        Scalars.GraphQLID.name -> mapOf(JsonLdKeywords.id to value.toString())
        ExtendedScalars.DateTime.name -> mapOf(JsonLdKeywords.type to XSDVocab.dateTime, JsonLdKeywords.value to value.toString())
        ExtendedScalars.Date.name -> mapOf(JsonLdKeywords.type to XSDVocab.date, JsonLdKeywords.value to value.toString())
        ExtendedScalars.Time.name -> mapOf(JsonLdKeywords.type to XSDVocab.time, JsonLdKeywords.value to value.toString())
        else -> value
    }
}

