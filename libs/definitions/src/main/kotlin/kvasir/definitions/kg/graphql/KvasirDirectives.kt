package kvasir.definitions.kg.graphql

import graphql.Scalars.*
import graphql.introspection.Introspection
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLList

object KvasirDirectives {
    /**
     * Associate a GraphQL type with an RDF class.
     */
    val classDirective = GraphQLDirective.newDirective().name(DIRECTIVE_CLASS_NAME).validLocations(
        Introspection.DirectiveLocation.INTERFACE,
        Introspection.DirectiveLocation.OBJECT,
        Introspection.DirectiveLocation.INPUT_OBJECT
    )
        .argument(GraphQLArgument.newArgument().name(ARG_IRI_NAME).type(GraphQLString).build()).build()

    /**
     * Associate a GraphQL field with an RDF predicate.
     */
    val predicateDirective =
        GraphQLDirective.newDirective().name(DIRECTIVE_PREDICATE_NAME)
            .validLocations(
                Introspection.DirectiveLocation.FIELD_DEFINITION,
                Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION
            )
            .argument(GraphQLArgument.newArgument().name(ARG_IRI_NAME).type(GraphQLString).build())
            .argument(GraphQLArgument.newArgument().name(ARG_REVERSE_NAME).type(GraphQLBoolean).build()).build()

    /**
     * Add constraints to input object fields.
     *
     * - minCount: There must be at least $minCount values.
     * - maxCount: There must be at most $maxCount values.
     * - minExclusive: 	All values must be > $minExclusive.
     * - minInclusive: All values must be >= $minInclusive.
     * - maxExclusive: All values must be < $maxExclusive.
     * - maxInclusive: All values must be <= $maxInclusive.
     * - minLength: All values must have a string length of at least $minLength.
     * - maxLength: All values must have a string length of at most $maxLength.
     * - pattern: All values must match the regular expression $pattern.
     * - flags: Optional flags such as "i" (ignore case) for the regular expression matching using pattern.
     * - hasValue: One of the values must be $hasValue.
     * - in: All values must be from the given list of values.
     */
    val shapeDirective = GraphQLDirective.newDirective().name(DIRECTIVE_SHAPE_NAME)
        .validLocation(Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION)
        .argument(GraphQLArgument.newArgument().name(ARG_MIN_COUNT_NAME).type(GraphQLInt).build())
        .argument(GraphQLArgument.newArgument().name(ARG_MAX_COUNT_NAME).type(GraphQLInt).build())
        .argument(GraphQLArgument.newArgument().name(ARG_MIN_INCLUSIVE_NAME).type(GraphQLString).build())
        .argument(GraphQLArgument.newArgument().name(ARG_MAX_INCLUSIVE_NAME).type(GraphQLString).build())
        .argument(GraphQLArgument.newArgument().name(ARG_MIN_EXCLUSIVE_NAME).type(GraphQLString).build())
        .argument(GraphQLArgument.newArgument().name(ARG_MAX_EXCLUSIVE_NAME).type(GraphQLString).build())
        .argument(GraphQLArgument.newArgument().name(ARG_MIN_LENGTH_NAME).type(GraphQLInt).build())
        .argument(GraphQLArgument.newArgument().name(ARG_MAX_LENGTH_NAME).type(GraphQLInt).build())
        .argument(GraphQLArgument.newArgument().name(ARG_PATTERN_NAME).type(GraphQLString).build())
        .argument(GraphQLArgument.newArgument().name(ARG_FLAGS_NAME).type(GraphQLString).build())
        .argument(GraphQLArgument.newArgument().name(ARG_HAS_VALUE_NAME).type(GraphQLString).build())
        .argument(GraphQLArgument.newArgument().name(ARG_IN_NAME).type(GraphQLList.list(GraphQLString)).build())
        .build()

    /**
     * When querying: indicate that a field is optional.
     * i.e. Instances not having a value for the field, are also included in the result.
     */
    val optionalDirective =
        GraphQLDirective.newDirective().name(DIRECTIVE_OPTIONAL_NAME)
            .validLocation(Introspection.DirectiveLocation.FIELD)
            .build()

    /**
     * When querying: express additional matching conditions
     */
    val filterDirective =
        GraphQLDirective.newDirective().name(DIRECTIVE_FILTER_NAME)
            .validLocations(Introspection.DirectiveLocation.FIELD_DEFINITION, Introspection.DirectiveLocation.FIELD)
            .argument(GraphQLArgument.newArgument().name(ARG_IF_NAME).type(GraphQLString).build()).build()

    /**
     * When querying: express the target graphs for the query.
     */
    val graphDirective =
        GraphQLDirective.newDirective().name(DIRECTIVE_GRAPH_NAME).validLocations(
            Introspection.DirectiveLocation.QUERY
        )
            .argument(GraphQLArgument.newArgument().name(ARG_IRI_NAME).type(GraphQLList.list(GraphQLString)).build())
            .build()

    /**
     * When querying: explicitly set the target storage for a node
     */
    val storageDirective = GraphQLDirective.newDirective().name(DIRECTIVE_STORAGE_NAME).validLocations(
        Introspection.DirectiveLocation.FIELD
    )
        .argument(GraphQLArgument.newArgument().name(ARG_CLASS_NAME).type(GraphQLString).build()).build()

    /**
     * Collection of the Kvasir directives
     */
    val all = setOf(
        predicateDirective,
        classDirective,
        shapeDirective,
        optionalDirective,
        filterDirective,
        storageDirective,
        graphDirective
    )
}