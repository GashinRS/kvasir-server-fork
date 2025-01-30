package kvasir.utils.graphql

import graphql.language.Field
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.schema.*
import io.vertx.core.json.JsonObject
import kvasir.definitions.kg.DEFAULT_PAGE_SIZE
import kvasir.definitions.rdf.XSDVocab
import kvasir.utils.cursors.OffsetBasedCursor

fun <T : GraphQLType> GraphQLOutputType.innerType(): T {
    return GraphQLTypeUtil.unwrapAllAs<T>(this)
}

fun GraphQLOutputType.isOptional(): Boolean {
    return GraphQLTypeUtil.isNullable(this)
}

fun GraphQLOutputType.isList(): Boolean {
    return GraphQLTypeUtil.isList(this) || GraphQLTypeUtil.isList(GraphQLTypeUtil.unwrapNonNull(this))
}

fun GraphQLOutputType.isScalar(): Boolean {
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
