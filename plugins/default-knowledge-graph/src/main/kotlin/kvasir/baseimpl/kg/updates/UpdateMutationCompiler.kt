package kvasir.baseimpl.kg

import graphql.language.ArrayValue
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.language.VariableReference
import graphql.schema.*
import kvasir.definitions.kg.changes.Assertion
import kvasir.definitions.kg.changes.AssertionPhase
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.definitions.rdf.JsonLdKeywords
import kvasir.definitions.rdf.KvasirVocab
import kvasir.utils.graphql.MutationInputConverter
import kvasir.utils.graphql.getFQName
import kvasir.utils.graphql.innerType
import kvasir.utils.rdf.RDFTransformer

// ── UpdateGroup ───────────────────────────────────────────────────────────────

/**
 * Accumulates all change components for a single target resource during compilation of an
 * update mutation. One [UpdateGroup] is created per resource ID found in the mutation input.
 */
internal data class UpdateGroup(
    val targetId: String,
    val queryFieldName: String,
    /**
     * Fully qualified RDF class IRI for this resource's type (e.g. `http://example.org/Person`).
     * Emitted as a symmetric INSERT+DELETE pair in [UpdateMutationCompiler.compile] so that:
     * - The [kvasir.utils.graphql.ChangeRequestValidator] can detect the update pattern.
     * - The pipeline's redundant-pair filter removes both records before persistence.
     */
    val fqTypeName: String,
    /** Field names to fetch in the with-clause (required by state-dependent operations). */
    val withFields: MutableList<String> = mutableListOf(),
    /** JSONata delete template strings (blanket-remove all current values for a predicate). */
    val deleteTemplates: MutableList<String> = mutableListOf(),
    /** Targeted JSON-LD delete documents (one per value for `_remove` operations). */
    val deleteDocuments: MutableList<JSONObject> = mutableListOf(),
    /** Accumulated JSON-LD insert payload for plain `_set` / replace operations (keyed by FQ predicate). */
    val insertPayload: MutableMap<String, Any> = mutableMapOf(),
    /** JSONata insert template strings for atomic operations (`_increment`, `_append`, …). */
    val atomicInserts: MutableList<String> = mutableListOf(),
    /** Extra JSON-LD insert documents for `_add` operations (append without deleting existing values). */
    val addDocuments: MutableList<JSONObject> = mutableListOf()
) {
    init {
        insertPayload[JsonLdKeywords.id] = targetId
    }

    /** True if [insertPayload] contains any fields beyond the mandatory `@id`. */
    fun hasInserts(): Boolean = insertPayload.size > 1
}

// ── CompiledUpdate ────────────────────────────────────────────────────────────

/**
 * The output of [UpdateMutationCompiler.compile].
 *
 * @param withQuery GraphQL with-clause query; `null` when no state-dependent operations are present.
 * @param deleteItems Mix of JSONata delete template strings and targeted JSON-LD delete documents.
 * @param insertItems Mix of JSONata insert template strings and JSON-LD insert documents.
 * @param assertions PRE-phase assertions verifying that each target resource exists before the update.
 */
internal data class CompiledUpdate(
    val withQuery: String?,
    val deleteItems: List<Any>,
    val insertItems: List<Any>,
    val assertions: List<Assertion>
)

// ── UpdateMutationCompiler ────────────────────────────────────────────────────

/**
 * Translates update/set GraphQL mutations into the with/delete/insert components of a
 * [kvasir.definitions.kg.changes.ChangeRequest].
 *
 * ### Supported field types
 * - **Plain nullable scalars** — simple replace-or-delete semantics.
 * - **_Updatable\* built-in input types** — atomic operations decoded by [UpdateFieldEntry.extractUpdatableOp]:
 *   - `_set` — replace (or delete when null)
 *   - `_increment` / `_decrement` / `_multiply` — JSONata arithmetic template
 *   - `_append` / `_prepend` / `_template` — JSONata string template
 *   - `_add` — JSON-LD insert without delete (no with-clause needed)
 *   - `_remove` — targeted JSON-LD delete documents (no with-clause needed)
 *
 * ### Usage
 * Call [process] once per mutation [DataFetchingEnvironment], then call [compile] to obtain
 * the [CompiledUpdate] that is merged into the final [kvasir.definitions.kg.changes.ChangeRequest].
 *
 * Input abstraction is handled by [UpdateFieldSource] / [UpdateFieldEntry] (see [UpdateFieldSource.kt]).
 * The operation model is defined in [UpdatableOp] (see [UpdatableOp.kt]).
 */
internal class UpdateMutationCompiler(private val context: JSONObject) {

    private val groups = mutableListOf<UpdateGroup>()
    private val converter = MutationInputConverter(context)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Processes one mutation field execution environment, adding [UpdateGroup]s for every
     * resource found in the arguments.
     */
    fun process(env: DataFetchingEnvironment) {
        env.field.arguments.forEach { argument ->
            val argumentDefinition = env.fieldDefinition.getArgument(argument.name)
            val inputType = argumentDefinition.type.innerType<GraphQLInputObjectType>()
            val fqTypeName = getFQName(inputType, context)
            val queryFieldName = resolveQueryFieldName(env.graphQLSchema, inputType, fqTypeName)

            when (val argValue = argument.value) {
                is ObjectValue -> processInput(AstUpdateFieldSource(env, argValue), inputType, queryFieldName, fqTypeName)
                is ArrayValue -> argValue.values.forEach { element ->
                    when (element) {
                        is ObjectValue -> processInput(AstUpdateFieldSource(env, element), inputType, queryFieldName, fqTypeName)
                        // Variable reference inside an array literal, e.g. update(person: [$input])
                        is VariableReference -> env.variables[element.name]?.let { varValue ->
                            if (varValue is Map<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                processInput(MapUpdateFieldSource(varValue as Map<String, Any?>), inputType, queryFieldName, fqTypeName)
                            }
                        }
                        else -> {}
                    }
                }
                is VariableReference -> {
                    when (val value = env.variables[argValue.name]) {
                        is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            processInput(MapUpdateFieldSource(value as Map<String, Any?>), inputType, queryFieldName, fqTypeName)
                        }
                        is Iterable<*> -> value.filterIsInstance<Map<*, *>>().forEach {
                            @Suppress("UNCHECKED_CAST")
                            processInput(MapUpdateFieldSource(it as Map<String, Any?>), inputType, queryFieldName, fqTypeName)
                        }
                    }
                }
            }
        }
    }

    /**
     * Compiles all accumulated [UpdateGroup]s into a [CompiledUpdate].
     *
     * A with-clause is only generated when state-dependent operations (plain replaces or atomic ops)
     * are present; `_add` and `_remove` operations produce direct JSON-LD documents and do not
     * require a with-clause.
     *
     * Returns `null` if no groups have been accumulated (nothing to compile).
     */
    fun compile(): CompiledUpdate? {
        if (groups.isEmpty()) return null

        val allWithFields = mutableSetOf<String>()
        val allDeleteTemplates = mutableListOf<String>()
        val allDeleteDocuments = mutableListOf<JSONObject>()
        val allInserts = mutableListOf<Any>()
        val assertions = mutableListOf<Assertion>()

        val queryFieldName = groups.first().queryFieldName
        val targetIds = groups.map { it.targetId }

        groups.forEach { group ->
            allWithFields.addAll(group.withFields)
            allDeleteTemplates.addAll(group.deleteTemplates)
            allDeleteDocuments.addAll(group.deleteDocuments)
            // Emit a type INSERT marker for update detection in the validator.
            allInserts.add(mapOf(JsonLdKeywords.id to group.targetId, JsonLdKeywords.type to group.fqTypeName))
            // Add the type DELETE document to mirror the @type in insertPayload.
            // Together they form a redundant INSERT+DELETE pair that the pipeline filters before persistence,
            // while allowing the validator to detect the update pattern.
            allDeleteDocuments.add(mapOf(JsonLdKeywords.id to group.targetId, JsonLdKeywords.type to group.fqTypeName))
            allInserts.addAll(group.atomicInserts)
            allInserts.addAll(group.addDocuments)
            if (group.hasInserts()) allInserts.add(group.insertPayload.toMap())
        }

        // One PRE assertion per distinct target: verify the resource exists before updating.
        targetIds.distinct().forEach { targetId ->
            assertions.add(
                Assertion(
                    type = KvasirVocab.AssertNonEmptyResult,
                    query = "{ $queryFieldName(id: \"$targetId\") { id } }",
                    phase = AssertionPhase.PRE
                )
            )
        }

        val withQuery = if (allWithFields.isNotEmpty()) {
            val idFilter = if (targetIds.size == 1) {
                "id: \"${targetIds.first()}\""
            } else {
                "id: [${targetIds.joinToString(", ") { "\"$it\"" }}]"
            }
            "{ $queryFieldName($idFilter) { id ${allWithFields.joinToString(" ")} } }"
        } else null

        return CompiledUpdate(
            withQuery = withQuery,
            deleteItems = allDeleteTemplates + allDeleteDocuments,
            insertItems = allInserts,
            assertions = assertions
        )
    }

    // ── Input processing ──────────────────────────────────────────────────────

    /**
     * Processes a single update input object (one resource), creating an [UpdateGroup] and
     * dispatching each field to either the plain-replace path or the _Updatable* path.
     */
    private fun processInput(
        source: UpdateFieldSource,
        type: GraphQLInputObjectType,
        queryFieldName: String,
        fqTypeName: String
    ) {
        val idValue = source.extractId()
        val fqId = JsonLdHelper.getFQName(idValue, context) ?: idValue
        val targetId = RDFTransformer.ensureValidAbsoluteIri(fqId)
        val group = UpdateGroup(targetId, queryFieldName, fqTypeName)

        source.forEachField(type) { schemaFieldName, fieldDefinition, fieldEntry ->
            val fqFieldName = getFQName(fieldDefinition, context)
            val innerType = fieldDefinition.type.innerType<GraphQLNamedType>()

            if (innerType is GraphQLInputObjectType && innerType.name.startsWith(UPDATABLE_TYPE_PREFIX)) {
                processUpdatableField(group, queryFieldName, schemaFieldName, fqFieldName, innerType, fieldEntry)
            } else {
                applyPlainReplace(group, queryFieldName, schemaFieldName, fqFieldName, fieldDefinition, fieldEntry)
            }
        }

        groups.add(group)
    }

    /** Applies plain replace/delete semantics for non-_Updatable fields. */
    private fun applyPlainReplace(
        group: UpdateGroup,
        queryFieldName: String,
        schemaFieldName: String,
        fqFieldName: String,
        fieldDefinition: GraphQLInputObjectField,
        fieldEntry: UpdateFieldEntry
    ) {
        group.withFields.add(schemaFieldName)
        group.deleteTemplates.add(deleteTemplate(queryFieldName, fqFieldName, schemaFieldName))
        fieldEntry.toInsertValue(fieldDefinition, converter)?.let { group.insertPayload[fqFieldName] = it }
    }

    /**
     * Applies the operation encoded in a _Updatable* field value to [group].
     * The concrete effect depends on the [UpdatableOp] decoded from [fieldEntry].
     */
    private fun processUpdatableField(
        group: UpdateGroup,
        queryFieldName: String,
        schemaFieldName: String,
        fqFieldName: String,
        updatableType: GraphQLInputObjectType,
        fieldEntry: UpdateFieldEntry
    ) {
        val op = fieldEntry.extractUpdatableOp()
        // _set field (or _add for array-only types) carries the inner scalar type for value conversion.
        val setFieldDef = updatableType.getField(UPDATABLE_FIELD_SET)
            ?: updatableType.getField(UPDATABLE_FIELD_ADD)

        when (op) {
            is UpdatableOp.Set -> {
                group.withFields.add(schemaFieldName)
                group.deleteTemplates.add(deleteTemplate(queryFieldName, fqFieldName, schemaFieldName))
                if (op.rawValue != null && setFieldDef != null) {
                    group.insertPayload[fqFieldName] = convertSetValue(op.rawValue, setFieldDef)
                }
            }
            is UpdatableOp.Arithmetic -> {
                group.withFields.add(schemaFieldName)
                group.deleteTemplates.add(deleteTemplate(queryFieldName, fqFieldName, schemaFieldName))
                val current = "$queryFieldName.$schemaFieldName"
                val expr = when (op.op) {
                    UPDATABLE_FIELD_INCREMENT -> "\$number($current) + ${op.numericStr}"
                    UPDATABLE_FIELD_DECREMENT -> "\$number($current) - ${op.numericStr}"
                    UPDATABLE_FIELD_MULTIPLY  -> "\$number($current) * ${op.numericStr}"
                    else -> throw IllegalArgumentException("Unknown arithmetic op: ${op.op}")
                }
                group.atomicInserts.add(atomicInsertTemplate(queryFieldName, fqFieldName, expr))
            }
            is UpdatableOp.StringOp -> {
                group.withFields.add(schemaFieldName)
                group.deleteTemplates.add(deleteTemplate(queryFieldName, fqFieldName, schemaFieldName))
                val current = "$queryFieldName.$schemaFieldName"
                val escaped = op.str.replace("\\", "\\\\").replace("\"", "\\\"")
                val expr = when (op.op) {
                    UPDATABLE_FIELD_APPEND   -> "$current & \"$escaped\""
                    UPDATABLE_FIELD_PREPEND  -> "\"$escaped\" & $current"
                    UPDATABLE_FIELD_TEMPLATE -> "\$replace(\"$escaped\", \"{current}\", \$string($current))"
                    else -> throw IllegalArgumentException("Unknown string op: ${op.op}")
                }
                group.atomicInserts.add(atomicInsertTemplate(queryFieldName, fqFieldName, expr))
            }
            is UpdatableOp.CollectionAdd -> {
                if (setFieldDef != null && op.rawValues.isNotEmpty()) {
                    val converted = convertElements(op.rawValues, setFieldDef)
                    group.addDocuments.add(mapOf(JsonLdKeywords.id to group.targetId, fqFieldName to converted))
                }
            }
            is UpdatableOp.CollectionRemove -> {
                if (setFieldDef != null && op.rawValues.isNotEmpty()) {
                    convertElements(op.rawValues, setFieldDef).forEach { value ->
                        group.deleteDocuments.add(mapOf(JsonLdKeywords.id to group.targetId, fqFieldName to value))
                    }
                }
            }
        }
    }

    // ── Value conversion helpers ───────────────────────────────────────────────

    /** Converts a raw `_set` value (AST or JSON, scalar or list) to its JSON-LD form. */
    private fun convertSetValue(rawValue: Any, setFieldDef: GraphQLInputObjectField): Any = when {
        rawValue is ArrayValue  -> rawValue.values.map { converter.singleValueToJSON(it, setFieldDef) }
        rawValue is Value<*>    -> converter.singleValueToJSON(rawValue, setFieldDef)
        rawValue is Iterable<*> -> rawValue.map { converter.contextualizeSingleValue(it!!, setFieldDef) }
        else                    -> converter.contextualizeSingleValue(rawValue, setFieldDef)
    }

    /** Converts a list of raw `_add`/`_remove` element values (AST or JSON) to their JSON-LD forms. */
    private fun convertElements(rawValues: List<Any>, fieldDef: GraphQLInputObjectField): List<Any> =
        rawValues.map { element ->
            if (element is Value<*>) converter.singleValueToJSON(element, fieldDef)
            else converter.contextualizeSingleValue(element, fieldDef)
        }

    // ── JSONata template builders ──────────────────────────────────────────────

    /**
     * JSONata delete template: removes all current values of [fqFieldName] for the matching resource.
     * Example: `{ "@id": persons.id, "http://example.org/name": persons.ex_name }`
     */
    private fun deleteTemplate(queryFieldName: String, fqFieldName: String, schemaFieldName: String): String =
        "{ \"${JsonLdKeywords.id}\": $queryFieldName.id, \"$fqFieldName\": $queryFieldName.$schemaFieldName }"

    /**
     * JSONata insert template: sets [fqFieldName] to the result of [jsonataExpr].
     * Example: `{ "@id": counters.id, "http://example.org/value": $number(counters.ex_value) + 3 }`
     */
    private fun atomicInsertTemplate(queryFieldName: String, fqFieldName: String, jsonataExpr: String): String =
        "{ \"${JsonLdKeywords.id}\": $queryFieldName.id, \"$fqFieldName\": $jsonataExpr }"

    // ── Schema resolution ─────────────────────────────────────────────────────

    /**
     * Resolves the query field name to use in the with-clause by matching the input type's
     * fully qualified IRI against the return types of all Query fields.
     * Matching is done by FQ IRI, not by GraphQL type name.
     */
    private fun resolveQueryFieldName(
        schema: GraphQLSchema,
        inputType: GraphQLInputObjectType,
        fqTypeName: String
    ): String {
        val queryType = schema.queryType
            ?: throw IllegalArgumentException(
                "No Query type found in schema. Update mutations require a Query type for with-clause resolution."
            )
        val matchingField = queryType.fieldDefinitions.firstOrNull { fieldDef ->
            val returnType = fieldDef.type.innerType<GraphQLNamedType>()
            if (returnType !is GraphQLDirectiveContainer) return@firstOrNull false
            try {
                getFQName(returnType as GraphQLDirectiveContainer, context) == fqTypeName
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        return matchingField?.name
            ?: throw IllegalArgumentException(
                "No query path found for type '${inputType.name}' (IRI: $fqTypeName). " +
                    "Update mutations require a matching field in the Query type for with-clause resolution."
            )
    }
}
