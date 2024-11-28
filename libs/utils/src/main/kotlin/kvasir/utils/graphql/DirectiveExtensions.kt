package kvasir.utils.graphql

import graphql.language.DirectivesContainer
import graphql.language.Value

fun <T : Value<*>> DirectivesContainer<*>.getDirectiveArg(
    name: String,
    argName: String
): T? {
    return this.getDirectives(name).firstOrNull()?.getArgument(argName)?.value as? T
}