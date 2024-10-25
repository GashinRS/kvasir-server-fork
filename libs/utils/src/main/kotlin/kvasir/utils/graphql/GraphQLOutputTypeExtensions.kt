package kvasir.utils.graphql

import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import kvasir.definitions.rdf.XSDVocab

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