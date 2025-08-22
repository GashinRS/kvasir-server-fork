package kvasir.utils.graphql

import graphql.language.*
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import graphql.schema.idl.*
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kotlin.jvm.optionals.getOrNull

private val ID_TYPE = TypeName.newTypeName().name("ID").build()
private val INPUT_FIELD_IGNORE_LIST = setOf(
    FIELD_TYPES_NAME, FIELD_RELATIONS_NAME, FIELD_TYPENAME_NAME,
    FIELD_PREDICATES_NAME, FIELD_OBJECT_NAME, FIELD_RAW_RDF_NAME
)
private val DEFAULT_OPS_ARG = ArrayValue.newArrayValue().values(
    listOf(
        StringValue.of(MUTATION_ADD_PREFIX),
        StringValue.of(MUTATION_REMOVE_PREFIX)
    )
).build()

class SliceGraphQLSchema(private val sliceSchema: String, private val context: JSONObject) {

    private val typeDefinitionRegistry = SchemaParser().parse(sliceSchema)
    private val queryType = typeDefinitionRegistry.getType(TYPE_QUERY, ObjectTypeDefinition::class.java).getOrNull()
        ?: throw IllegalArgumentException("Query type '$TYPE_QUERY' is not defined in the schema")
    val isGenerateMutationsGlobal = queryType.directives.any { it.name == DIRECTIVE_GENERATE_MUTATIONS_NAME }
    val generateMutationsOperationsGlobal =
        queryType.getDirectiveArg<ArrayValue>(
            DIRECTIVE_GENERATE_MUTATIONS_NAME,
            ARG_OPERATIONS_NAME,
            DEFAULT_OPS_ARG
        ).values.filterIsInstance<StringValue>().map { it.value }

    fun getTypeDefinitionRegistry(): TypeDefinitionRegistry {
        val generatedInputTypes = mutableListOf<Pair<InputObjectTypeDefinition, List<String>>>()
        typeDefinitionRegistry.getTypes(ObjectTypeDefinition::class.java).forEach { type ->
            enhanceObjectType(type)
        }

        // Second pass to generate input types for object types
        typeDefinitionRegistry.getTypes(ObjectTypeDefinition::class.java)
            .filterNot { it.name in setOf(TYPE_QUERY, TYPE_MUTATION, TYPE_SUBSCRIPTION) }
            .forEach { type ->
                val isGenerateMutations = type.directives.any { it.name == DIRECTIVE_GENERATE_MUTATIONS_NAME }
                val generateMutationsOperations = type.getDirectiveArg<ArrayValue>(
                    DIRECTIVE_GENERATE_MUTATIONS_NAME,
                    ARG_OPERATIONS_NAME,
                    DEFAULT_OPS_ARG
                ).values.filterIsInstance<StringValue>().map { it.value }
                // If the type is annotated with @generateMutations, generate mutations for it
                if (isGenerateMutations) {
                    generatedInputTypes.add(generateInputType(type) to generateMutationsOperations)
                } else if (isGenerateMutationsGlobal) {
                    generatedInputTypes.add(generateInputType(type) to generateMutationsOperationsGlobal)
                }
            }

        typeDefinitionRegistry.getTypes(InterfaceTypeDefinition::class.java).forEach { type ->
            enhanceInterfaceType(type)
        }
        generateMutations(generatedInputTypes)
        // Add Kvasir built-in types (e.g. RDFNode, Resource, etc.)
        addKvasirBuiltins()
        return typeDefinitionRegistry
    }

    fun getDummySchema(): GraphQLSchema {
        val dynamicWiringFactory = object : WiringFactory {

            override fun getDefaultDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> {
                return DataFetcher { null }
            }

            override fun providesTypeResolver(environment: InterfaceWiringEnvironment): Boolean {
                return true
            }

            override fun getTypeResolver(environment: InterfaceWiringEnvironment): TypeResolver {
                return RDFClassTypeResolver(context)
            }

            override fun providesTypeResolver(environment: UnionWiringEnvironment): Boolean {
                return true
            }

            override fun getTypeResolver(environment: UnionWiringEnvironment): TypeResolver {
                return RDFClassTypeResolver(context)
            }

        }
        val runtimeWiring =
            RuntimeWiring.newRuntimeWiring().scalar(ExtendedScalars.Json).wiringFactory(dynamicWiringFactory).build()
        return SchemaGenerator().makeExecutableSchema(getTypeDefinitionRegistry(), runtimeWiring)
    }

    fun getSDL(): String {
        return SchemaPrinter(
            SchemaPrinter.Options.defaultOptions()
                .includeDirectiveDefinitions(false)
                .includeDirectives(true)
                .includeScalarTypes(false)
                .includeSchemaElement { it !is GraphQLObjectType || it.name != TYPE_UNTYPED_RESOURCE })
            .print(getDummySchema())
    }

    /**
     * Generates an input type for the specified object type.
     */
    private fun generateInputType(type: ObjectTypeDefinition): InputObjectTypeDefinition {
        // Create input type
        val fqTypeName = getFQName(type.name, type, context)
        val inputType = InputObjectTypeDefinition.newInputObjectDefinition()
            .name("${type.name}Input")
            .directive(
                Directive.newDirective().name(DIRECTIVE_CLASS_NAME).argument(
                    Argument.newArgument().name(
                        ARG_IRI_NAME
                    ).value(StringValue.of(fqTypeName)).build()
                ).build()
            )
            .inputValueDefinitions(
                type.fieldDefinitions
                    .filterNot { INPUT_FIELD_IGNORE_LIST.contains(it.name) }
                    .filterNot {
                        // Don't include reverse fields in input type (these are declared on the other side of the relation)
                        it.getDirectiveArg<BooleanValue>(
                            DIRECTIVE_PREDICATE_NAME, ARG_REVERSE_NAME
                        )?.isValue ?: false
                    }
                    .mapNotNull { field ->
                        val referredType = typeDefinitionRegistry.getType(TypeUtil.unwrapAll(field.type)).get()
                        when (referredType) {
                            // Scalar fields are included as input values
                            is ScalarTypeDefinition -> TypeName.newTypeName().name(referredType.name).build()
                            // For object types, the generated input type is used. If not available, its value is an ID (URI).
                            is ObjectTypeDefinition -> {
                                if (isGenerateMutationsGlobal || referredType.directives.any { it.name == DIRECTIVE_GENERATE_MUTATIONS_NAME }) {
                                    TypeName.newTypeName("${referredType.name}Input").build()
                                } else {
                                    ID_TYPE
                                }
                            }

                            // For interface and union types, the value is an ID (URI).
                            is InterfaceTypeDefinition, is UnionTypeDefinition -> ID_TYPE
                            else -> null
                        }?.let { valueType ->
                            InputValueDefinition.newInputValueDefinition()
                                .name(field.name)
                                // Copy shape directives
                                .directives(field.directives.filter { it.name == DIRECTIVE_SHAPE_NAME })
                                .type(
                                    if (TypeUtil.isWrapped(field.type)) {
                                        replaceInnerType(field.type, valueType)
                                    } else {
                                        valueType
                                    }
                                )
                                .build()
                        }
                    })
            .build()
        typeDefinitionRegistry.add(inputType)
        return inputType
    }

    /**
     * Generates mutations for the specified input types.
     */
    private fun generateMutations(inputTypes: List<Pair<InputObjectTypeDefinition, List<String>>>) {
        if (inputTypes.isNotEmpty()) {
            (typeDefinitionRegistry.getType(TYPE_MUTATION, ObjectTypeDefinition::class.java).getOrNull() ?: run {
                // If no Mutation type is defined, create a new one
                ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(TYPE_MUTATION)
                    .build()
            }).let { mutationType ->
                val inputTypesByOperation =
                    inputTypes.flatMap { inputType -> inputType.second.map { it to inputType.first } }
                        .groupBy({ it.first }, { it.second })
                val modifiedMutationType = mutationType.transform { builder ->
                    builder.fieldDefinitions(
                        mutationType.fieldDefinitions.filterNot { it.name in inputTypesByOperation.keys } + inputTypesByOperation.map { (operationType, inputTypes) ->
                            val existingOp = mutationType.fieldDefinitions.find { it.name == operationType }
                            val inputArgs = inputTypes.map { inputType ->
                                InputValueDefinition.newInputValueDefinition()
                                    .name(inputType.name.replaceFirstChar { it.lowercaseChar() }
                                        .removeSuffix("Input"))
                                    .type(
                                        ListType.newListType().type(
                                            NonNullType.newNonNullType()
                                                .type(TypeName.newTypeName(inputType.name).build()).build()
                                        ).build()
                                    )
                                    .build()
                            }
                            // If the operation already exists, we modify it to include the input types
                            if (existingOp != null) {
                                existingOp.transform { fieldBuilder ->
                                    fieldBuilder.inputValueDefinitions(existingOp.inputValueDefinitions + inputArgs)
                                }
                            } else {
                                // If the operation does not exist, create a new one
                                FieldDefinition.newFieldDefinition()
                                    .name(operationType)
                                    .inputValueDefinitions(inputArgs)
                                    .type(NonNullType.newNonNullType().type(ID_TYPE).build())
                                    .build()
                            }
                        }
                    )
                }
                typeDefinitionRegistry.remove(mutationType)
                typeDefinitionRegistry.add(modifiedMutationType)
            }
        }
    }

    /**
     * Enhances the type defined in the Schema with additional fields or arguments.
     */
    private fun enhanceObjectType(type: ObjectTypeDefinition) {
        val enhancedType = type.transform { typeBuilder ->
            val definedFieldNames = type.fieldDefinitions.map { it.name }.toSet()
            val (interfaces, commonFields) = if (type.name !in setOf(TYPE_QUERY, TYPE_MUTATION, TYPE_SUBSCRIPTION)) {
                (type.implements + convertType(KvasirTypes.Resource) + convertType(KvasirTypes.RDFNode)) to
                        (KvasirTypes.commonResourceFields.filterNot { definedFieldNames.contains(it.name) }
                            .map { convertField(it) })
            } else {
                type.implements to emptyList()
            }
            typeBuilder
                // A type always implements Resource (with the exception of GraphQL built-in types)
                .implementz(interfaces)
                // Add common fields (e.g. id) to the type
                .fieldDefinitions(type.fieldDefinitions.map { field -> enhanceField(field) } + commonFields)
        }
        // TODO: Is there a better way to update the type definition?
        typeDefinitionRegistry.remove(type)
        typeDefinitionRegistry.add(enhancedType)
    }

    private fun enhanceInterfaceType(type: InterfaceTypeDefinition) {
        val definedFieldNames = type.fieldDefinitions.map { it.name }.toSet()
        val enhancedType = type.transform { typeBuilder ->
            typeBuilder
                // An interface always implements Resource
                .implementz(type.implements + convertType(KvasirTypes.Resource) + convertType(KvasirTypes.RDFNode))
                // Add common fields (e.g. id) to the type
                .definitions(type.fieldDefinitions.map { field -> enhanceField(field) } + KvasirTypes.commonResourceFields.filterNot {
                    definedFieldNames.contains(
                        it.name
                    )
                }.map { convertField(it) })
        }
        // TODO: Is there a better way to update the type definition?
        typeDefinitionRegistry.remove(type)
        typeDefinitionRegistry.add(enhancedType)
    }

    private fun enhanceField(field: FieldDefinition): FieldDefinition = field.transform { builder ->
        // Add common arguments (pageSize, cursor, etc) to fields that return a collection
        if (TypeUtil.isList(field.type) || (TypeUtil.isNonNull(field.type) && TypeUtil.isList(
                TypeUtil.unwrapOne(
                    field.type
                )
            ))
        ) {
            // Only add default arguments if they are not already defined
            val existingArgs = field.inputValueDefinitions.map { it.name }.toSet()
            builder.inputValueDefinitions(field.inputValueDefinitions + KvasirTypes.defaultRelationArguments.filterNot {
                existingArgs.contains(
                    it.name
                )
            }.map { convertArgument(it) })
        }
    }

    private fun addKvasirBuiltins() {
        typeDefinitionRegistry.add(ScalarTypeDefinition.newScalarTypeDefinition().name("JSON").build())
        typeDefinitionRegistry.addAll(KvasirTypes.all.map { type ->
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

}