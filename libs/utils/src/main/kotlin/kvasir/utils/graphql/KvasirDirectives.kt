package kvasir.utils.graphql

import graphql.language.*
import graphql.schema.idl.TypeDefinitionRegistry

fun TypeDefinitionRegistry.addKvasirDirectives() {
    // TODO: avoid duplication with directives added to the GraphQLSchema
    this
        .addAll(
            listOf(
                DirectiveDefinition.newDirectiveDefinition().name("predicate").inputValueDefinitions(
                    listOf(
                        InputValueDefinition.newInputValueDefinition().name("iri").type(
                            TypeName.newTypeName("String").build()
                        ).build(),
                        InputValueDefinition.newInputValueDefinition().name("reverse").type(
                            TypeName.newTypeName("Boolean").build()
                        ).build()
                    )
                )
                    .directiveLocation(DirectiveLocation.newDirectiveLocation().name("FIELD_DEFINITION").build())
                    .build(),
                DirectiveDefinition.newDirectiveDefinition().name("class").inputValueDefinitions(
                    listOf(
                        InputValueDefinition.newInputValueDefinition().name("iri").type(
                            TypeName.newTypeName("String").build()
                        ).build()
                    )
                )
                    .directiveLocations(
                        listOf(
                            DirectiveLocation.newDirectiveLocation().name("OBJECT").build(),
                            DirectiveLocation.newDirectiveLocation().name("INTERFACE").build()
                        )
                    )
                    .build(),
                DirectiveDefinition.newDirectiveDefinition().name("shape").inputValueDefinitions(
                    listOf(
                        InputValueDefinition.newInputValueDefinition().name("minCount")
                            .type(TypeName.newTypeName("Int").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("maxCount")
                            .type(TypeName.newTypeName("Int").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("minExclusive")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("maxExclusive")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("minInclusive")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("maxInclusive")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("minLength")
                            .type(TypeName.newTypeName("Int").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("maxLength")
                            .type(TypeName.newTypeName("Int").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("pattern")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("flags")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("hasValue")
                            .type(TypeName.newTypeName("String").build()).build(),
                        InputValueDefinition.newInputValueDefinition().name("in")
                            .type(ListType(TypeName.newTypeName("String").build())).build(),
                    )
                )
                    .directiveLocation(DirectiveLocation.newDirectiveLocation().name("FIELD_DEFINITION").build())
                    .build(),
            )
        )
}