package kvasir.definitions.kg.graphql

import graphql.schema.GraphQLEnumType

object KvasirEnums {

    val sortOrderEnum = GraphQLEnumType.newEnum().name(ENUM_SORT_ORDER_NAME)
        .value(ENUM_SORT_ORDER_ASC_VALUE)
        .value(ENUM_SORT_ORDER_DESC_VALUE)
        .build()

    val triggerTypeEnum = GraphQLEnumType.newEnum().name(ENUM_TRIGGER_TYPE_NAME)
        .value(ENUM_TRIGGER_TYPE_INSERT_VALUE)
        .value(ENUM_TRIGGER_TYPE_DELETE_VALUE)
        .build()

    val all = setOf(sortOrderEnum, triggerTypeEnum)

}