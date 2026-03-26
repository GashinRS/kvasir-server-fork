package kvasir.definitions.kg.graphql

import graphql.Scalars.*
import graphql.introspection.Introspection
import graphql.language.ArrayValue
import graphql.language.StringValue
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull

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
        .validLocations(
            Introspection.DirectiveLocation.FIELD_DEFINITION,
            Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION
        )
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
     *
     * Two caveats:
     * 1. This directive is only allowed on fields of a nullable type or list type. Applying it to a non-nullable field would be contradictory (there is no way to return a missing result) and is therefore disallowed.
     * 2. When the accompanying field definition is annotated with @mustExist, specifying @optional on the field in the query will result in an error, since the field is required to exist according to the schema and cannot be treated as optional in the query.
     */
    val optionalDirective =
        GraphQLDirective.newDirective().name(DIRECTIVE_OPTIONAL_NAME)
            .validLocation(Introspection.DirectiveLocation.FIELD)
            .build()

    /**
     * When defining a schema: indicate that a field must have a value (i.e. cannot be null or empty) for its encapsulating object to be considered a valid instance of the parent type.
     */
    val mustExistDirective =
        GraphQLDirective.newDirective().name(DIRECTIVE_MUST_EXIST_NAME)
            .validLocation(Introspection.DirectiveLocation.FIELD_DEFINITION)
            .build()

    /**
     * When defining a schema: express conditions on when a field should be included in the result.
     * When querying: express additional matching conditions
     */
    val filterDirective =
        GraphQLDirective.newDirective().name(DIRECTIVE_FILTER_NAME)
            .validLocations(
                Introspection.DirectiveLocation.FIELD_DEFINITION,
                Introspection.DirectiveLocation.FIELD,
                Introspection.DirectiveLocation.INLINE_FRAGMENT
            )
            .argument(GraphQLArgument.newArgument().name(ARG_IF_NAME).type(GraphQLString).build()).build()

    /**
     * When querying/defining a schema: express the target graphs for the query.
     */
    val graphDirective =
        GraphQLDirective.newDirective().name(DIRECTIVE_GRAPH_NAME).validLocations(
            Introspection.DirectiveLocation.QUERY
        )
            .argument(GraphQLArgument.newArgument().name(ARG_IRI_NAME).type(GraphQLList.list(GraphQLString)).build())
            .build()

    /**
     * When defining subscriptions: specify a trigger event for the subscription.
     * If no directive is specified, the trigger event is based on the return type and the field suffix.
     */
    val triggerDirective = GraphQLDirective.newDirective().name(DIRECTIVE_TRIGGER_NAME).validLocations(
        Introspection.DirectiveLocation.FIELD_DEFINITION
    )
        .argument(GraphQLArgument.newArgument().name(ARG_TYPE_NAME).type(KvasirEnums.triggerTypeEnum))
        .argument(GraphQLArgument.newArgument().name(ARG_SUBJECT_NAME).type(GraphQLList.list(GraphQLString)))
        .argument(GraphQLArgument.newArgument().name(ARG_PREDICATE_NAME).type(GraphQLList.list(GraphQLString)))
        .argument(GraphQLArgument.newArgument().name(ARG_OBJECT_NAME).type(GraphQLList.list(GraphQLString)))
        .build()

    /**
     * When defining Slice types: enable auto-generation of matching input types and mutation operations.
     * (This is a quality-of-life feature that speeds up authoring Slices in common cases.)
     * Annotate individual Slice types with this directive to enable the generation of mutations for that type or
     * annotate the Query type to enable generation of mutations for all types.
     */
    val generateMutationsDirective = GraphQLDirective.newDirective().name(DIRECTIVE_GENERATE_MUTATIONS_NAME)
        .validLocations(Introspection.DirectiveLocation.OBJECT)
        .argument(
            GraphQLArgument.newArgument().name(ARG_OPERATIONS_NAME)
                .type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLString))).defaultValueLiteral(
                    ArrayValue.newArrayValue().values(
                        listOf(StringValue.of(MUTATION_ADD_PREFIX), StringValue.of(MUTATION_REMOVE_PREFIX))
                    ).build()
                ).build()
        )
        .build()

    /**
     * Collection of the Kvasir directives
     */
    val all = setOf(
        predicateDirective,
        classDirective,
        shapeDirective,
        optionalDirective,
        filterDirective,
        graphDirective,
        triggerDirective,
        generateMutationsDirective,
        mustExistDirective
    )
}