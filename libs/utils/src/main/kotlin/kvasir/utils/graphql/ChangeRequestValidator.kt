package kvasir.utils.graphql

import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLScalarType
import kvasir.definitions.kg.ChangeRecord
import kvasir.definitions.kg.ChangeRecordType
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.changes.AssertionPhase
import kvasir.definitions.kg.changes.ChangeRequest
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.*
import kvasir.utils.rdf.RDFTransformer
import java.math.BigDecimal

class ChangeRequestValidator(
    private val records: Collection<ChangeRecord>,
    sliceSchema: String,
    private val context: JSONObject,
    private val request: ChangeRequest
) {
    private val graphQLSchema = SliceGraphQLSchema(sliceSchema, context).getDummySchema()

    private val toBeValidatedRecords = mutableSetOf<ChangeRecord>().apply {
        // The records that should be validated, ignore certain system properties
        // E.g. a resource may always have a type property, but it is not part of the input schema
        addAll(records.filterNot { it.statement.predicate == RDFVocab.type })
    }

    /**
     * RDF statements produced by the JSON-LD document entries in [ChangeRequest.insert].
     * Statements NOT in this set came from JSONata templates and represent state-dependent inserts
     * (e.g. `_increment`, `_append`). Used to classify deletes as blanket vs targeted.
     */
    private val deterministicInsertStatements: Set<RDFStatement> by lazy {
        request.insert.filterIsInstance<Map<*, *>>().flatMap {
            @Suppress("UNCHECKED_CAST")
            try {
                RDFTransformer.toStatements(it as Map<String, Any>, request.sliceId ?: request.podId)
            } catch (_: Exception) {
                emptyList()
            }
        }.toSet()
    }

    /**
     * RDF statements produced by the JSON-LD document entries in [ChangeRequest.delete].
     * Statements NOT in this set came from JSONata templates (blanket deletes of all current values
     * for a predicate). Statements IN this set are targeted deletes of specific values (e.g. `_remove`).
     */
    private val deterministicDeleteStatements: Set<RDFStatement> by lazy {
        request.delete.filterIsInstance<Map<*, *>>().flatMap {
            @Suppress("UNCHECKED_CAST")
            try {
                RDFTransformer.toStatements(it as Map<String, Any>, request.sliceId ?: request.podId)
            } catch (_: Exception) {
                emptyList()
            }
        }.toSet()
    }

    fun validate() {
        // Navigate the GraphQL input types with the mutation type as entry point.
        // Each RDF statement should fit its structure (remove entries from the record set while navigating).
        // At the end of the cycle, the records set should be empty for the request to be valid.
        graphQLSchema.mutationType?.let { mutationType ->
            mutationType.fieldDefinitions.forEach { fieldDefinition ->
                fieldDefinition.arguments.forEach { arg ->
                    val argumentType = arg.type.innerType<GraphQLInputObjectType>()
                    val fqTypeName = argumentType.getFQName()
                    // Update/set mutations: subjects have BOTH an INSERT and DELETE rdf:type record.
                    val updateInstanceIds = detectUpdateSubjects(fqTypeName)

                    if (isUpdateOperation(fieldDefinition.name) && updateInstanceIds.isNotEmpty()) {
                        validateUpdateInstances(updateInstanceIds, argumentType, fqTypeName)
                    } else {
                        val operationType = getOperationType(fieldDefinition.name)
                        val instanceIds =
                            records.filter { it.type == operationType && it.statement.predicate == RDFVocab.type && it.statement.`object` == fqTypeName }
                                .map { it.statement.subject }.toSet() - updateInstanceIds
                        validateInstancesOfType(instanceIds, argumentType, fqTypeName, operationType)
                    }
                }
            }
        }

        if (toBeValidatedRecords.isNotEmpty()) {
            val remainingRecords = toBeValidatedRecords.joinToString("\n") { it.toString() }
            throw InvalidChangeRequestException("The following records do not fit the expected input structure:\n$remainingRecords")
        }
    }

    // ── Update detection ──────────────────────────────────────────────────────

    private fun isUpdateOperation(fieldName: String) =
        fieldName.startsWith(MUTATION_UPDATE_PREFIX) || fieldName.startsWith(MUTATION_SET_PREFIX)

    /**
     * Returns the set of resource IDs being updated: their `rdf:type` triple appears in
     * **both** INSERT and DELETE records (the symmetric pair emitted by [UpdateMutationCompiler]).
     */
    private fun detectUpdateSubjects(fqTypeName: String): Set<String> {
        val insertSubjects = records
            .filter { it.type == ChangeRecordType.INSERT && it.statement.predicate == RDFVocab.type && it.statement.`object` == fqTypeName }
            .map { it.statement.subject }.toSet()
        val deleteSubjects = records
            .filter { it.type == ChangeRecordType.DELETE && it.statement.predicate == RDFVocab.type && it.statement.`object` == fqTypeName }
            .map { it.statement.subject }.toSet()
        return insertSubjects intersect deleteSubjects
    }

    // ── Update validation ─────────────────────────────────────────────────────

    /**
     * Validates update instances in **relaxed mode**:
     * - `rdf:type` INSERT+DELETE pairs are consumed (redundant; filtered before persistence).
     * - INSERT records are validated inline against **merged insert+update** `@shape` constraints.
     *   Because all records are fully resolved (templates evaluated) before the validator runs,
     *   value-based constraints can always be checked inline — no POST assertions required for them.
     * - DELETE records are validated against **merged delete+update** `@shape` constraints.
     *   The delete input type defines which values the user is allowed to remove — this is a
     *   security/access boundary enforced by the Slice schema. The update type's constraints
     *   are merged in (stricter bound wins), mirroring how insert+update constraints are merged.
     * - **Blanket deletes** (from JSONata templates, removing all current values) on a non-null
     *   insert-type field are rejected **inline** before any data is written.
     * - **Targeted deletes** (from JSON-LD docs, removing specific values) on count-constrained
     *   fields generate POST count-bounds assertions to verify cardinality after mutation.
     * - Missing fields are NOT an error (partial update by design).
     * - Extra predicates not defined in the schema are rejected.
     */
    private fun validateUpdateInstances(
        instanceIds: Set<String>,
        updateInputType: GraphQLInputObjectType,
        fqTypeName: String
    ) {
        val insertInputType = findInsertInputType(fqTypeName)
        val deleteInputType = findDeleteInputType(fqTypeName)
        val queryFieldName = resolveQueryFieldName(fqTypeName)

        instanceIds.forEach { instanceId ->
            // Consume the redundant type INSERT+DELETE pair (filtered by pipeline before persistence)
            toBeValidatedRecords.removeAll(records.filter {
                it.statement.subject == instanceId && it.statement.predicate == RDFVocab.type
            }.toSet())

            // Validate id field constraints from the update type
            updateInputType.fieldDefinitions.firstOrNull { it.name == FIELD_ID_NAME }?.let { idField ->
                checkValueConstraints(
                    instanceId,
                    ShapeConstraints.fromField(idField),
                    "Invalid subject for resource '$instanceId'"
                )
            }

            // Map update type fields to their FQ predicate IRIs
            val definedProperties = updateInputType.fieldDefinitions
                .filter { it.name != FIELD_ID_NAME }
                .groupBy { fieldDef ->
                    JsonLdHelper.getFQName(fieldDef.name, context, "_") ?: run {
                        fieldDef.getDirectiveArg<StringValue>(DIRECTIVE_PREDICATE_NAME, ARG_IRI_NAME)
                            ?.value?.let { JsonLdHelper.getFQName(it, context) ?: it }
                    }!!
                }

            definedProperties.keys.filter { fqFieldName ->
                records.any { it.statement.subject == instanceId && it.statement.predicate == fqFieldName }
            }.map { fqFieldName ->
                val updateFieldDefs = definedProperties[fqFieldName]!!

                val insertMatches = records.filter {
                    it.type == ChangeRecordType.INSERT &&
                            it.statement.subject == instanceId &&
                            it.statement.predicate == fqFieldName
                }.toSet()
                val deleteMatches = records.filter {
                    it.type == ChangeRecordType.DELETE &&
                            it.statement.subject == instanceId &&
                            it.statement.predicate == fqFieldName
                }.toSet()

                // Classify deletes: blanket (JSONata template) vs targeted (JSON-LD doc)
                val hasBlanketDeletes =
                    deleteMatches.any { it.statement.copy(graph = "") !in deterministicDeleteStatements }
                val hasTargetedDeletes =
                    deleteMatches.any { it.statement.copy(graph = "") in deterministicDeleteStatements }

                // Build insert specs: merge(insertTypeConstraints, updateTypeConstraints)
                val insertSpecs = updateFieldDefs.map { updateFieldDef ->
                    val insertField = insertInputType?.fieldDefinitions?.firstOrNull { it.name == updateFieldDef.name }
                    val insertConstraints = insertField?.let {
                        val constraints = ShapeConstraints.fromField(it)
                        // A non-null insert field (!) implies minCount: 1 — propagate this as an
                        // explicit constraint so it survives the merge into the update spec.
                        if (!it.type.isNullable() && (constraints.minCount ?: 0) < 1) {
                            constraints.copy(minCount = 1)
                        } else {
                            constraints
                        }
                    } ?: ShapeConstraints.EMPTY
                    FieldValidationSpec.fromField(updateFieldDef, fqFieldName, context).asUpdateSpec(insertConstraints)
                }

                // Build delete specs: merge(deleteTypeConstraints, updateTypeConstraints)
                val deleteSpecs = updateFieldDefs.map { updateFieldDef ->
                    val deleteField = deleteInputType?.fieldDefinitions?.firstOrNull { it.name == updateFieldDef.name }
                    val deleteConstraints =
                        deleteField?.let { ShapeConstraints.fromField(it) } ?: ShapeConstraints.EMPTY
                    FieldValidationSpec.fromField(updateFieldDef, fqFieldName, context).asUpdateSpec(deleteConstraints)
                }

                // Inline validation of INSERT records against merged insert+update constraints
                if (insertMatches.isNotEmpty()) {
                    val validationResults = insertSpecs.map { spec ->
                        isValidFieldValue(spec, ChangeRecordType.INSERT, instanceId, fqTypeName, insertMatches)
                    }
                    if (validationResults.any { it.valid }) {
                        toBeValidatedRecords.removeAll(insertMatches)
                    } else {
                        val msg = validationResults.joinToString("\n\t") {
                            "- No match via field '${it.spec.name}': ${it.error?.message}"
                        }
                        throw InvalidChangeRequestException(
                            "Invalid value for property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName': $msg"
                        )
                    }
                }

                // Validate DELETE records against merged delete+update constraints.
                // The delete input type's @shape constraints define which values the user is allowed
                // to remove — a security/access boundary enforced by the Slice schema.
                if (deleteMatches.isNotEmpty()) {
                    val validationResults = deleteSpecs.map { spec ->
                        isValidFieldValue(spec, ChangeRecordType.DELETE, instanceId, fqTypeName, deleteMatches)
                    }
                    if (validationResults.any { it.valid }) {
                        toBeValidatedRecords.removeAll(deleteMatches)
                    } else {
                        val msg = validationResults.joinToString("\n\t") {
                            "- No match via field '${it.spec.name}': ${it.error?.message}"
                        }
                        throw InvalidChangeRequestException(
                            "Invalid value for property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName' (delete): $msg"
                        )
                    }
                }

                // Primary insert spec: carries the cardinality invariants that must survive the update.
                val primaryInsertSpec = insertSpecs.first()

                // Pure adds: INSERT records exist for this field but no DELETE records at all.
                // The total post-update count (existing + new) cannot be verified inline.
                // Use a POST count assertion when the request is state-dependent; otherwise reject.
                val hasPureAdds = insertMatches.isNotEmpty() && deleteMatches.isEmpty()
                if (hasPureAdds && primaryInsertSpec.effectiveMaxCount != null) {
                    if (request.isStateDependent() && queryFieldName != null) {
                        addCountPostAssertionIfNeeded(
                            instanceId,
                            queryFieldName,
                            primaryInsertSpec,
                            includeMinCount = false
                        )
                    } else {
                        throw InvalidChangeRequestException(
                            "Cannot verify @shape(maxCount: ${primaryInsertSpec.effectiveMaxCount}) for field '${updateFieldDefs.first().name}' " +
                                    "on instance '$instanceId' of type '$fqTypeName' when adding values without removing existing ones. " +
                                    "Count-bound post assertions require a state-dependent ChangeRequest."
                        )
                    }
                }

                // Blanket deletes: all current values of the field are removed.
                // If the insert type requires at least one value and no new values are being inserted, reject inline.
                if (hasBlanketDeletes && primaryInsertSpec.effectiveMinCount > 0 && insertMatches.isEmpty()) {
                    throw InvalidChangeRequestException(
                        "Cannot remove all values for field '${updateFieldDefs.first().name}' on instance '$instanceId' of type '$fqTypeName': " +
                                "at least ${primaryInsertSpec.effectiveMinCount} value(s) required"
                    )
                }

                // Targeted deletes on a single-value required field: effectiveMaxCount == 1 means
                // exactly one value can ever exist, so deleting it always empties the field — reject inline.
                if (hasTargetedDeletes && primaryInsertSpec.effectiveMinCount > 0 && primaryInsertSpec.effectiveMaxCount == 1 && insertMatches.isEmpty()) {
                    throw InvalidChangeRequestException(
                        "Cannot remove the only value for required field '${updateFieldDefs.first().name}' on instance '$instanceId' of type '$fqTypeName'"
                    )
                }

                // POST assertion for targeted deletes on fields where surviving values (outside this
                // request) might still satisfy the minimum-count constraint.
                // Not needed for single-value required fields (handled inline above).
                if (queryFieldName != null && hasTargetedDeletes && !(primaryInsertSpec.effectiveMinCount > 0 && primaryInsertSpec.effectiveMaxCount == 1)) {
                    addCountPostAssertionIfNeeded(instanceId, queryFieldName, primaryInsertSpec)
                }

                fqFieldName
            }.toSet()

            // Extra predicates not in the schema are still an error
            val extraProperties = records.filter {
                it.statement.subject == instanceId &&
                        it.statement.predicate != RDFVocab.type &&
                        it.statement.predicate !in definedProperties
            }.map { it.statement.predicate }.toSet()
            if (extraProperties.isNotEmpty()) {
                throw InvalidChangeRequestException(
                    "The instance '$instanceId' of type '$fqTypeName' has predicates that are not supported by the input schema: ${
                        extraProperties.joinToString(", ")
                    }"
                )
            }
        }
    }

    // ── POST assertion generation ─────────────────────────────────────────────

    /**
     * Appends a POST assertion when a field's cardinality cannot be fully proven inline.
     *
     * For **non-list** fields, the only meaningful check is whether a value exists at all,
     * so a simple [KvasirVocab.AssertNonEmptyResult] is used (cardinality is binary: 0 or 1).
     *
     * For **list** fields, an [KvasirVocab.AssertCountBounds] assertion is generated. The query
     * uses `pageSize: 1` so the count is obtained from the pagination `totalCount` extension
     * without loading all collection values.
     *
     * Assertions are deduplicated: an identical assertion already present in [request.assert]
     * is not added again.
     */
    private fun addCountPostAssertionIfNeeded(
        instanceId: String,
        queryFieldName: String,
        spec: FieldValidationSpec,
        includeMinCount: Boolean = true
    ) {
        val minCount = spec.effectiveMinCount.takeIf { includeMinCount && it > 0 }
        val maxCount = spec.effectiveMaxCount

        if (!spec.isList) {
            // Non-list field: "does it have a value?" is the only relevant cardinality question.
            if (minCount != null) {
                addPostAssertionIfAbsent(
                    type = KvasirVocab.AssertNonEmptyResult,
                    query = "{ $queryFieldName(id: \"$instanceId\") { ${spec.name} } }"
                )
            }
            return
        }

        // List field: use count-bounds assertion with pagination for efficient counting.
        if (minCount != null || maxCount != null) {
            addCountBoundsPostAssertionIfAbsent(
                query = "{ $queryFieldName(id: \"$instanceId\") { ${spec.name}(pageSize: 1) } }",
                fieldName = spec.name,
                minCount = minCount,
                maxCount = maxCount
            )
        }
    }

    private fun addPostAssertionIfAbsent(type: String, query: String) {
        if (request.assert.none { it.phase == AssertionPhase.POST && it.type == type && it.query == query }) {
            request.assert.add(Assertion(type = type, query = query, phase = AssertionPhase.POST))
        }
    }

    private fun addCountBoundsPostAssertionIfAbsent(query: String, fieldName: String, minCount: Int?, maxCount: Int?) {
        if (request.assert.none {
                it.phase == AssertionPhase.POST &&
                        it.type == KvasirVocab.AssertCountBounds &&
                        it.query == query &&
                        it.fieldName == fieldName &&
                        it.minCount == minCount &&
                        it.maxCount == maxCount
            }
        ) {
            request.assert.add(
                Assertion(
                    type = KvasirVocab.AssertCountBounds,
                    query = query,
                    phase = AssertionPhase.POST,
                    fieldName = fieldName,
                    minCount = minCount,
                    maxCount = maxCount
                )
            )
        }
    }

    // ── Schema helpers ────────────────────────────────────────────────────────

    /**
     * Finds the insert (`add`/`insert` prefixed mutation) input type for the given [fqTypeName].
     * Used to determine non-null and `@shape` invariants that must survive an update.
     */
    private fun findInsertInputType(fqTypeName: String): GraphQLInputObjectType? {
        return graphQLSchema.mutationType?.fieldDefinitions
            ?.filter { it.name.startsWith(MUTATION_ADD_PREFIX) || it.name.startsWith(MUTATION_INSERT_PREFIX) }
            ?.flatMap { it.arguments }
            ?.mapNotNull { arg ->
                val type = arg.type.innerType<GraphQLInputObjectType>()
                try {
                    if (getFQName(type, context) == fqTypeName) type else null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            ?.firstOrNull()
    }

    /**
     * Finds the delete (`remove`/`delete` prefixed mutation) input type for the given [fqTypeName].
     * Used to determine `@shape` constraints on what values the user is allowed to remove.
     */
    private fun findDeleteInputType(fqTypeName: String): GraphQLInputObjectType? {
        return graphQLSchema.mutationType?.fieldDefinitions
            ?.filter { it.name.startsWith(MUTATION_REMOVE_PREFIX) || it.name.startsWith(MUTATION_DELETE_PREFIX) }
            ?.flatMap { it.arguments }
            ?.mapNotNull { arg ->
                val type = arg.type.innerType<GraphQLInputObjectType>()
                try {
                    if (getFQName(type, context) == fqTypeName) type else null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            ?.firstOrNull()
    }

    /**
     * Resolves the Query field name for the given [fqTypeName] (FQ class IRI).
     * Used to construct POST assertion queries.
     */
    private fun resolveQueryFieldName(fqTypeName: String): String? {
        return graphQLSchema.queryType?.fieldDefinitions?.firstOrNull { fieldDef ->
            val returnType = fieldDef.type.innerType<GraphQLNamedType>()
            if (returnType !is GraphQLDirectiveContainer) return@firstOrNull false
            try {
                getFQName(returnType as GraphQLDirectiveContainer, context) == fqTypeName
            } catch (_: IllegalArgumentException) {
                false
            }
        }?.name
    }

    // ── Insert/delete validation ──────────────────────────────────────────────

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
            toBeValidatedRecords.remove(typeRecord)

            // Validate id field constraints
            type.fieldDefinitions.firstOrNull { it.name == FIELD_ID_NAME }?.let { idFieldDef ->
                checkValueConstraints(
                    instanceId,
                    ShapeConstraints.fromField(idFieldDef),
                    "Invalid subject for resource '$instanceId'"
                )
            }

            // Build specs for all non-id fields, grouped by FQ predicate IRI
            val definedProperties = type.fieldDefinitions
                .filter { it.name != FIELD_ID_NAME }
                .groupBy { fieldDef ->
                    JsonLdHelper.getFQName(fieldDef.name, context, "_") ?: run {
                        fieldDef.getDirectiveArg<StringValue>(DIRECTIVE_PREDICATE_NAME, ARG_IRI_NAME)
                            ?.value?.let { JsonLdHelper.getFQName(it, context) ?: it }
                    }!!
                }
                .mapValues { (fqFieldName, fieldDefs) ->
                    fieldDefs.map { FieldValidationSpec.fromField(it, fqFieldName, context) }
                }

            definedProperties.forEach { (fqFieldName, specs) ->
                val matches = records
                    .filter { it.type == operation && it.statement.subject == instanceId && it.statement.predicate == fqFieldName }
                    .toSet()

                val validationResults = specs.map { spec ->
                    isValidFieldValue(spec, operation, instanceId, fqTypeName, matches)
                }
                if (validationResults.any { it.valid }) {
                    toBeValidatedRecords.removeAll(matches)
                } else {
                    val msg = validationResults.joinToString("\n\t") {
                        "- No match via field '${it.spec.name}': ${it.error?.message}"
                    }
                    throw InvalidChangeRequestException("Invalid value for property '$fqFieldName' for instance '$instanceId' of type '$fqTypeName': $msg")
                }
            }

            val extraProperties = records
                .filter { it.type == operation && it.statement.subject == instanceId && it.statement.predicate != RDFVocab.type && it.statement.predicate !in definedProperties }
                .map { it.statement.predicate }.toSet()
            if (extraProperties.isNotEmpty()) {
                throw InvalidChangeRequestException(
                    "The instance '$instanceId' of type '$fqTypeName' has predicates that are not supported by the input schema: ${
                        extraProperties.joinToString(", ")
                    }"
                )
            }
        }
    }

    private fun isValidFieldValue(
        spec: FieldValidationSpec,
        operation: ChangeRecordType,
        instanceId: String,
        fqTypeName: String,
        matches: Set<ChangeRecord>
    ): FieldValidationResult {
        try {
            // No values: check whether absence is allowed in this context
            if (matches.isEmpty()) {
                if (!spec.absenceAllowed) {
                    val detail = if (spec.effectiveMinCount > 1)
                        "has 0 values, but at least ${spec.effectiveMinCount} are required"
                    else
                        "is required but has no value"
                    throw InvalidChangeRequestException(
                        "Property '${spec.fqName}' for instance '$instanceId' of type '$fqTypeName' $detail"
                    )
                }
                return FieldValidationResult(spec, true)
            }

            // Count bounds (values present)
            if (matches.size < spec.effectiveMinCount) {
                throw InvalidChangeRequestException(
                    "Property '${spec.fqName}' for instance '$instanceId' of type '$fqTypeName' " +
                            "has ${matches.size} values, but at least ${spec.effectiveMinCount} are required"
                )
            }
            spec.effectiveMaxCount?.let { maxCount ->
                if (matches.size > maxCount) {
                    throw InvalidChangeRequestException(
                        "Property '${spec.fqName}' for instance '$instanceId' of type '$fqTypeName' " +
                                "has ${matches.size} values, but at most $maxCount are allowed"
                    )
                }
            }

            when (val rt = spec.returnType) {
                is FieldReturnType.Id -> {
                    if (matches.any { it.statement.dataType != null }) {
                        throw InvalidChangeRequestException("Property '${spec.fqName}' for instance '$instanceId' of type '$fqTypeName' should be an IRI")
                    }
                    matches.forEach {
                        checkValueConstraints(
                            it.statement.`object`,
                            spec.constraints,
                            "Invalid object IRI for relation '${spec.fqName}' on resource '$instanceId'"
                        )
                    }
                }

                is FieldReturnType.Scalar -> {
                    val supportedRDFDataTypes = rt.scalarType.rdfDatatype()
                    if (matches.any { !supportedRDFDataTypes.contains(it.statement.dataType) && rt.scalarType.name != ExtendedScalars.Json.name }) {
                        throw InvalidChangeRequestException("Property '${spec.fqName}' for instance '$instanceId' of type '$fqTypeName' should be one of data types: $supportedRDFDataTypes")
                    }
                    matches.forEach {
                        checkValueConstraints(
                            it.statement.`object`,
                            spec.constraints,
                            "Invalid literal value for property '${spec.fqName}' on resource '$instanceId'",
                            rt.scalarType
                        )
                    }
                }

                is FieldReturnType.Complex -> {
                    val targetInstanceIds = records
                        .filter { it.type == operation && it.statement.subject == instanceId && it.statement.predicate == spec.fqName }
                        .map { it.statement.`object`.toString() }.toSet()
                    validateInstancesOfType(targetInstanceIds, rt.inputType, rt.fqTypeName, operation)
                }
            }
            return FieldValidationResult(spec, true)
        } catch (t: Throwable) {
            return FieldValidationResult(spec, false, t)
        }
    }

    // ── Value constraint checking ─────────────────────────────────────────────

    private fun checkValueConstraints(
        predicateValue: Any,
        constraints: ShapeConstraints,
        errorHeading: String,
        scalarType: GraphQLScalarType? = null
    ) {
        if (constraints.isEmpty) return
        val value = predicateValue.toString()
        val numericValue = scalarType?.takeIf { it.isNumericScalar() }?.let {
            value.toBigDecimalOrNull()
                ?: throw InvalidChangeRequestException("$errorHeading: '$value' is not a valid numeric value for scalar type '${it.name}'")
        }
        constraints.minExclusive?.let {
            if (numericValue != null) {
                if (numericValue <= it.toNumericConstraint("minExclusive")) {
                    throw InvalidChangeRequestException("$errorHeading: '$value' is not greater than the minimum exclusive value '$it'")
                }
            } else if (value <= it) {
                throw InvalidChangeRequestException("$errorHeading: '$value' is not greater than the minimum exclusive value '$it'")
            }
        }
        constraints.minInclusive?.let {
            if (numericValue != null) {
                if (numericValue < it.toNumericConstraint("minInclusive")) {
                    throw InvalidChangeRequestException("$errorHeading: '$value' is not greater than or equal to the minimum inclusive value '$it'")
                }
            } else if (value < it) {
                throw InvalidChangeRequestException("$errorHeading: '$value' is not greater than or equal to the minimum inclusive value '$it'")
            }
        }
        constraints.maxExclusive?.let {
            if (numericValue != null) {
                if (numericValue >= it.toNumericConstraint("maxExclusive")) {
                    throw InvalidChangeRequestException("$errorHeading: '$value' is not less than the maximum exclusive value '$it'")
                }
            } else if (value >= it) {
                throw InvalidChangeRequestException("$errorHeading: '$value' is not less than the maximum exclusive value '$it'")
            }
        }
        constraints.maxInclusive?.let {
            if (numericValue != null) {
                if (numericValue > it.toNumericConstraint("maxInclusive")) {
                    throw InvalidChangeRequestException("$errorHeading: '$value' is not less than or equal to the maximum inclusive value '$it'")
                }
            } else if (value > it) {
                throw InvalidChangeRequestException("$errorHeading: '$value' is not less than or equal to the maximum inclusive value '$it'")
            }
        }
        constraints.minLength?.let {
            if (value.length < it) throw InvalidChangeRequestException("$errorHeading: '$value' is shorter than the minimum length '$it'")
        }
        constraints.maxLength?.let {
            if (value.length > it) throw InvalidChangeRequestException("$errorHeading: '$value' is longer than the maximum length '$it'")
        }
        constraints.hasValue?.let {
            if (value != it) throw InvalidChangeRequestException("$errorHeading: '$value' does not match the expected value '$it'")
        }
        constraints.inValues?.let { allowed ->
            if (!allowed.contains(value)) throw InvalidChangeRequestException(
                "$errorHeading: '$value' is not in the list of allowed values '${
                    allowed.joinToString(
                        ", "
                    )
                }'"
            )
        }
        constraints.pattern?.let { pat ->
            val options = constraints.patternFlags?.let {
                if (it == "i") setOf(RegexOption.IGNORE_CASE) else throw IllegalArgumentException("Unsupported regex flag: $it")
            } ?: emptySet()
            if (!Regex(pat, options).containsMatchIn(value)) {
                throw InvalidChangeRequestException("$errorHeading: Value '$value' does not match the pattern '$pat'")
            }
        }
    }

    private fun String.toNumericConstraint(constraintName: String): BigDecimal {
        return toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Invalid numeric $constraintName constraint '$this'")
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private fun getOperationType(mutationFieldName: String): ChangeRecordType =
        if (mutationFieldName.startsWith("insert") || mutationFieldName.startsWith("add")) ChangeRecordType.INSERT
        else ChangeRecordType.DELETE

    private fun GraphQLInputObjectType.getFQName(): String {
        return (this.getDirectiveArg<StringValue>(DIRECTIVE_CLASS_NAME, ARG_IRI_NAME)?.value?.let {
            JsonLdHelper.getFQName(it, context) ?: it
        } ?: JsonLdHelper.getFQName(this.name, context, "_"))
            ?: throw IllegalArgumentException("No semantic context found for input type '${this.name}'")
    }
}

internal data class FieldValidationResult(
    val spec: FieldValidationSpec,
    val valid: Boolean,
    val error: Throwable? = null
)
