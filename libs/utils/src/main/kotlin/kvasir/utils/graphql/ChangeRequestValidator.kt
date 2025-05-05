package kvasir.utils.graphql

import graphql.Scalars
import graphql.language.ArrayValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.*
import graphql.schema.idl.*
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.RDFVocab

class ChangeRequestValidator(
    private val records: Set<ChangeRecord>,
    sliceSchema: String,
    private val context: JSONObject
) {
    private val typeRegistry = SchemaParser().parse(sliceSchema)
    private val graphQLSchema = run {
        typeRegistry.addKvasirBuiltins()
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
        SchemaGenerator().makeExecutableSchema(typeRegistry, runtimeWiring)
    }

    private val toBeValidatedRecords = mutableSetOf<ChangeRecord>().apply {
        // The records that should be validated, ignore certain system properties
        // E.g. a resource may always have a type property, but it is not part of the input schema
        addAll(records.filterNot { it.statement.predicate == RDFVocab.type })
    }

    fun validate() {
        // Navigate the GraphQL input types with the mutation type as entry point.
        // Each RDF statement should fit his structure (remove entries from the record set while navigating).
        // At the end of the cycle, the records set should be empty for the request to be valid.
        // If not, throw an exception.
        graphQLSchema.mutationType?.let { mutationType ->
            mutationType.fieldDefinitions.forEach { fieldDefinition ->
                fieldDefinition.arguments.forEach { arg ->
                    val argumentType = arg.type.innerType<GraphQLInputObjectType>()
                    val fqTypeName = argumentType.getFQName()
                    val operationType = getOperationType(fieldDefinition.name)
                    val instanceIds =
                        records.filter { it.type == operationType && (it.statement.predicate == RDFVocab.type && it.statement.`object` == fqTypeName) }
                            .map { it.statement.subject }.toSet()
                    validateInstancesOfType(instanceIds, argumentType, fqTypeName, operationType)
                }
            }
        }


        if (toBeValidatedRecords.isNotEmpty()) {
            val remainingRecords = toBeValidatedRecords.joinToString("\n") { it.toString() }
            throw InvalidChangeRequestException("The following records do not fit the expected input structure:\n$remainingRecords")
        }
    }

    private fun validateInstancesOfType(
        instanceIds: Set<String>,
        type: GraphQLInputObjectType,
        fqTypeName: String,
        operation: ChangeRecordType
    ) {
        instanceIds.forEach { instanceId ->
            val typeRecord =
                records.firstOrNull { it.type == operation && it.statement.subject == instanceId && it.statement.predicate == RDFVocab.type && it.statement.`object` == fqTypeName }
                    ?: throw InvalidChangeRequestException("Missing type '$fqTypeName' for instance '$instanceId'")

            // Remove the type record from the to be validated set
            toBeValidatedRecords.remove(typeRecord)

            val definedProperties =
                type.fieldDefinitions.mapNotNull { fieldDefinition ->
                    if (fieldDefinition.name == FIELD_ID_NAME) {
                        // Handle id field by checking if the subject matches the shape directive constraints
                        checkValueConstraints(instanceId, fieldDefinition, "Invalid subject for resource '$instanceId'")
                        null
                    } else {
                        val allowMultipleValues = fieldDefinition.type.isList()
                        val optional = fieldDefinition.type.isOptional()
                        val fqFieldName = resolveNameAsIri(fieldDefinition.name) ?: run {
                            // Or else get IRI from predicate directive
                            fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_PREDICATE_NAME, ARG_IRI_NAME)?.value
                        }
                        val matches =
                            records.filter { it.type == operation && it.statement.subject == instanceId && it.statement.predicate == fqFieldName }
                                .toSet()
                        if (matches.isEmpty() && !optional) {
                            throw InvalidChangeRequestException("Missing required property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName'")
                        }
                        if (matches.size > 1 && !allowMultipleValues) {
                            throw InvalidChangeRequestException("Property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName' has multiple values, but ${if (optional) "at most one" else "only one"} is allowed")
                        }

                        fieldDefinition.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_COUNT_NAME)
                            ?.let { minCount ->
                                if (matches.size < minCount.value.toInt()) {
                                    throw InvalidChangeRequestException("Property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName' has ${matches.size} values, but at least ${minCount.value} are required")
                                }
                            }

                        fieldDefinition.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MAX_COUNT_NAME)
                            ?.let { maxCount ->
                                if (matches.size > maxCount.value.toInt()) {
                                    throw InvalidChangeRequestException("Property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName' has ${matches.size} values, but at most ${maxCount.value} are allowed")
                                }
                            }

                        val fieldType = fieldDefinition.type.innerType<GraphQLInputType>()
                        when {
                            fieldType == Scalars.GraphQLID -> {
                                if (matches.any { it.statement.dataType != null }) {
                                    throw InvalidChangeRequestException("Property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName' should be an IRI")
                                }
                                matches.forEach {
                                    checkValueConstraints(
                                        it.statement.`object`,
                                        fieldDefinition,
                                        "Invalid object IRI for relation '$fqFieldName' on resource '$instanceId'"
                                    )
                                }
                            }

                            fieldType.isScalar() -> {
                                val rdfDataType = fieldType.innerType<GraphQLScalarType>().rdfDatatype()
                                if (matches.any { it.statement.dataType != rdfDataType }) {
                                    throw InvalidChangeRequestException("Property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName' should be of type '$rdfDataType'")
                                }
                                matches.forEach {
                                    checkValueConstraints(
                                        it.statement.`object`,
                                        fieldDefinition,
                                        "Invalid literal value for property '$fqFieldName' on resource '$instanceId'"
                                    )
                                }
                            }

                            else -> {
                                // Complex type, recurse
                                val complexType = fieldType.innerType<GraphQLInputObjectType>()
                                // Find subjects for the relationship
                                val targetInstanceIds =
                                    records.filter { it.type == operation && it.statement.subject == instanceId && it.statement.predicate == fqFieldName }
                                        .map { it.statement.`object`.toString() }.toSet()
                                validateInstancesOfType(
                                    targetInstanceIds,
                                    complexType,
                                    complexType.getFQName(),
                                    operation
                                )
                            }
                        }
                        // If all cases pass, remove the matching records from the to be validated set
                        toBeValidatedRecords.removeAll(matches)
                        fqFieldName
                    }
                }.toSet()
            // The instance cannot have properties that are not defined in the type
            val extraProperties =
                records.filter { it.type == operation && it.statement.subject == instanceId && it.statement.predicate != RDFVocab.type && it.statement.predicate !in definedProperties }
                    .map { it.statement.predicate }.toSet()
            if (extraProperties.isNotEmpty()) {
                throw InvalidChangeRequestException(
                    "The instance '$instanceId' of type '$fqTypeName' has predicates that are not supported by the input schema: ${
                        extraProperties.joinToString(
                            ", "
                        )
                    }"
                )
            }
        }
    }

    private fun getOperationType(mutationFieldName: String): ChangeRecordType {
        return if (mutationFieldName.startsWith("insert") || mutationFieldName.startsWith("add")) {
            ChangeRecordType.INSERT
        } else {
            ChangeRecordType.DELETE
        }
    }

    private fun GraphQLInputObjectType.getFQName(): String {
        return (resolveNameAsIri(this.name)
            ?: run {
                // Or else get IRI from class directive
                this.getDirectiveArg<StringValue>(DIRECTIVE_CLASS_NAME, ARG_IRI_NAME)?.value?.let {
                    JsonLdHelper.getFQName(it, context) ?: it
                }
            } ?: throw IllegalArgumentException("No semantic context found for input type '${this.name}'"))
    }

    private fun resolveNameAsIri(
        name: String,
        separator: String = KvasirNodeVisitor.GRAPHQL_NAME_PREFIX_SEPARATOR
    ): String? {
        return context[name]?.toString() ?: name.takeIf { it.contains(separator) }?.let { prefixedName ->
            val (prefix, localName) = prefixedName.split(separator, limit = 2)
            context[prefix]?.let { prefixIri ->
                "$prefixIri$localName"
            }
        }
    }

    private fun checkValueConstraints(
        predicateValue: Any,
        fieldDefinition: GraphQLInputObjectField,
        errorHeading: String
    ) {
        val value = predicateValue.toString()
        // TODO: expand the logic beyond simple string-based checks
        fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_EXCLUSIVE_NAME)
            ?.let { minExclusiveValue ->
                if (value <= minExclusiveValue.value) {
                    throw InvalidChangeRequestException("$errorHeading: '$value' is not greater than the minimum exclusive value '${minExclusiveValue.value}'")
                }
            }
        fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_INCLUSIVE_NAME)
            ?.let { minInclusiveValue ->
                if (value < minInclusiveValue.value) {
                    throw InvalidChangeRequestException("$errorHeading: '$value' is not greater than or equal to the minimum inclusive value '${minInclusiveValue.value}'")
                }
            }
        fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_MAX_EXCLUSIVE_NAME)
            ?.let { maxExclusiveValue ->
                if (value >= maxExclusiveValue.value) {
                    throw InvalidChangeRequestException("$errorHeading: '$value' is not less than the maximum exclusive value '${maxExclusiveValue.value}'")
                }
            }
        fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_MAX_INCLUSIVE_NAME)
            ?.let { maxInclusiveValue ->
                if (value > maxInclusiveValue.value) {
                    throw InvalidChangeRequestException("$errorHeading: '$value' is not less than or equal to the maximum inclusive value '${maxInclusiveValue.value}'")
                }
            }
        fieldDefinition.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_LENGTH_NAME)?.let { minLength ->
            if (value.length < minLength.value.toInt()) {
                throw InvalidChangeRequestException("$errorHeading: '$value' is shorter than the minimum length '${minLength.value}'")
            }
        }
        fieldDefinition.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MAX_LENGTH_NAME)?.let { maxLength ->
            if (value.length > maxLength.value.toInt()) {
                throw InvalidChangeRequestException("$errorHeading: '$value' is longer than the maximum length '${maxLength.value}'")
            }
        }
        fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_HAS_VALUE_NAME)?.let { expectedValue ->
            if (value != expectedValue.value) {
                throw InvalidChangeRequestException("$errorHeading: '$value' does not match the expected value '${expectedValue.value}'")
            }
        }
        fieldDefinition.getDirectiveArg<ArrayValue>(DIRECTIVE_SHAPE_NAME, ARG_IN_NAME)?.let { allowedValues ->
            val allowedValuesList = allowedValues.values.map { (it as StringValue).value }
            if (!allowedValuesList.contains(value)) {
                throw InvalidChangeRequestException(
                    "$errorHeading: '$value' is not in the list of allowed values '${
                        allowedValuesList.joinToString(
                            ", "
                        )
                    }'"
                )
            }
        }
        fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_PATTERN_NAME)?.let { pattern ->
            val flags = fieldDefinition.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_FLAGS_NAME)?.value
            val options = flags?.let {
                if (flags == "i") setOf(RegexOption.IGNORE_CASE) else throw IllegalArgumentException("Unsupported regex flag: $flags")
            } ?: emptySet()
            if (!Regex(pattern.value, options).containsMatchIn(value)) {
                throw InvalidChangeRequestException("$errorHeading: Value '$value' does not match the pattern '${pattern.value}'")
            }
        }
    }
}