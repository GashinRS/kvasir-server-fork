package kvasir.utils.graphql

import graphql.Scalars
import graphql.language.*
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import io.vertx.core.json.JsonObject
import kvasir.definitions.kg.DEFAULT_PAGE_SIZE
import kvasir.definitions.kg.graphql.*
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

fun <T : Value<*>> GraphQLDirectiveContainer.getDirectiveArg(
    name: String,
    argName: String
): T? {
    return this.getAppliedDirective(name)?.getArgument(argName)?.argumentValue?.value?.let { it as T }
}

fun TypeDefinitionRegistry.addKvasirBuiltins() {
    this.add(ScalarTypeDefinition.newScalarTypeDefinition().name("JSON").build())
    this.addAll(KvasirTypes.all.map { type ->
        when (type) {
            is GraphQLInterfaceType -> convertInterface(type)
            is GraphQLObjectType -> convertObject(type)
            else -> throw RuntimeException("Kvasir built-in setup does not support '${type::class.simpleName}'")
        }
    } + KvasirEnums.all.map { enum ->
        EnumTypeDefinition.newEnumTypeDefinition()
            .name(enum.name)
            .enumValueDefinitions(enum.values.map { enumVal ->
                EnumValueDefinition.newEnumValueDefinition().name(enumVal.name).build()
            })
            .build()
    } + KvasirDirectives.all.map { convertDirective(it) })
}

private fun convertInterface(type: GraphQLInterfaceType): InterfaceTypeDefinition {
    return InterfaceTypeDefinition.newInterfaceTypeDefinition()
        .name(type.name)
        .implementz(type.interfaces.map { TypeName.newTypeName(it.name).build() })
        .definitions(type.fieldDefinitions.map(::convertField))
        .build()
}

private fun convertObject(type: GraphQLObjectType): ObjectTypeDefinition {
    return ObjectTypeDefinition.newObjectTypeDefinition()
        .name(type.name)
        .implementz(type.interfaces.map { TypeName.newTypeName(it.name).build() })
        .fieldDefinitions(type.fieldDefinitions.map(::convertField))
        .build()
}

private fun convertField(field: GraphQLFieldDefinition): FieldDefinition {
    return FieldDefinition.newFieldDefinition()
        .name(field.name)
        .type(convertType(field.type))
        .inputValueDefinitions(field.arguments.map(::convertArgument))
        .build()
}

private fun convertDirective(directive: GraphQLDirective): DirectiveDefinition {
    return DirectiveDefinition.newDirectiveDefinition()
        .name(directive.name)
        .directiveLocations(
            directive.validLocations()
                .map { location -> DirectiveLocation.newDirectiveLocation().name(location.name).build() })
        .repeatable(directive.isRepeatable)
        .inputValueDefinitions(directive.arguments.map(::convertArgument))
        .build()
}

private fun convertType(type: GraphQLType): Type<*> {
    return when {
        GraphQLTypeUtil.isList(type) -> ListType.newListType(convertType(GraphQLTypeUtil.unwrapOne(type))).build()
        GraphQLTypeUtil.isNonNull(type) -> NonNullType.newNonNullType()
            .type(convertType(GraphQLTypeUtil.unwrapNonNull(type))).build()

        type is GraphQLNamedType -> TypeName.newTypeName().name(type.name).build()
        else -> throw IllegalArgumentException("Unsupported GraphQL type $type")
    }
}

private fun convertArgument(argument: GraphQLArgument): InputValueDefinition {
    return InputValueDefinition.newInputValueDefinition()
        .name(argument.name)
        .type(convertType(argument.type))
        .build()
}