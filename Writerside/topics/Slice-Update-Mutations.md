# Update mutations

<show-structure depth="2"/>

Update mutations let clients perform **partial, in-place modifications** to existing resources through a Slice's GraphQL
interface. Only the fields that are explicitly supplied are touched; absent fields retain their current values.

This page covers the full mechanics of update mutations: how they are detected, how constraints are enforced, the
available atomic operations, and delete semantics within an update context.

> For a quick introduction and a minimal example, see [Mutation type — Update mutations](Slices.md#update-mutations).
> {style="note"}

## Defining update mutations

An update mutation is any mutation whose name starts with `update` or `set` — both prefixes are equivalent and the
choice is purely a matter of the Slice author's preference (e.g. `updatePerson` or `setPerson`).

The key difference from add/remove mutations is the **split input type pattern**: you define a dedicated update input
type alongside the insert input type. The two types serve different purposes:

| Type | Prefix | Field nullability | Role |
|---|---|---|---|
| Insert input (`add` / `insert`) | Required (`!`) on mandatory fields | Defines what a valid, complete resource looks like |
| Update input (`update` / `set`) | All fields nullable (optional) | Defines which fields may be changed in a single request |

```graphql
type Mutation {
  add(person: [PersonInput!]!): ID!
  update(person: [PersonUpdateInput!]!): ID!
}

input PersonInput @class(iri: "ex:Person") {
  id: ID!
  ex_givenName: String! @shape(minLength: 2)
  ex_familyName: String!
  ex_score: Int! @shape(maxInclusive: "100")
}

input PersonUpdateInput @class(iri: "ex:Person") {
  id: ID!
  ex_givenName: String
  ex_score: _UpdatableInt
}
```

> Both input types must use the same `@class(iri: ...)` value (or the same context-resolvable name). Kvasir uses this
> to correlate the two types and enforce insert-type constraints during updates.
> {style="note"}

## How Kvasir detects an update

When a mutation is compiled, Kvasir emits a symmetric `rdf:type` INSERT + DELETE pair for each target resource. The
validator recognises this pattern and switches to **relaxed update mode** for those resources:

- Missing fields are not an error (partial update by design).
- The insert input type's constraints are still enforced on any new values being written.
- Extra predicates not present in the update input type are rejected.

## GraphQL update vs. raw ChangeRequest

Slice GraphQL updates and raw Changes API requests follow the same underlying rule: update semantics are enabled only
when a resource appears in both delete and insert intent.

For GraphQL updates, this is handled automatically by the compiler, which emits an `rdf:type` delete+insert pair for
the updated subject.

For raw ChangeRequest payloads, clients must include this mixed intent explicitly:

- A `kss:delete`-only request is treated as a delete operation.
- To express "update by removing a field" (for example, setting a field to `null`), also include at least one insert for
  the same subject (typically `rdf:type`).

**Delete-only (delete semantics)**

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": "http://example.org/",
    "so": "http://schema.org/"
  },
  "kss:delete": [
    {
      "@id": "ex:john",
      "so:email": "jdoe@example.org"
    }
  ]
}
```

**Delete + insert on same subject (update semantics)**

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "ex": "http://example.org/",
    "so": "http://schema.org/"
  },
  "kss:delete": [
    {
      "@id": "ex:john",
      "so:email": "jdoe@example.org"
    }
  ],
  "kss:insert": [
    {
      "@id": "ex:john",
      "@type": "ex:Person"
    }
  ]
}
```

## Constraint inheritance

The insert input type is the **source of truth** for what a valid resource looks like. Its `@shape` constraints are
inherited during every update, even when the field is not listed in the update input type.

When the same field appears in both types, the **stricter bound wins**:

- Lower bounds (`minInclusive`, `minExclusive`, `minLength`, `minCount`): the larger value wins.
- Upper bounds (`maxInclusive`, `maxExclusive`, `maxLength`, `maxCount`): the smaller value wins.
- `pattern`, `hasValue`, `inValues`: the insert type takes precedence.

Numeric constraints (`minInclusive`, `maxInclusive`, `minExclusive`, `maxExclusive`) on `Int` and `Float` fields are
compared **numerically**, not lexicographically, so `"10"` is correctly treated as greater than `"9"`.

## Inline validation vs. POST assertions

Kvasir validates change requests before and after they are applied:

- **Inline (PRE)** — the validator checks the new value directly from the mutation input. If a constraint is violated,
  the change is rejected immediately and nothing is written.
- **POST assertion** — generated for cases where the final state can only be known after looking at existing data (for
  example, a targeted delete of one item from a list where other items may still be needed to satisfy `minCount`). If the assertion
  fails, the change is rolled back with status `ASSERTION_FAILED`.

All standard field assignments and atomic operations produce a **known, deterministic value** that is validated inline.
POST assertions are only generated for targeted deletes when the field has `minCount > 0` and may still satisfy that bound through values
outside the current request.

In addition, every update mutation generates a **PRE assertion** verifying that the target resource exists before the
update is applied. Updating a non-existent resource results in `ASSERTION_FAILED`.

## Atomic operations with `_Updatable*` types

For numeric and string fields, Kvasir provides built-in `_Updatable*` input types that expose atomic operations. Use
these in the update input type instead of the plain scalar type:

```graphql
input PersonUpdateInput @class(iri: "ex:Person") {
  id: ID!
  ex_score: _UpdatableInt
  ex_bio: _UpdatableString
  ex_tags: _UpdatableStringArray
}
```

### Available types and operations

| Type | Operations |
|---|---|
| `_UpdatableInt` | `_set`, `_increment`, `_decrement` |
| `_UpdatableFloat` | `_set`, `_increment`, `_decrement`, `_multiply` |
| `_UpdatableString` | `_set`, `_append`, `_prepend`, `_template` |
| `_UpdatableBoolean` | `_set` |
| `_UpdatableID` | `_set` |
| `_UpdatableDateTime` | `_set` |
| `_UpdatableDate` | `_set` |
| `_UpdatableTime` | `_set` |
| `_UpdatableIntArray` | `_set`, `_add`, `_remove` |
| `_UpdatableFloatArray` | `_set`, `_add`, `_remove` |
| `_UpdatableStringArray` | `_set`, `_add`, `_remove` |
| `_UpdatableIDArray` | `_set`, `_add`, `_remove` |

### Operation semantics

**`_set`** — replaces the current value. This is equivalent to assigning a value directly on a non-`_Updatable*`
update field (i.e. the behavior you get when no `_Updatable*` type is used). Pass `null` to delete the field entirely
(subject to required-field constraints).

```graphql
mutation { update(person: { id: "ex:jdoe", ex_givenName: { _set: "Jane" } }) }
```

**`_increment` / `_decrement`** — adds or subtracts the given amount from the current value. The resulting value is
known at validation time (Kvasir resolves the current value via a with-clause before applying the change), so constraint
violations are caught inline — no POST assertion is needed.

```graphql
mutation { update(person: { id: "ex:jdoe", ex_score: { _increment: 5 } }) }
```

**`_multiply`** — multiplies the current value by the given factor (Float fields only).

**`_append` / `_prepend`** — concatenates the given string to the end or beginning of the current value.

**`_template`** — replaces `{current}` in the given template string with the current value.

```graphql
mutation { update(person: { id: "ex:jdoe", ex_bio: { _template: "Formerly known as {current}" } }) }
```

**`_add` / `_remove`** (array types) — adds or removes specific elements without affecting the rest of the list.
`_remove` on a field with `minCount` can generate a POST assertion to verify the resulting collection still satisfies
its minimum. `_add` on a field with `maxCount` generates a POST count-bound assertion when the Change Request is
state-dependent; otherwise it is rejected because post-state count validation requires state-dependent execution.

```graphql
mutation { update(person: { id: "ex:jdoe", ex_tags: { _add: "kotlin" } }) }
```

## Delete semantics within an update

When an update removes values, Kvasir classifies each delete as either **blanket** (all current values for a predicate
are removed, typically from a JSONata template) or **targeted** (a specific value is removed, from a JSON-LD document,
e.g. via `_remove`).

The following rules apply, based on the insert input type's constraints:

| Scenario | Outcome |
|---|---|
| Blanket delete of a required (`!`) field, no new value supplied | Rejected inline — required field cannot be emptied |
| Targeted delete of a non-list required field, no new value supplied | Rejected inline — the field has only one value, so it would always become empty |
| Targeted delete of a list field with `minCount > 1` and no replacement value | POST assertion generated to verify enough values survive the delete |
| Update with array `_add` on a field with `maxCount` | POST count-bound assertion when state-dependent; rejected otherwise |

## The delete input type (access boundary)

You may optionally define a `remove`/`delete`-prefixed input type alongside the update type. Its `@shape` constraints
define **which values a caller is allowed to remove** — a security and access-control boundary enforced by the Slice
schema. For delete validation, the delete type's constraints are merged with the update type's constraints (stricter
bound wins). Insert-type constraints are handled separately in update validation and are not merged into delete specs.

```graphql
input PersonRemoveInput @class(iri: "ex:Person") {
  id: ID!
  ex_tags: [String!] @shape(pattern: "^user-.*")   # callers may only remove tags starting with "user-"
}
```

## Complete example — a scored counter

The following example brings all the pieces together: a `Counter` with a score that must stay in the range `[0, 100]`.

**Schema**

```graphql
type Query {
  counters: [ex_Counter!]!
}

type Mutation {
  add(counter: [CounterInput!]!): ID!
  update(counter: [CounterUpdateInput!]!): ID!
}

type ex_Counter {
  id: ID!
  ex_score: Int!
}

input CounterInput @class(iri: "ex:Counter") {
  id: ID!
  ex_score: Int! @shape(minInclusive: "0", maxInclusive: "100")
}

input CounterUpdateInput @class(iri: "ex:Counter") {
  id: ID!
  ex_score: _UpdatableInt
}
```

**Insert a counter**

```graphql
mutation {
  add(counter: { id: "ex:c1", ex_score: 42 })
}
```

**Increment within bounds — succeeds**

```graphql
mutation {
  update(counter: { id: "ex:c1", ex_score: { _increment: 10 } })  # 42 + 10 = 52 ≤ 100 ✓
}
```

**Increment past the maximum — rejected inline**

```graphql
mutation {
  update(counter: { id: "ex:c1", ex_score: { _increment: 60 } })  # 42 + 60 = 102 > 100 ✗
}
```

Because the resulting value is fully resolved before validation, the `maxInclusive: "100"` constraint is evaluated
inline and the change is rejected before any data is written.

<seealso>
    <category ref="related">
        <a href="Slices.md">Slices</a>
        <a href="Walkthrough.md">Walkthrough</a>
        <a href="Changes.md">Changes API</a>
    </category>
</seealso>

