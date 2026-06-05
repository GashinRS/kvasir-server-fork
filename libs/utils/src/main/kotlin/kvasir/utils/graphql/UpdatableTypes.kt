package kvasir.utils.graphql

/**
 * SDL definitions for the built-in _Updatable* input types.
 *
 * These types allow GraphQL update mutations to express atomic operations on fields
 * (increment, decrement, append, add to collection, remove from collection, etc.)
 * beyond simple value replacement.
 *
 * Usage: instead of a plain nullable scalar in an update input type, use the corresponding
 * _Updatable* type. Provide exactly one operation field per use.
 *
 * The underscore prefix ensures no conflicts with user-defined types (Slice authors are
 * not permitted to define types starting with an underscore).
 *
 * Scalar variants:
 * - _UpdatableInt      : _set / _increment / _decrement
 * - _UpdatableFloat    : _set / _increment / _decrement / _multiply
 * - _UpdatableString   : _set / _append / _prepend / _template
 * - _UpdatableBoolean  : _set
 * - _UpdatableID       : _set
 * - _UpdatableDateTime : _set
 * - _UpdatableDate     : _set
 * - _UpdatableTime     : _set
 *
 * Array/collection variants (set-semantics; duplicates handled by the underlying RDF store):
 * - _UpdatableIntArray      : _set / _add / _remove
 * - _UpdatableFloatArray    : _set / _add / _remove
 * - _UpdatableStringArray   : _set / _add / _remove
 * - _UpdatableIDArray       : _set / _add / _remove
 * - _UpdatableDateTimeArray : _set / _add / _remove
 * - _UpdatableDateArray     : _set / _add / _remove
 * - _UpdatableTimeArray     : _set / _add / _remove
 * - _UpdatableBooleanArray  : _set / _add / _remove
 */
const val UPDATABLE_TYPES_SDL = """
input _UpdatableInt {
  _set: Int
  _increment: Int
  _decrement: Int
}
input _UpdatableFloat {
  _set: Float
  _increment: Float
  _decrement: Float
  _multiply: Float
}
input _UpdatableString {
  _set: String
  _append: String
  _prepend: String
  _template: String
}
input _UpdatableBoolean {
  _set: Boolean
}
input _UpdatableID {
  _set: ID
}
input _UpdatableDateTime {
  _set: DateTime
}
input _UpdatableDate {
  _set: Date
}
input _UpdatableTime {
  _set: Time
}
input _UpdatableIntArray {
  _set: [Int!]
  _add: [Int!]
  _remove: [Int!]
}
input _UpdatableFloatArray {
  _set: [Float!]
  _add: [Float!]
  _remove: [Float!]
}
input _UpdatableStringArray {
  _set: [String!]
  _add: [String!]
  _remove: [String!]
}
input _UpdatableIDArray {
  _set: [ID!]
  _add: [ID!]
  _remove: [ID!]
}
input _UpdatableDateTimeArray {
  _set: [DateTime!]
  _add: [DateTime!]
  _remove: [DateTime!]
}
input _UpdatableDateArray {
  _set: [Date!]
  _add: [Date!]
  _remove: [Date!]
}
input _UpdatableTimeArray {
  _set: [Time!]
  _add: [Time!]
  _remove: [Time!]
}
input _UpdatableBooleanArray {
  _set: [Boolean!]
  _add: [Boolean!]
  _remove: [Boolean!]
}
"""

