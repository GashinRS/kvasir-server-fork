package kvasir.definitions.kg.graphql

import graphql.Scalars.*
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import kvasir.definitions.kg.DEFAULT_PAGE_SIZE
import kvasir.definitions.rdf.RDFSVocab

object KvasirTypes {

    val RDFNode = GraphQLInterfaceType.newInterface().name(TYPE_RDF_NODE)
        .description("A common interface for representing values that can either be a Resource or a Literal.")
        .field(
            GraphQLFieldDefinition.newFieldDefinition().name(FIELD_RAW_RDF_NAME).type(ExtendedScalars.Json)
                .description("Access the raw RDF string representation of this Node.")
        )
        .build()

    val defaultRelationArguments = listOf(
        GraphQLArgument.newArgument().name(ARG_ID_NAME).description("Find instances of this Resource by ID.")
            .type(GraphQLList.list(GraphQLID)).build(),
        GraphQLArgument.newArgument().name(ARG_PAGE_SIZE_NAME)
            .description("Limit the amount of returned results to the specified value.").type(GraphQLInt)
            .defaultValueProgrammatic(DEFAULT_PAGE_SIZE)
            .build(),
        GraphQLArgument.newArgument().name(ARG_CURSOR_NAME).description("List results from the specified cursor.")
            .type(GraphQLString).build(),
        GraphQLArgument.newArgument().name(ARG_ORDER_BY_NAME)
            .description("Specify the sort keys. A sort key refers to a field name and may be prefixed with `-` to sort in reverse order.")
            .type(GraphQLList.list(GraphQLString)).build()
    )

    val commonResourceFields = listOf(
        GraphQLFieldDefinition.newFieldDefinition().name(FIELD_ID_NAME).description("The id of the Resource.")
            .type(GraphQLNonNull.nonNull(GraphQLID)).build(),
        GraphQLFieldDefinition.newFieldDefinition().name(FIELD_RELATIONS_NAME)
            .type(GraphQLList.list(GraphQLID))
            .description("Retrieve a list of relations that exists between this Resource and another Resource specified by a URI.")
            .argument(GraphQLArgument.newArgument().name(ARG_ID_NAME).type(GraphQLID).build()).build(),
        GraphQLFieldDefinition.newFieldDefinition().name(FIELD_PREDICATES_NAME).type(GraphQLList.list(GraphQLID))
            .description("Retrieve a list of all predicate IRIs, associated with this Resource.").build(),
        GraphQLFieldDefinition.newFieldDefinition().name(FIELD_TYPES_NAME).type(GraphQLList.list(GraphQLID))
            .description("Retrieve a list of RDF types associated with this Resource.").build(),
        GraphQLFieldDefinition.newFieldDefinition().name(FIELD_RAW_RDF_NAME)
            .description("Access the raw RDF string representation of this Resource (i.e. <ID>).")
            .type(ExtendedScalars.Json)
            .build(),
        GraphQLFieldDefinition.newFieldDefinition().name(FIELD_OBJECT_NAME)
            .type(GraphQLList.list(GraphQLNonNull.nonNull(RDFNode)))
            .description("Dynamically retrieve the object for the specified relation.")
            .argument(GraphQLArgument.newArgument().name(ARG_PREDICATE_NAME).type(GraphQLNonNull.nonNull(GraphQLID)))
            .arguments(defaultRelationArguments)
            .build()
    )
    val Resource = GraphQLInterfaceType.newInterface().name(TYPE_RESOURCE)
        .description("Common supertype for representing RDF resources.").withInterface(RDFNode)
        .fields(commonResourceFields)
        .withAppliedDirective(KvasirDirectives.classDirective.toAppliedDirective().let { resourceTypeDirective ->
            resourceTypeDirective.transform { directiveBuilder ->
                directiveBuilder.argument(resourceTypeDirective.getArgument(ARG_IRI_NAME).transform { argBuilder ->
                    argBuilder.valueLiteral(StringValue.of(RDFSVocab.Resource))
                })
            }
        })
        .build()

    val UntypedResource = GraphQLObjectType.newObject().name(TYPE_UNTYPED_RESOURCE)
        .description("Common supertype for representing RDF resources.")
        .withInterfaces(Resource, RDFNode)
        .fields(commonResourceFields)
        .withAppliedDirective(KvasirDirectives.classDirective.toAppliedDirective().let { resourceTypeDirective ->
            resourceTypeDirective.transform { directiveBuilder ->
                directiveBuilder.argument(resourceTypeDirective.getArgument(ARG_IRI_NAME).transform { argBuilder ->
                    argBuilder.valueLiteral(StringValue.of(RDFSVocab.Resource))
                })
            }
        })
        .build()

    val BoxedLiteral = GraphQLObjectType.newObject().name(TYPE_BOXED_LITERAL)
        .description("This type represents a boxed literal, can be useful to use in combination with the RDFNode supertype, in order to support fields which can either have Resources or literals as values.")
        .withInterface(RDFNode)
        .field(
            GraphQLFieldDefinition.newFieldDefinition().name(FIELD_RAW_RDF_NAME)
                .description("Access the raw RDF string representation of this Literal.").type(ExtendedScalars.Json)
        )
        .build()

    val all = setOf(RDFNode, Resource, UntypedResource, BoxedLiteral)
}