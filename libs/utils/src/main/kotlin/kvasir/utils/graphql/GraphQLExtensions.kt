package kvasir.utils.graphql

import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import io.vertx.core.json.JsonObject
import kvasir.definitions.kg.DEFAULT_PAGE_SIZE
import kvasir.definitions.kg.graphql.KvasirDirectives
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

fun Field.getPaginationInfo(): Pair<Int, Long> {
    val pageSize = (arguments.find { it.name == "pageSize" }?.value as? IntValue)?.value?.toInt()
        ?: DEFAULT_PAGE_SIZE
    val cursor = (arguments.find { it.name == "cursor" }?.value as? StringValue)?.value?.let {
        OffsetBasedCursor.fromString(it)?.offset
    } ?: 0L
    return pageSize to cursor
}

fun <T> DataFetchingEnvironment.getFromSource(key: String): T? {
    val source = getSource<Any?>()
    return when (source) {
        is Map<*, *> -> source["_$key"] ?: source[key]
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

fun TypeDefinitionRegistry.addKvasirDirectives() {
    this.addAll(KvasirDirectives.all.map { directive ->
        DirectiveDefinition.newDirectiveDefinition()
            .name(directive.name)
            .directiveLocations(
                directive.validLocations()
                    .map { location -> DirectiveLocation.newDirectiveLocation().name(location.name).build() })
            .repeatable(directive.isRepeatable)
            .inputValueDefinitions(directive.arguments.map { argument ->
                InputValueDefinition.newInputValueDefinition()
                    .name(argument.name)
                    .type(convertType(argument.type))
                    .build()
            })
            .build()
    })
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