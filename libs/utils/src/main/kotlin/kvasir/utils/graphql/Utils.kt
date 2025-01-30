package kvasir.utils.graphql

import graphql.language.Field
import graphql.language.StringValue
import graphql.schema.GraphQLDirectiveContainer
import kvasir.definitions.rdf.JsonLdHelper

fun getFQName(node: GraphQLDirectiveContainer, context: Map<String, Any>): String {
    return JsonLdHelper.getFQName(node.name, context, "_")?.takeIf { it != node.name }
        ?: run {
            node.getAppliedDirective("predicate")?.getArgument("iri")?.getValue<String>()
                ?: node.getAppliedDirective("type")?.getArgument("iri")?.getValue<String>()

        } ?: throw IllegalArgumentException("No semantic context found for ${node.name}")
}

fun getFQName(field: Field, context: Map<String, Any>): String {
    return JsonLdHelper.getFQName(field.name, context, "_")?.takeIf { it != field.name }
        ?: run {
            field.getDirectiveArg<StringValue>("predicate", "iri")?.value
                ?: field.getDirectiveArg<StringValue>("type", "iri")?.value

        } ?: throw IllegalArgumentException("No semantic context found for ${field.name}")
}