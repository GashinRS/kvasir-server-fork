package kvasir.utils.graphql

import graphql.TypeResolutionEnvironment
import graphql.language.Field
import graphql.language.StringValue
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.TypeResolver
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.getJsonArray
import kvasir.utils.json.convertToJsonMap

fun getFQName(node: GraphQLDirectiveContainer, context: Map<String, Any>): String {
    // predicate directive takes precedence over prefix before underscore in graphql schema
    return run {
        (node.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_IRI_NAME)?.getValue<String>()
            ?: node.getAppliedDirective(DIRECTIVE_CLASS_NAME)?.getArgument(ARG_IRI_NAME)?.getValue<String>())
            ?.let { JsonLdHelper.getFQName(it, context) ?: it }
    } ?: JsonLdHelper.getFQName(node.name, context, "_")?.takeIf { it != node.name }
    ?: throw IllegalArgumentException("No semantic context found for ${node.name}")
}

fun getFQName(field: Field, context: Map<String, Any>): String {
    // predicate directive takes precedence over prefix before underscore in graphql schema
    return run {
        (field.getDirectiveArg<StringValue>(DIRECTIVE_PREDICATE_NAME, ARG_IRI_NAME)?.value
            ?: field.getDirectiveArg<StringValue>(DIRECTIVE_CLASS_NAME, ARG_IRI_NAME)?.value)
            ?.let { JsonLdHelper.getFQName(it, context) ?: it }
    } ?: JsonLdHelper.getFQName(field.name, context, "_")?.takeIf { it != field.name }
    ?: throw IllegalArgumentException("No semantic context found for ${field.name}")
}

class RDFClassTypeResolver(private val context: JSONObject) : TypeResolver {
    override fun getType(env: TypeResolutionEnvironment): GraphQLObjectType {
        val target = convertToJsonMap(env.getObject())
        val fieldType = env.fieldType.innerType<GraphQLNamedType>()
        val defaultResolvedType =
            if (fieldType.name == TYPE_RESOURCE) KvasirTypes.UntypedResource else KvasirTypes.BoxedLiteral
        return (target[FIELD_TYPENAME_NAME] as String?)?.let {
            env.schema.getObjectType(it)
        } ?: run {
            // When no explicit __typename was set, use the _types field to access the RDF classes for this instance and select the first entry
            val fqClassNames = target.getJsonArray<String?>(FIELD_TYPES_NAME)?.filterNotNull() ?: emptyList()
            if (fqClassNames.isNotEmpty()) {
                val fqClassName = fqClassNames.min()
                env.schema.allTypesAsList.filterIsInstance<GraphQLObjectType>().find { objectType ->
                    (objectType.getDirectiveArg<StringValue>(
                        DIRECTIVE_CLASS_NAME,
                        ARG_IRI_NAME
                    )?.value ?: JsonLdHelper.getFQName(objectType.name, context, "_")) == fqClassName
                } ?: defaultResolvedType
            } else {
                // Default to BoxedLiteral
                defaultResolvedType
            }
        }
    }
}