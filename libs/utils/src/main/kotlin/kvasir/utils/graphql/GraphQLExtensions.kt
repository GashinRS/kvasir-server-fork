package kvasir.utils.graphql

import graphql.language.*
import graphql.schema.*
import io.vertx.core.json.JsonObject
import kvasir.definitions.kg.DEFAULT_PAGE_SIZE
import kvasir.definitions.kg.graphql.ARG_CURSOR_NAME
import kvasir.definitions.kg.graphql.ARG_PAGE_SIZE_NAME
import kvasir.definitions.kg.graphql.FIELD_ID_NAME
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.cursors.OffsetBasedCursor

fun <T : GraphQLType> GraphQLType.innerType(): T {
    return GraphQLTypeUtil.unwrapAllAs(this)
}

fun GraphQLType.isOptional(): Boolean {
    return GraphQLTypeUtil.isNullable(this)
}

fun GraphQLType.isList(): Boolean {
    return GraphQLTypeUtil.isList(this) || GraphQLTypeUtil.isList(GraphQLTypeUtil.unwrapNonNull(this))
}

fun GraphQLType.isScalar(): Boolean {
    return GraphQLTypeUtil.isScalar(this.innerType())
}

fun GraphQLScalarType.rdfDatatype(): String {
    return when (this.name) {
        "String" -> XSDVocab.string
        "Int" -> XSDVocab.integer
        "Float" -> XSDVocab.double
        "Boolean" -> XSDVocab.boolean
        else -> "http://www.w3.org/2001/XMLSchema#string"
    }
}

/**
 * Returns true if the type is an interface or union type, otherwise false.
 */
fun GraphQLOutputType.isAbstract(): Boolean {
    return GraphQLTypeUtil.unwrapAll(this).let { it is GraphQLInterfaceType || it is GraphQLUnionType }
}

fun Field.getIntArgument(name: String, variables: Map<String, Any>): Int? {
    return this.arguments.find { it.name == name }?.let {
        when (val value = it.value) {
            is IntValue -> value.value.toInt()
            is VariableReference -> {
                variables[value.name].toString().toInt()
            }

            else -> throw IllegalArgumentException("Unsupported argument type: ${value::class.simpleName}")
        }
    }
}

fun Field.getStringArgument(name: String, variables: Map<String, Any>): String? {
    return this.arguments.find { it.name == name }?.let {
        when (val value = it.value) {
            is StringValue -> value.value
            is VariableReference -> {
                variables[value.name].toString()
            }

            else -> throw IllegalArgumentException("Unsupported argument type: ${value::class.simpleName}")
        }
    }
}

fun Field.getStringArrayArgument(name: String, variables: Map<String, Any>): List<String>? {
    return this.arguments.find { it.name == name }?.let {
        when (val argVal = it.value) {
            is ArrayValue -> argVal.values.map { value ->
                when (value) {
                    is StringValue -> value.value
                    is VariableReference -> {
                        variables[value.name].toString()
                    }

                    else -> throw IllegalArgumentException("Unsupported argument type: ${value::class.simpleName}")
                }
            }

            is StringValue -> listOf(argVal.value)
            else -> throw IllegalArgumentException("Unsupported argument type: ${argVal::class.simpleName}")
        }
    }
}

fun Field.getPaginationInfo(variables: Map<String, Any>): Pair<Int, Long> {
    val pageSize = this.getIntArgument(ARG_PAGE_SIZE_NAME, variables) ?: DEFAULT_PAGE_SIZE
    val cursor =
        this.getStringArgument(ARG_CURSOR_NAME, variables)?.let { OffsetBasedCursor.fromString(it)?.offset } ?: 0L
    return pageSize to cursor
}

fun Field.aliasOrName(): String {
    // Never alias ID (this would mess up a lot of internal logic)
    return if (this.name == FIELD_ID_NAME) {
        this.name
    } else {
        this.alias ?: this.name
    }
}

fun <T> DataFetchingEnvironment.getFromSource(key: String): T? {
    val source = getSource<Any?>()
    return when (source) {
        is Map<*, *> -> source[key]
        is JsonObject -> source.getValue(key)
        else -> null
    } as T?
}

fun DataFetchingEnvironment.getStorageClass(): String? {
    return this.mergedField.singleField.getDirectiveArg<StringValue>("storage", "class")?.value
}

fun <T : Value<*>> DirectivesContainer<*>.getDirectiveArg(
    name: String,
    argName: String
): T? {
    return this.getDirectives(name).firstOrNull()?.getArgument(argName)?.value as? T
}

fun <T : Value<*>> DirectivesContainer<*>.getDirectiveArg(
    name: String,
    argName: String,
    defaultValue: T
): T {
    return this.getDirectiveArg(name, argName) ?: defaultValue
}

fun <T : Value<*>> GraphQLDirectiveContainer.getDirectiveArg(
    name: String,
    argName: String
): T? {
    return this.getAppliedDirective(name)?.getArgument(argName)?.argumentValue?.value?.let { it as T }
}