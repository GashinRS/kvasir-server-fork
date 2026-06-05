package kvasir.utils.graphql

import graphql.Scalars
import graphql.language.ArrayValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLScalarType
import kvasir.definitions.kg.exceptions.InvalidChangeRequestException
import kvasir.definitions.kg.graphql.*
import kvasir.definitions.rdf.JSONObject
import kvasir.definitions.rdf.JsonLdHelper
import kvasir.utils.graphql.FieldValidationSpec.Companion.fromField
import kvasir.utils.graphql.ShapeConstraints.Companion.fromField
import kvasir.utils.graphql.ShapeConstraints.Companion.merge

// ── FieldReturnType ───────────────────────────────────────────────────────────

/**
 * Describes what kind of value a field holds, independent of the GraphQL schema API.
 * Used by [FieldValidationSpec] to drive data-type checks in [ChangeRequestValidator].
 */
internal sealed interface FieldReturnType {
    /** The field holds an IRI reference (a relationship to another resource). */
    data object Id : FieldReturnType

    /** The field holds a plain scalar literal; [scalarType] determines the allowed RDF data types. */
    data class Scalar(val scalarType: GraphQLScalarType) : FieldReturnType

    /**
     * The field is typed as a complex nested input object.
     * [inputType] is the GraphQL definition; [fqTypeName] is its fully qualified RDF class IRI.
     */
    data class Complex(val inputType: GraphQLInputObjectType, val fqTypeName: String) : FieldReturnType
}

// ── ShapeConstraints ──────────────────────────────────────────────────────────

/**
 * All constraint values extracted from one or more `@shape` directive instances on a field.
 *
 * Constructed via [fromField] for a single field definition, or combined with [merge] to
 * produce the **stricter** of two constraint sets (e.g. insert type + update type).
 *
 * ### Merging semantics
 * - Lower bounds (`min*`, `minLength`, `minCount`): larger value wins (more restrictive).
 * - Upper bounds (`max*`, `maxLength`, `maxCount`): smaller value wins (more restrictive).
 * - `pattern` / `hasValue` / `inValues`: the **base** (insert type) takes precedence.
 */
internal data class ShapeConstraints(
    val minInclusive: String? = null,
    val maxInclusive: String? = null,
    val minExclusive: String? = null,
    val maxExclusive: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val patternFlags: String? = null,
    val hasValue: String? = null,
    val inValues: List<String>? = null,
    val minCount: Int? = null,
    val maxCount: Int? = null
) {
    val isEmpty: Boolean get() = this == EMPTY

    companion object {
        val EMPTY = ShapeConstraints()

        /** Extracts all `@shape` constraint values from a [GraphQLInputObjectField]. */
        fun fromField(field: GraphQLInputObjectField): ShapeConstraints = ShapeConstraints(
            minInclusive = field.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_INCLUSIVE_NAME)?.value,
            maxInclusive = field.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_MAX_INCLUSIVE_NAME)?.value,
            minExclusive = field.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_EXCLUSIVE_NAME)?.value,
            maxExclusive = field.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_MAX_EXCLUSIVE_NAME)?.value,
            minLength = field.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_LENGTH_NAME)?.value?.toInt(),
            maxLength = field.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MAX_LENGTH_NAME)?.value?.toInt(),
            pattern = field.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_PATTERN_NAME)?.value,
            patternFlags = field.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_FLAGS_NAME)?.value,
            hasValue = field.getDirectiveArg<StringValue>(DIRECTIVE_SHAPE_NAME, ARG_HAS_VALUE_NAME)?.value,
            inValues = field.getDirectiveArg<ArrayValue>(DIRECTIVE_SHAPE_NAME, ARG_IN_NAME)
                ?.values?.filterIsInstance<StringValue>()?.map { it.value },
            minCount = field.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MIN_COUNT_NAME)?.value?.toInt(),
            maxCount = field.getDirectiveArg<IntValue>(DIRECTIVE_SHAPE_NAME, ARG_MAX_COUNT_NAME)?.value?.toInt()
        )

        /**
         * Merges [base] and [override] constraint sets, always taking the stricter bound.
         * The [base] (typically the insert input type) is authoritative for `pattern`,
         * `hasValue`, and `inValues`.
         */
        fun merge(base: ShapeConstraints, override: ShapeConstraints, numericBounds: Boolean = false): ShapeConstraints = ShapeConstraints(
            minInclusive = stricterLowerBound(base.minInclusive, override.minInclusive, numericBounds),
            maxInclusive = stricterUpperBound(base.maxInclusive, override.maxInclusive, numericBounds),
            minExclusive = stricterLowerBound(base.minExclusive, override.minExclusive, numericBounds),
            maxExclusive = stricterUpperBound(base.maxExclusive, override.maxExclusive, numericBounds),
            minLength = listOfNotNull(base.minLength, override.minLength).maxOrNull(),
            maxLength = listOfNotNull(base.maxLength, override.maxLength).minOrNull(),
            pattern = base.pattern ?: override.pattern,
            patternFlags = base.patternFlags ?: override.patternFlags,
            hasValue = base.hasValue ?: override.hasValue,
            inValues = base.inValues ?: override.inValues,
            minCount = listOfNotNull(base.minCount, override.minCount).maxOrNull(),
            maxCount = listOfNotNull(base.maxCount, override.maxCount).minOrNull()
        )

        private fun stricterLowerBound(first: String?, second: String?, numericBounds: Boolean): String? {
            return pickBound(first, second, numericBounds) { it >= 0 }
        }

        private fun stricterUpperBound(first: String?, second: String?, numericBounds: Boolean): String? {
            return pickBound(first, second, numericBounds) { it <= 0 }
        }

        private fun pickBound(
            first: String?,
            second: String?,
            numericBounds: Boolean,
            chooseFirst: (Int) -> Boolean
        ): String? {
            if (first == null) return second
            if (second == null) return first

            return if (chooseFirst(compareConstraintValues(first, second, numericBounds))) first else second
        }

        private fun compareConstraintValues(first: String, second: String, numericBounds: Boolean): Int {
            return if (numericBounds) {
                val left = first.toBigDecimalOrNull() ?: throw IllegalArgumentException("Invalid numeric constraint value '$first'")
                val right = second.toBigDecimalOrNull() ?: throw IllegalArgumentException("Invalid numeric constraint value '$second'")
                left.compareTo(right)
            } else {
                first.compareTo(second)
            }
        }
    }
}

// ── FieldValidationSpec ───────────────────────────────────────────────────────

/**
 * Encapsulates everything needed to validate the records for a single field, independent of
 * the GraphQL schema API.
 *
 * ### Construction
 * - **Insert / delete** path: use [fromField] directly.
 * - **Update** path: use [fromField] then [asUpdateSpec] to merge in insert-type invariants
 *   and set `partialContext = true` (partial updates always allow missing fields).
 *
 * ### Cardinality
 * The effective bounds are derived from schema-level type wrapping combined with `@shape` constraints:
 * - [effectiveMinCount] — the larger of `1` (non-null field) and `@shape(minCount)`.
 * - [effectiveMaxCount] — the smaller of `1` (non-list field) and `@shape(maxCount)`; `null` = unbounded.
 * - [absenceAllowed] — whether zero values is acceptable in this context.
 *
 * @param name GraphQL field name; used in error messages and assertion queries.
 * @param fqName Fully qualified RDF predicate IRI.
 * @param optional Whether the field's GraphQL type is nullable (i.e. not wrapped in `!`).
 * @param partialContext Whether field absence is always acceptable regardless of cardinality.
 *                       Always `true` for update specs (partial updates may omit any field).
 * @param isList Whether the field permits multiple values.
 *               Set to `true` for `_Updatable*Array` built-in fields even though the
 *               GraphQL type itself is not wrapped in `[…]`.
 * @param returnType The kind of value this field holds.
 * @param constraints Merged `@shape` constraints from one or more field definitions.
 */
internal data class FieldValidationSpec(
    val name: String,
    val fqName: String,
    val optional: Boolean,
    val partialContext: Boolean = false,
    val isList: Boolean,
    val returnType: FieldReturnType,
    val constraints: ShapeConstraints
) {
    /**
     * Effective minimum number of values required for this field.
     * - A non-null field (`optional = false`) implies at least 1.
     * - `@shape(minCount: N)` can raise this further.
     * The stricter (larger) value wins.
     */
    val effectiveMinCount: Int get() = maxOf(constraints.minCount ?: 0, if (optional) 0 else 1)

    /**
     * Effective maximum number of values allowed for this field, or `null` if unbounded.
     * - A non-list field (`isList = false`) implies at most 1.
     * - `@shape(maxCount: N)` can lower this further.
     * The stricter (smaller) value wins.
     */
    val effectiveMaxCount: Int? get() = listOfNotNull(constraints.maxCount, if (!isList) 1 else null).minOrNull()

    /**
     * Whether zero values (field absence) is acceptable in this validation context.
     * True when [partialContext] is set (e.g. partial update), or when [effectiveMinCount] is 0.
     */
    val absenceAllowed: Boolean get() = partialContext || effectiveMinCount == 0

    /**
     * Returns a copy of this spec suitable for **update** validation:
     * - `partialContext = true` (partial updates allow any field to be absent).
     * - `constraints` merged with [insertConstraints] so insert-type invariants are preserved.
     */
    fun asUpdateSpec(insertConstraints: ShapeConstraints): FieldValidationSpec = copy(
        partialContext = true,
        constraints = merge(
            insertConstraints,
            constraints,
            numericBounds = (returnType as? FieldReturnType.Scalar)?.scalarType?.isNumericScalar() == true
        )
    )

    companion object {
        /**
         * Builds a [FieldValidationSpec] from a [GraphQLInputObjectField].
         *
         * @param field The GraphQL input object field definition.
         * @param fqName Pre-resolved fully qualified RDF predicate IRI for this field.
         * @param context JSON-LD context used to resolve complex type class IRIs.
         */
        fun fromField(field: GraphQLInputObjectField, fqName: String, context: JSONObject): FieldValidationSpec {
            val innerType = field.type.innerType<GraphQLInputType>()
            val (returnType, isUpdatableArray) = resolveReturnType(innerType, context)
            return FieldValidationSpec(
                name = field.name,
                fqName = fqName,
                optional = field.type.isNullable(),
                isList = field.type.isList() || isUpdatableArray,
                returnType = returnType,
                constraints = fromField(field)
            )
        }

        /**
         * Resolves the [FieldReturnType] for a [GraphQLInputType].
         *
         * Returns a pair of:
         * - The resolved [FieldReturnType] (with `_Updatable*` types mapped to their underlying scalar).
         * - A boolean that is `true` when the type is a `_Updatable*Array` built-in, indicating that
         *   the field should be treated as a list even though it isn't wrapped in `[…]` in the schema.
         */
        internal fun resolveReturnType(type: GraphQLInputType, context: JSONObject): Pair<FieldReturnType, Boolean> =
            when {
                type == Scalars.GraphQLID ->
                    FieldReturnType.Id to false

                type is GraphQLInputObjectType && type.name.startsWith(UPDATABLE_TYPE_PREFIX) -> {
                    val isArray = type.name.endsWith("Array")
                    FieldReturnType.Scalar(resolveUpdatableScalar(type)) to isArray
                }

                type.isScalar() ->
                    FieldReturnType.Scalar(type.innerType<GraphQLScalarType>()) to false

                type is GraphQLInputObjectType -> {
                    val fqTypeName =
                        (type.getDirectiveArg<StringValue>(DIRECTIVE_CLASS_NAME, ARG_IRI_NAME)?.value
                            ?.let { JsonLdHelper.getFQName(it, context) ?: it })
                            ?: JsonLdHelper.getFQName(type.name, context, "_")
                            ?: throw IllegalArgumentException("No semantic context found for input type '${type.name}'")
                    FieldReturnType.Complex(type, fqTypeName) to false
                }

                else -> throw IllegalArgumentException("Unsupported field type: $type")
            }

        /**
         * Maps a `_Updatable*` built-in input type to its underlying [GraphQLScalarType].
         * Array variants (e.g. `_UpdatableStringArray`) resolve to the same scalar as `_UpdatableString`.
         */
        internal fun resolveUpdatableScalar(type: GraphQLInputObjectType): GraphQLScalarType {
            val baseName = type.name.removePrefix(UPDATABLE_TYPE_PREFIX).removeSuffix("Array")
            return when (baseName) {
                "Int" -> Scalars.GraphQLInt
                "Float" -> Scalars.GraphQLFloat
                "String" -> Scalars.GraphQLString
                "Boolean" -> Scalars.GraphQLBoolean
                "ID" -> Scalars.GraphQLID
                "DateTime" -> ExtendedScalars.DateTime
                "Date" -> ExtendedScalars.Date
                "Time" -> ExtendedScalars.Time
                else -> throw InvalidChangeRequestException("Unsupported _Updatable* built-in type: '${type.name}'")
            }
        }
    }
}

