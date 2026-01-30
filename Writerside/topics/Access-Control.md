# Access Control

Kvasir uses OpenFGA to manage access control. OpenFGA is a ReBAC (Relationship-Based Access Control) system, which means
that access control is based on relationships between users and resources. This allows for fine-grained access
control policies that can be tailored to specific use cases.

## Introduction to ReBAC

The type of relations and the implication these have on the access control policies are defined in an OpenFGA schema.
The default Kvasir schema makes some assumptions to simplify most use cases, but a custom schema may be supplied if a
different behaviour is required.

A **User** can have the following relations defined on a **Resource** (any HTTP endpoint hosted by Kvasir):

- `reader`: the user is granted read permissions on the resource.
- `writer`: the user is granted write permissions on the resource.
- `deleter`: the user is granted delete permissions on the resource.
- `owner`: the user is granted ownership of the resource, which implies both read, write and delete permissions.
- `blocked`: the user is blocked from accessing the resource, which overrides any other permissions.

A **Resource** can have a `parent` relation to another resource, which allows for hierarchical access control.

> Kvasir automatically adds the hierarchical `parent` relations for child resources.<br/>
> E.g. Read permission on `/slices/someApp`, implicitly grants read permission on `/slices/someApp/query`,
> `/slices/someApp/changes`, etc.
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

There are two special user identifiers that can be used:

- `urn:kvasir-user:anonymous`: represents any unauthenticated user.
- `urn:kvasir-wildcard`: represents any user, authenticated or not.

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

## Delegating to external systems

### A4DS/UMA 2.0 Authorization Servers

When a [UMA server is configured for a Pod](Pod-Management.md#uma-configuration), Kvasir can delegate policy decisions
for specific resources to that server.

This is done by adding a delegation rule via the [ReBAC API](#adding-or-removing-a-relationship)
or [Kvasir UI](Kvasir-UI.md#access-control), for
example:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "kss-fga": "https://kvasir.discover.ilabt.imec.be/fine-grained-access#"
  },
  "kss:insert": [
    {
      "@id": "urn:kvasir-wildcard",
      "@type": "kss-fga:User",
      "kss-fga:owner": {
        "@id": "https://kvasir.example.org/slices/",
        "@type": "kss-fga:Resource",
        "kss-fga:external_access": {
          "@id": "kss-fga:Uma"
        }
      }
    }
  ]
}
```

Read this relationship as: _"When no direct access is granted by regular OpenFGA rules, delegate policy decisions up
to `owner`-level access, to the external system identified by `kss-fga:uma`, regardless of the requesting user."_

It is important to note that:

- Internal OpenFGA relationships always take precedence over delegated access control.
- Delegation rules can only be defined for the wildcard user (`urn:kvasir-wildcard`). Delegation for specific users
  only, would not make sense, as the external system is responsible for evaluating user permissions.
- A delegation rule cannot be combined with a regular relation of the same type on the same resource. E.g. in this case
  it is not possible to also grant all users owner-level access to `/slices/` via a regular OpenFGA relationship. This
  also would not make sense, as the external system would never be consulted.

Alternatively, UMA delegation for the entire Pod can also be activated at Pod creation time, via the
`auto-register-uma` (using bootstrap config) or `kss:autoRegisterUma` (using the API) properties.

When enabling the <tooltip term="A4DS">A4DS</tooltip> delegation, Kvasir acts as a UMA Resource Server, delegating
authentication and authorization
to a configured <tooltip term="A4DS">A4DS</tooltip>/UMA-compliant Authorization Server.
The <tooltip term="A4DS">A4DS</tooltip> implementation is being developed and tested using
the [KNoWS UMA Authorization Server](https://github.com/SolidLabResearch/user-managed-access) as a reference. For any
questions you may have regarding setting up an Authorization server, configuring policies, how to create and
authenticate users & clients, etc; please refer to the documentation of the Authorization Server you are using.

Contact information for the KNoWS group can be found at [](https://knows.idlab.ugent.be).

### How it works

For each request to an API endpoint, Kvasir checks for a Bearer token in the `Authorization` header.

- If no token is present, Kvasir will create a ticket with the configured Authorization Server (AS), using the
  `permission_endpoint` in the UMA configuration of the <tooltip term="AS">AS</tooltip> (retrieved by performing a GET
  at `/.well-known/uma2-configuration`).
    - If the <tooltip term="AS">AS</tooltip> responds with a `201 Created` status code, Kvasir will forward the ticket,
      along with the URL of the <tooltip term="AS">AS</tooltip>, to the client in a `WWW-Authenticate` header by
      responding with a `401 Unauthorized` status code.
    - If the <tooltip term="AS">AS</tooltip> responds with a `200 OK` status code, Kvasir allows the requests to
      proceed (the Resource represented by the API call is a public Resource).
    - For any other response code, Kvasir will respond with a `401 Unauthorized` status code (without `WWW-Authenticate`
      challenge).
- If a token is present, it is validated by Kvasir using the `introspection_endpoint` of the <tooltip term="AS">
  AS</tooltip> (also retrieved from the UMA configuration).
    - If the token is valid and active, Kvasir checks if the token contains the required permissions to access the
      requested Resource. If so, the request is allowed to proceed.
    - If the above conditions are not met, Kvasir responds with a `401 Unauthorized` status code along with a
      `WWW-Authenticate` header containing the ticket and <tooltip term="AS">AS</tooltip> URL.

### Known Limitations

At the moment, the <tooltip term="A4DS">A4DS</tooltip> delegation implementation has the following limitations:

- Kvasir will try to register the requested Resource with the AS every time a ticket is created, to ensure the Resource
  is known to the <tooltip term="AS">AS</tooltip>. This may lead to performance issues when a lot of different Resources
  are being requested. Once
  the KNoWS implementation returns a `400 Bad Request` when requesting a ticket for a Resource that is not registered
  with the <tooltip term="AS">AS</tooltip>, we can update the implementation to only register the Resource when it is
  not known to the <tooltip term="AS">AS</tooltip>.
- At the moment, there is no reliable way to extract the user identity from the JWT token issued by
  the <tooltip term="AS">AS</tooltip>. This means that features that rely on knowing the user identity (e.g. removing
  data produced by a specific user) will not work when using the <tooltip term="A4DS">A4DS</tooltip> Policy Agent. By
  default, the implementation tries to extract the `sub` claim from the token, but this is not set by the KNoWS
  implementation. However, Kvasir
  allows [configuring custom principal extractors](Configuration-Reference.md#pod-configuration) to work around this
  issue.
- A JWKS keyset (hosted at `/.well-known/jwks.json`) is exposed by Kvasir, but the keypair is generated on
  each startup. This means that any tokens issued by Kvasir will be invalid after a restart. A proper key management
  solution should be implemented to solve this.
- At the moment, Kvasir uses a simplified mapping of its internal permissions to UMA requested scopes. Only
  `urn:example:css:modes:read`,
  `urn:example:css:modes:write`
  and `urn:example:css:modes:delete` scopes are used, meaning no distinction is made between creating new data,
  overriding data or appending
  data.
- Kvasir assumes the resource identifier used by the UMA server is the full URL of the requested resource. This
  currently applies for the KNoWS implementation, but may not be the case for all UMA servers. Future updates will
  include persistent mapping of Kvasir resources to UMA resource identifiers.

## Delegating to an external HTTP Endpoint Policy Enforcer

When
a [HTTP Endpoint Policy Enforcer is configured for a Pod](Pod-Management.md#external-http-endpoint-policy-enforcer),
Kvasir can delegate policy decisions for specific resources to that HTTP endpoint.

This is done by adding a delegation rule via the [ReBAC API](#adding-or-removing-a-relationship) or Kvasir UI, for
example:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
    "kss-fga": "https://kvasir.discover.ilabt.imec.be/fine-grained-access#"
  },
  "kss:insert": [
    {
      "@id": "urn:kvasir-wildcard",
      "@type": "kss-fga:User",
      "kss-fga:owner": {
        "@id": "https://kvasir.example.org/slices/",
        "@type": "kss-fga:Resource",
        "kss-fga:external_access": {
          "@id": "kss-fga:HttpEndpoint"
        }
      }
    }
  ]
}
```

Read this relationship as: _"When no direct access is granted by regular OpenFGA rules, delegate policy decisions up
to `owner`-level access, to the external system identified by `kss-fga:HttEndpoint`, regardless of the requesting
user."_

### HTTP Endpoint Policy Enforcer contract

When Kvasir needs to delegate a policy decision to the configured HTTP Endpoint Policy Enforcer, it will send a POST
request to the configured URL with a JSON body similar to:

```json
{
  "principal": "urn:kvasir-user:bob",
  "requiredPermission": "read",
  "requestUri": "https://kvasir.example.org/alice/slices/music-tracker/query",
  "requestHeaders": {
    "Authorization": "Bearer <token>",
    "Other-Header": "value"
  },
  "requestMethod": "GET"
}
```

This provides the HTTP Endpoint Policy Enforcer implementation with all the necessary information to make a policy
decision. The implementation is expected to respond with the following JSON structure:

```json
{
  "allowed": true
}
```

If the `allowed` property is set to `true`, Kvasir will allow the request to proceed. In all other cases, the request
will be denied.