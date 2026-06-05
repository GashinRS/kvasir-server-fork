package kvasir.baseimpl.kg

import graphql.language.Value

/**
 * Represents the resolved operation extracted from a _Updatable* input object.
 *
 * Exactly one operation field should be set per usage (enforced at the application level,
 * since graphql-java does not yet support `@oneOf`).
 *
 * Instances are produced by [UpdateFieldEntry.extractUpdatableOp] for both AST and JSON inputs.
 */
internal sealed interface UpdatableOp {

    /**
     * Replace (`_set: value`) or remove (`_set: null`) the current value.
     * [rawValue] is either a GraphQL AST [Value] or a deserialized JSON value; `null` signals deletion.
     */
    data class Set(val rawValue: Any?) : UpdatableOp

    /**
     * Arithmetic operation on a numeric scalar field.
     * [op] is one of `_increment`, `_decrement`, `_multiply`.
     * [numericStr] is the operand serialised as a string for embedding in a JSONata template.
     */
    data class Arithmetic(val op: String, val numericStr: String) : UpdatableOp

    /**
     * String mutation operation.
     * [op] is one of `_append`, `_prepend`, `_template`.
     * [str] is the operand string value.
     */
    data class StringOp(val op: String, val str: String) : UpdatableOp

    /**
     * Append [rawValues] to the current collection without removing existing values (`_add`).
     * Elements are AST [Value] nodes (inline literals) or plain JSON values (variables).
     */
    data class CollectionAdd(val rawValues: List<Any>) : UpdatableOp

    /**
     * Remove specific [rawValues] from the current collection using targeted JSON-LD delete
     * documents (`_remove`). One delete document is produced per value.
     */
    data class CollectionRemove(val rawValues: List<Any>) : UpdatableOp
}

