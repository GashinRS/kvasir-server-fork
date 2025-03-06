package kvasir.definitions.kg.graphql

import graphql.schema.GraphQLEnumType

object KvasirEnums {

    val triggerTypeEnum = GraphQLEnumType.newEnum().name(ENUM_TRIGGER_TYPE_NAME)
        .value(ENUM_TRIGGER_TYPE_INSERT_VALUE)
        .value(ENUM_TRIGGER_TYPE_DELETE_VALUE)
        .build()

    val all = setOf(triggerTypeEnum)

}