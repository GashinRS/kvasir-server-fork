# Access Control

<show-structure depth="2"/>

> Kvasir should eventually comply to the
> [Trustflows specification](https://spec.knows.idlab.ugent.be/trustflows/all/e45c02bd3711f5734eeb75548ff37a70f57c465e/),
> which builds on UMA 2.0, implying access to a resource is controlled in a decentralized way.
>
> This page documents Kvasir's built-in access control, which is based on OpenFGA.
> {style="warning"}

## Introduction to ReBAC

Kvasir uses OpenFGA to manage access control. OpenFGA is a ReBAC (Relationship-Based Access Control) system, which means
that access control is based on relationships between users and resources. This allows for fine-grained access
control policies that can be tailored to specific use cases.

The type of relations and the implication these have on the access control policies are defined in an OpenFGA schema.
The default Kvasir schema makes some assumptions to simplify most use cases, but a custom schema may be supplied if a
different behaviour is required.

A **User** can have the following relations defined on a **Resource** (any HTTP endpoint hosted by Kvasir):

* `reader`: the user is granted read permissions on the resource.
* `writer`: the user is granted write permissions on the resource.
* `deleter`: the user is granted delete permissions on the resource.
* `owner`: the user is granted ownership of the resource, which implies both read, write and delete permissions.
* `blocked`: the user is blocked from accessing the resource, which overrides any other permissions.

A **Resource** can have a `parent` relation to another resource, which allows for hierarchical access control.

> Kvasir automatically adds the hierarchical `parent` relations for child resources.<br/>
> E.g. Read permission on `/slices/someApp`, implicitly grants read permission on `/slices/someApp/query`,
`/slices/someApp/changes`, etc.
>
> This behaviour can be modified via config.
> {style="note"}

Access checks are then resolved as follows:

- A **User** `can_read` a **Resource** if:
    - The user has a `reader` relation on the resource, or
    - The user has an `owner` relation on the resource, or
    - A `can_read` check on a parent resource returns true.
    - And the user does not have a `blocked` relation on the resource.
- A **User** `can_write` a **Resource** if:
    - The user has a `writer` relation on the resource, or
    - The user has an `owner` relation on the resource, or
    - A `can_write` check on a parent resource returns true.
    - And the user does not have a `blocked` relation on the resource.
- A **User** `can_delete` a **Resource** if:
    - The user has a `deleter` relation on the resource, or
    - The user has an `owner` relation on the resource, or
    - A `can_delete` check on a parent resource returns true.
    - And the user does not have a `blocked` relation on the resource.

## Managing access control

### Via the Kvasir UI

You can manage access control via the [Kvasir UI](Kvasir-UI.md#access-control). The UI provides a user-friendly
interface to manage access control.

### Via the ReBAC API

Kvasir provides a Linked-Data compatible Wrapper API for managing the OpenFGA relationships.

#### Listing existing relationships

For example, to list the relationships that are active on a specific Pod, you can use the following HTTP request:

**GET** `https://kvasir.example.org/alice/rebac/relationships`

Returns:

```json
{
  "@id": "urn:kvasir-user:alice",
  "@type": "kss-fga:User",
  "kss-fga:owner": {
    "@id": "https://kvasir.example.org/alice",
    "@type": "kss-fga:Resource"
  },
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "kss-fga": "https://kvasir.discover.ilabt.imec.be/fine-grained-access#"
  }
}
```

#### Adding or removing a relationship

To add a relationship, you can use the following HTTP request. For example, to allow a user `bob` to read a specific
Slice and all its child resources:

**POST** `https://kvasir.example.org/alice/rebac/relationships`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "kss-fga": "https://kvasir.discover.ilabt.imec.be/fine-grained-access#"
  },
  "kss:insert": [
    {
      "@id": "urn:kvasir-user:bob",
      "@type": "kss-fga:User",
      "kss-fga:reader": {
        "@id": "https://kvasir.example.org/slices/music-tracker",
        "@type": "kss-fga:Resource"
      }
    }
  ]
}
```

Removing a relationship is analogous, but using the `kss:delete` method instead of `kss:insert`.

Note that the `@id` of the user must be a valid URI, hence the `urn:kvasir-user:bob` format for representing the user
`bob` which is known in the `quarkus` realm of the embedded Keycloak server. The user id could also be an email
address (e.g. represented as `mailto:bob@example.org`) or a full URI (e.g. a WebID
`https://kvasir.example.org/users/bob`), depending on the configured OIDC provider for the Pod.

#### Check access

To check if a user has access to a resource, you can use the following HTTP request:

**POST** `https://kvasir.example.org/alice/rebac/check`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "kss-fga": "https://kvasir.discover.ilabt.imec.be/fine-grained-access#"
  },
  "@id": "urn:kvasir-user:bob",
  "@type": "kss-fga:User",
  "kss-fga:can_read": {
    "@id": "https://kvasir.example.org/slices/music-tracker/query",
    "@type": "kss-fga:Resource"
  }
}
```

Returns:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "kss-fga": "https://kvasir.discover.ilabt.imec.be/fine-grained-access#"
  },
  "kss-fga:allowed": true
}
```