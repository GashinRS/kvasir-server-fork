package kvasir.utils.graphql

import graphql.Scalars
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
private val DYNAMIC_INTROSPECTION_FIELD_NAMES = setOf(FIELD_RELATIONS_NAME, FIELD_OBJECT_NAME, FIELD_PREDICATES_NAME)

private val ALL_SCALARS = setOf(
    Scalars.GraphQLString.name,
    Scalars.GraphQLInt.name,
    Scalars.GraphQLFloat.name,
    Scalars.GraphQLBoolean.name,
    ExtendedScalars.Json.name,
    ExtendedScalars.DateTime.name,
    ExtendedScalars.Date.name,
    ExtendedScalars.Time.name
)

class SliceGraphQLSchema(private val sliceSchema: String, private val context: JSONObject) {

    private val typeDefinitionRegistry = SchemaParser().parse(sliceSchema)
    private val queryType = typeDefinitionRegistry.getType(TYPE_QUERY, ObjectTypeDefinition::class.java).getOrNull()

    val isGenerateMutationsGlobal = queryType?.directives?.any { it.name == DIRECTIVE_GENERATE_MUTATIONS_NAME } ?: false
    val generateMutationsOperationsGlobal =
        queryType?.getDirectiveArg<ArrayValue>(
            DIRECTIVE_GENERATE_MUTATIONS_NAME,
            ARG_OPERATIONS_NAME,
            DEFAULT_OPS_ARG
        )?.values?.filterIsInstance<StringValue>()?.map { it.value } ?: emptyList()

    private val processedTypeDefinitionRegistry = run {
        if (queryType == null) {
            // If no Query type is defined, create a new one
            typeDefinitionRegistry.add(
                ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(TYPE_QUERY)
                    .fieldDefinition(
                        FieldDefinition.newFieldDefinition().name("noop").type(TypeName.newTypeName("String").build())
                            .build()
                    )
                    .build()
            )
        }

        typeDefinitionRegistry.add(ScalarTypeDefinition.newScalarTypeDefinition().name("JSON").build())
        typeDefinitionRegistry.add(ScalarTypeDefinition.newScalarTypeDefinition().name("DateTime").build())
        typeDefinitionRegistry.add(ScalarTypeDefinition.newScalarTypeDefinition().name("Date").build())
        typeDefinitionRegistry.add(ScalarTypeDefinition.newScalarTypeDefinition().name("Time").build())

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
                val operations = if (isGenerateMutations) {
                    generateMutationsOperations
                } else if (isGenerateMutationsGlobal) {
                    generateMutationsOperationsGlobal
                } else {
                    emptyList()
                }
                if (operations.isNotEmpty()) {
                    val insertDeleteOps = operations.filter { !it.startsWith(MUTATION_UPDATE_PREFIX) && !it.startsWith(MUTATION_SET_PREFIX) }
                    val updateOps = operations.filter { it.startsWith(MUTATION_UPDATE_PREFIX) || it.startsWith(MUTATION_SET_PREFIX) }
                    if (insertDeleteOps.isNotEmpty()) {
                        generatedInputTypes.add(generateInputType(type) to insertDeleteOps)
                    }
                    if (updateOps.isNotEmpty()) {
                        generatedInputTypes.add(generateUpdateInputType(type) to updateOps)
                    }
                }
            }

        typeDefinitionRegistry.getTypes(InterfaceTypeDefinition::class.java).forEach { type ->
            enhanceInterfaceType(type)
        }
        generateMutations(generatedInputTypes)
        // Add Kvasir built-in types (e.g. RDFNode, Resource, etc.)
        addKvasirBuiltins()
        typeDefinitionRegistry
    }

    fun getTypeDefinitionRegistry(): TypeDefinitionRegistry {
        return processedTypeDefinitionRegistry
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
            RuntimeWiring.newRuntimeWiring()
                .scalar(ExtendedScalars.Json)
                .scalar(ExtendedScalars.Time)
                .scalar(ExtendedScalars.Date)
                .scalar(ExtendedScalars.DateTime)
                .wiringFactory(dynamicWiringFactory).build()
        return SchemaGenerator().makeExecutableSchema(processedTypeDefinitionRegistry, runtimeWiring)
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

    fun hasMutations(): Boolean {
        return typeDefinitionRegistry.getType(TYPE_MUTATION, ObjectTypeDefinition::class.java).isPresent
    }

    fun validate() {
        val checkContextVisitor = CheckContextVisitor(context)
        processedTypeDefinitionRegistry.types().forEach { (_, type) ->
            AstTransformer().transform(type, checkContextVisitor)
        }
        // Validate that update/set mutations have a matching Query path
        validateUpdateMutationsHaveQueryPaths()
    }

    /**
     * Validates that every update/set mutation argument type has a corresponding field in the Query type
     * that returns the matching output type. This is required because update mutations generate with-clauses
     * that resolve against the Query type. Matching is done by fully qualified IRI, not by GraphQL type name.
     */
    private fun validateUpdateMutationsHaveQueryPaths() {
        val mutationType = processedTypeDefinitionRegistry.getType(TYPE_MUTATION, ObjectTypeDefinition::class.java).orElse(null) ?: return
        val queryTypeDef = processedTypeDefinitionRegistry.getType(TYPE_QUERY, ObjectTypeDefinition::class.java).orElse(null) ?: return

        // Resolve the FQ IRIs of all query field return types
        val queryFieldReturnTypeIris = queryTypeDef.fieldDefinitions.mapNotNull { fieldDef ->
            val returnTypeName = TypeUtil.unwrapAll(fieldDef.type).name
            val returnTypeDef = processedTypeDefinitionRegistry.getType(returnTypeName).orElse(null)
            if (returnTypeDef is DirectivesContainer<*>) {
                try {
                    getFQName(returnTypeName, returnTypeDef as DirectivesContainer<*>, context)
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else null
        }.toSet()

        mutationType.fieldDefinitions
            .filter { it.name.startsWith(MUTATION_UPDATE_PREFIX) || it.name.startsWith(MUTATION_SET_PREFIX) }
            .forEach { mutationField ->
                mutationField.inputValueDefinitions.forEach { inputValueDef ->
                    val inputTypeName = TypeUtil.unwrapAll(inputValueDef.type).name
                    val inputTypeDef = processedTypeDefinitionRegistry.getType(inputTypeName).orElse(null)
                    // Resolve the FQ IRI of the input type (via @class directive or prefix)
                    val inputTypeIri = if (inputTypeDef is DirectivesContainer<*>) {
                        try {
                            getFQName(inputTypeName, inputTypeDef as DirectivesContainer<*>, context)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    } else null

                    if (inputTypeIri == null || inputTypeIri !in queryFieldReturnTypeIris) {
                        throw RuntimeException(
                            "Update/set mutation '${mutationField.name}' references input type '$inputTypeName' " +
                                "(IRI: ${inputTypeIri ?: "unresolvable"}) which has no matching field in the Query type. " +
                                "Update mutations require a corresponding query path for with-clause resolution."
                        )
                    }
                }
            }
    }

    /**
     * Generates an update input type for the specified object type.
     * All fields except `id` are made nullable to support partial updates.
     */
    private fun generateUpdateInputType(type: ObjectTypeDefinition): InputObjectTypeDefinition {
        val fqTypeName = getFQName(type.name, type, context)
        val inputType = InputObjectTypeDefinition.newInputObjectDefinition()
            .name("${type.name}UpdateInput")
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
                    .mapNotNull { field ->
                        val referredType = typeDefinitionRegistry.getType(TypeUtil.unwrapAll(field.type)).get()
                        when (referredType) {
                            is ScalarTypeDefinition -> TypeName.newTypeName().name(referredType.name).build()
                            is ObjectTypeDefinition -> {
                                if (isGenerateMutationsGlobal || referredType.directives.any { it.name == DIRECTIVE_GENERATE_MUTATIONS_NAME }) {
                                    TypeName.newTypeName("${referredType.name}UpdateInput").build()
                                } else {
                                    ID_TYPE
                                }
                            }
                            is InterfaceTypeDefinition, is UnionTypeDefinition -> ID_TYPE
                            else -> null
                        }?.let { valueType ->
                            val isIdField = field.name == FIELD_ID_NAME
                            InputValueDefinition.newInputValueDefinition()
                                .name(field.name)
                                .directives(field.directives.filter { it.name == DIRECTIVE_SHAPE_NAME || it.name == DIRECTIVE_PREDICATE_NAME })
                                .type(
                                    if (isIdField) {
                                        // id field remains required (non-null)
                                        NonNullType.newNonNullType().type(ID_TYPE).build()
                                    } else if (TypeUtil.isList(field.type) || (TypeUtil.isNonNull(field.type) && TypeUtil.isList(TypeUtil.unwrapOne(field.type)))) {
                                        // List fields: make nullable list (e.g. [String!] — no outer NonNull)
                                        val innerType = if (TypeUtil.isWrapped(field.type)) {
                                            replaceInnerType(field.type, valueType)
                                        } else {
                                            valueType
                                        }
                                        stripOuterNonNull(innerType)
                                    } else {
                                        // Scalar/single fields: make nullable (strip NonNull wrapper if present)
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
     * Strips the outermost NonNull wrapper from a type, if present.
     */
    private fun stripOuterNonNull(type: Type<*>): Type<*> {
        return if (TypeUtil.isNonNull(type)) TypeUtil.unwrapOne(type) else type
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
                                // Copy shape directives AND predicate directives
                                .directives(field.directives.filter { it.name == DIRECTIVE_SHAPE_NAME || it.name == DIRECTIVE_PREDICATE_NAME })
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
                (type.implements + SchemaConversions.convertType(KvasirTypes.Resource) + SchemaConversions.convertType(
                    KvasirTypes.RDFNode
                )) to
                        // Add Kvasir built-in common fields, but don't include already defined fields, nor the dynamic introspection fields (as these should not be used with Slices)
                        (KvasirTypes.commonResourceFields.filter {
                            !definedFieldNames.contains(it.name) && !DYNAMIC_INTROSPECTION_FIELD_NAMES.contains(
                                it.name
                            )
                        }
                            .map { SchemaConversions.convertField(it) })
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
                .implementz(
                    type.implements + SchemaConversions.convertType(KvasirTypes.Resource) + SchemaConversions.convertType(
                        KvasirTypes.RDFNode
                    )
                )
                // Add common fields (e.g. id) to the type
                .definitions(type.fieldDefinitions.map { field -> enhanceField(field) } + KvasirTypes.commonResourceFields.filterNot {
                    definedFieldNames.contains(
                        it.name
                    )
                }.map { SchemaConversions.convertField(it) })
        }
        // TODO: Is there a better way to update the type definition?
        typeDefinitionRegistry.remove(type)
        typeDefinitionRegistry.add(enhancedType)
    }

    private fun enhanceField(field: FieldDefinition): FieldDefinition = field.transform { builder ->
        // Throw an exception if a field name starts with an underscore, as these are reserved for Kvasir system fields.
        if (field.name.startsWith("_")) {
            throw RuntimeException("Field names starting with an underscore are reserved for Kvasir system fields: '${field.name}'")
        }

        // Add common arguments (pageSize, cursor, etc) to fields that return a collection
        if (TypeUtil.isList(field.type) || (TypeUtil.isNonNull(field.type) && TypeUtil.isList(
                TypeUtil.unwrapOne(
                    field.type
                )
            ))
        ) {
            // Only add default arguments if they are not already defined
            val existingArgs = field.inputValueDefinitions.map { it.name }.toSet()

            val argsToAdd = if (TypeUtil.unwrapAll(field.type).let { ALL_SCALARS.contains(it.name) }) {
                // For fields that return a list of scalar values, we add the scalar collection arguments (pageSize, cursor, desc)
                KvasirTypes.scalarCollectionArguments
            } else {
                // For fields that return a list of non-scalar values, we add the default relation arguments (id, pageSize, cursor, orderBy)
                KvasirTypes.defaultRelationArguments
            }
            builder.inputValueDefinitions(field.inputValueDefinitions + argsToAdd.filterNot {
                existingArgs.contains(
                    it.name
                )
            }.map { SchemaConversions.convertArgument(it) })
        }
    }

    private fun addKvasirBuiltins() {
        // Register _Updatable* built-in input types
        SchemaParser().parse(UPDATABLE_TYPES_SDL).types().values.forEach { type ->
            typeDefinitionRegistry.add(type)
        }
        typeDefinitionRegistry.addAll(KvasirTypes.all.map { type ->
            // Do not generate dynamic introspection fields for GraphQL built-in types, as these break Slice data isolation (by supplying these field names as ignoreFields).
            when (type) {
                is GraphQLInterfaceType -> SchemaConversions.convertInterface(type, DYNAMIC_INTROSPECTION_FIELD_NAMES)
                is GraphQLObjectType -> SchemaConversions.convertObject(type, DYNAMIC_INTROSPECTION_FIELD_NAMES)
                else -> throw RuntimeException("Kvasir built-in setup does not support '${type::class.simpleName}'")
            }
        } + KvasirEnums.all.map { enum ->
            EnumTypeDefinition.newEnumTypeDefinition()
                .name(enum.name)
                .enumValueDefinitions(enum.values.map { enumVal ->
                    EnumValueDefinition.newEnumValueDefinition().name(enumVal.name).build()
                })
                .build()
        } + KvasirDirectives.all.map { SchemaConversions.convertDirective(it) })
    }

}