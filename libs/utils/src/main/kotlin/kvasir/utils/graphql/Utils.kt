package kvasir.utils.graphql

import graphql.language.Field
import graphql.language.StringValue
import graphql.schema.GraphQLDirectiveContainer
import kvasir.definitions.kg.graphql.ARG_IRI_NAME
import kvasir.definitions.kg.graphql.DIRECTIVE_CLASS_NAME
import kvasir.definitions.kg.graphql.DIRECTIVE_PREDICATE_NAME
import kvasir.definitions.rdf.JsonLdHelper

fun getFQName(node: GraphQLDirectiveContainer, context: Map<String, Any>): String {
    return JsonLdHelper.getFQName(node.name, context, "_")?.takeIf { it != node.name }
        ?: run {
            node.getAppliedDirective(DIRECTIVE_PREDICATE_NAME)?.getArgument(ARG_IRI_NAME)?.getValue<String>()
                ?: node.getAppliedDirective(DIRECTIVE_CLASS_NAME)?.getArgument(ARG_IRI_NAME)?.getValue<String>()
                    ?.let { JsonLdHelper.getFQName(it, context) ?: it }
        } ?: throw IllegalArgumentException("No semantic context found for ${node.name}")
}

fun getFQName(field: Field, context: Map<String, Any>): String {
    return JsonLdHelper.getFQName(field.name, context, "_")?.takeIf { it != field.name }
        ?: run {
            field.getDirectiveArg<StringValue>(DIRECTIVE_PREDICATE_NAME, ARG_IRI_NAME)?.value
                ?: field.getDirectiveArg<StringValue>(DIRECTIVE_CLASS_NAME, ARG_IRI_NAME)?.value
                    ?.let { JsonLdHelper.getFQName(it, context) ?: it }
        } ?: throw IllegalArgumentException("No semantic context found for ${field.name}")
}