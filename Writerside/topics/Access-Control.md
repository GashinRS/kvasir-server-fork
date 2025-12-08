# Authentication & Access Control

<show-structure depth="4"/>

Kvasir implements authentication by processing JWT tokens in the `Authorization` header of incoming requests, both
`Bearer` and `DPoP` tokens are supported. The tokens are validated according to the OpenID Connect and OAuth 2
standards.
When a WebID claim is present in the token, Kvasir performs additional verification to ensure the token was issued by an
OIDC server that is trusted by the WebID owner (per [Solid OIDC specification](https://solidproject.org/TR/oidc)).
_WebIDs can be disabled by setting `auth.enable-solid-web-id` to false in the Pod configuration._

On top of this authentication layer, Kvasir integrates multiple authorization mechanisms via a modular, pluggable
architecture, based around [OpenFGA](https://openfga.dev):

- Using **OpenFGA** relationship-based access control (ReBAC), fine-grained access control policies can be expressed and
  enforced for Kvasir HTTP resources. These policies are local to the Kvasir server instance.
- OpenFGA relationships can also be used to express that policy decisions for specific resources (or resource
  hierarchies) should be delegated to external systems. Kvasir currently supports the following integrations:
    - Delegate to **[A4DS](https://spec.knows.idlab.ugent.be/A4DS/L1/latest/)/UMA 2.0** compliant Authorization Servers.
    - Delegate to any external system that implements a specific **HTTP endpoint** for policy decision requests.

## Keycloak and OpenFGA

By default, Kvasir installations come with a Keycloak server that is used as a OIDC-compliant Identity Provider for
authentication.
OpenFGA is used to manage authorization. Some of the main benefits for choosing Keycloak are:

- Uses battle-tested
  standards ([OpenID Connect 1.0](https://openid.net/specs/openid-connect-core-1_0.html), [OAuth 2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-11))
- Also acts as an Identity Broker, allowing integration with other OpenId Providers for authentication.
- Widespread use & large support community
- Client libraries available in multiple languages (not all
  official): [javascript](https://www.keycloak.org/securing-apps/javascript-adapter), [java](https://github.com/keycloak/keycloak-client), [python](https://pypi.org/project/python-keycloak/)

OpenFGA on the other hand is an open-source implementation of the Google Zanzibar paper, a globally distributed
authorization
system that manages permissions at scale (powering authorization policies for Google Services such as YouTube, Drive,
Calendar, Cloud and Maps.).

### Authentication

#### Keycloak boostrap configuration

Once you start Kvasir (through either [Docker Compose](Getting-started.md#running-with-compose)
or [Dev mode](Getting-started.md#running-in-dev-mode)) the following will happen on boot:

- A keycloak instance is spun up, it will have a default `master` realm and a generated `quarkus` realm for initial
  setup
- In the `quarkus` realm, one client is created automatically:
    - **kvasir-ui**: this client manages authentication for the Kvasir UI client, which is a Single Page Application. It
      is there mainly to be able to log into the pod's realm and thus get a token. This
      bearer token can then be sent to the [Kvasir APIs](API-Reference.md).
- A default user is created for each Pod. Temporary credentials for that user are set to
  `podname:podname` (eg. `alice:alice`). Upon a first login, these will be prompted for change.

#### Creating your own client

When creating your own client, an important distinction must be made: _Is the client code publicly readable?_

- If so, it is called a _public client_, and it can't be trusted to keep a `client_secret` secret.
- If the code is not publicly accessible, it can indeed keep a `client_secret` safe, it is called a _confidential
  client_.

Examples of public clients are Single Page Applications (client-side code), examples of confidential clients are
Unattended backend services. Usage examples for both types of clients are provided below.

##### Public client {collapsible="true"}

To be able to get a bearer token that can access the [Kvasir APIs](API-Reference.md), a public (browser) client has to
authenticate on behalf of that user. This means it will follow
the [Authorization Code Flow](https://openid.net/specs/openid-connect-core-1_0.html#CodeFlowAuth), as described
by [OpenID Connect 1.0](https://openid.net/specs/openid-connect-core-1_0.html).

###### 1. Create a public client for a Pod

At the time of writing, the easiest way to create a public client is via the Pod bootstrap configuration (
see [](Pod-Management.md#via-configuration-file)).

```yaml
kvasir:
  bootstrap:
    pods:
      - name: alice
        generate-clients:
          - client-id: my-public-client
            redirect-uris:
              - http://localhost:4200/test
```

Now you have all you need to let your public client request a user to authenticate with the keycloak realm and receive a
bearer token.

> **PKCE** is an extension to the Authorization Code flow to prevent CSRF and authorization code injection attacks. For
> development, you can leave the client configuration as is. But when your client is exposed to the public internet, it
> is
> better to require PKCE when authenticating via your application. To force-enable PKCE, set the `enable-force-pkce`
> property to true.
>
> ```yaml
> generate-clients:
>   - client-id: my-public-client
>     redirect-uris:
>       - http://localhost:4200/test
>     enable-force-pkce: true
> ```
>
> {style="warning"}

###### 2. Code the Authorization Code Flow

In the example below, you can see how to request a token via the Authorization Code Flow in typescript.

```ts
/** Fetch important urls. (helper class below) */
async function init() {
    // Discover authServerUrl of the pod
    const kvasirHost = "http://localhost:8080";
    // Hit authed endpoint to get Www-Authenticate header
    const {headers} = (await fetch(`${kvasirHost}/alice/changes`));
    const wwwAuthParser = new WwwAuthParser(["Bearer", "UMA"]);
    const authUrl = wwwAuthParser.parseHeader(headers.get("Www-Authenticate")).Bearer.as_uri;

    // Fetch auth and token url from openid config
    const config = (
        await fetch(`${authUrl}/.well-known/openid-configuration`)
    ).json();
    const {authorization_endpoint, token_endpoint} = await config;

    // Printout
    console.log(authorization_endpoint);
    console.log(token_endpoint);
}

/** Trigger a login and redirect back to this page */
async function login() {
    const params = new URLSearchParams({
        client_id: "my-public-client",
        redirect_uri: "http://localhost:4200/test",
        response_type: "code",
        response_mode: "fragment",
        scope: "openid",
    });
    // Redirect user agent to login page
    window.location.assign(`${authorization_endpoint}?${params.toString()}`);
}

/** Function to check for code in fragment parameters, once page loads */
async function checkCodeResponse() {
    const fragment = new URLSearchParams(window.location.hash);
    const code = fragment.get("code");
    if (code) {
        const tokenRequest = new URLSearchParams({
            code: code,
            grant_type: "authorization_code",
            client_id: "my-public-client",
            redirect_uri: "http://localhost:4200/test",
        });

        // send code back to token endpoint
        const response = await fetch(token_endpoint, {
            method: "post",
            body: tokenRequest,
            headers: {
                "Content-Type": "application/x-www-form-urlencoded",
            },
        });
        const token = await response.json();
        console.log("Bearer token", token["access_token"]);
    }
}

/* Helper class */

export class WwwAuthParser<A = any> {
    constructor(private schemes: (keyof A)[]) {
    }

    /**
     * Parse a Www-Authenticate header into a map of scheme -> parameters
     * @param wwwAuthenticationHeader
     */
    parseHeader(wwwAuthenticationHeader: string): A {
        const tokens = wwwAuthenticationHeader
            .trim()
            .split(',')
            .flatMap((tok) => tok.trim().split(/\s+/));
        let currentScheme: keyof A | null = null;
        let responseMap = {} as any;
        tokens.forEach((tok) => {
            if (this.schemes.map((sch) => sch as string).includes(tok) && currentScheme != tok) {
                currentScheme = tok as keyof A;
                responseMap[currentScheme] = {} as Record<string, string>;
            } else if (currentScheme != null) {
                const params = tok.split('=');
                if (params.length == 2) {
                    responseMap[currentScheme][params[0]] = unquote(params[1]);
                } else {
                    responseMap[currentScheme][params[0]] = true;
                }
            } else {
                throw new Error('Www-Authenticate header is invalid!');
            }
        });
        return responseMap as A;
    }
}

function unquote(str: string): string {
    if (str != null) {
        if (str.startsWith('"') && str.endsWith('"')) {
            return str.slice(1, str.length - 1);
        }
    }
    return str;
}
```

You can now use this token as a Bearer token in the `Authorization` header, each time you do
a [Kvasir API](API-Reference.md) request:

```http
Authorization: Bearer <token>
```

##### Confidential client {collapsible="true"}

To be able to get a bearer token that can access the Kvasir APIs, a confidential client can authenticate as itself.
To do this, it can use its client credentials to request a bearer token directly from the `token_endpoint`.

###### 1. Create a confidential client in keycloak

At the time of writing, the easiest way to create a confidential client is via the Pod bootstrap configuration (
see [](Pod-Management.md#via-configuration-file)).

```yaml
kvasir:
  bootstrap:
    pods:
      - name: alice
        generate-clients:
          - client-id: my-confidential-client
            client-secret: my-secret
            enable-service-account: true
```

Now you have all you need to let your confidential client request a bearer token from the keycloak realm and be
authenticated as itself.

###### 2. Code the Client Credentials Flow

To get a bearer token, you just need to do a POST request to the token endpoint. Your client credentials pair has to be
sent using the [Basic Authentication Scheme](https://datatracker.ietf.org/doc/html/rfc7617) and an additional
`grant_type` parameter is required.

```http
POST http://localhost:8280/realms/quarkus/protocol/openid-connect/token
Authorization: Basic bXktY29uZmlkZW50aWFsLWNsaWVudDpteS1zZWNyZXQ=
Content-Type: application/x-www-form-urlencoded

grant_type = client_credentials
```

You can now use this token as a Bearer token in the Authorization header, each time you do
a [Kvasir API](API-Reference.md) request:

```http
Authorization: Bearer <token>
```

The next section goes into detail on how to manage access control using ReBAC (Relationship-Based Access Control) with
OpenFGA.
You can follow the instructions explained there to assign permissions to your confidential client. Alternatively,
permission relationships can also be created directly in the bootstrap config. For example:

```yaml
generate-clients:
  - client-id: my-confidential-client
    client-secret: my-secret
    enable-service-account: true
    openfga:
      relationships:
        - target-resource: "/slices/my-slice"
          relations: [ "reader", "writer", "deleter" ]
```

This grants the client `my-confidential-client` read, write and delete permissions on the Slice
`/slices/my-slice`.

### Access Control

#### Introduction to ReBAC

Kvasir uses OpenFGA to manage access control. OpenFGA is a ReBAC (Relationship-Based Access Control) system, which means
that access control is based on relationships between users and resources. This allows for fine-grained access
control policies that can be tailored to specific use cases.

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

#### Managing access control

##### Via the Kvasir UI

You can manage access control via the [Kvasir UI](Kvasir-UI.md#access-control). The UI provides a user-friendly
interface to manage access control.

##### Via the ReBAC API

Kvasir provides a Linked-Data compatible Wrapper API for managing the OpenFGA relationships.

###### Listing existing relationships

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

###### Adding or removing a relationship

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

###### Check access

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

#### Delegating to external systems

##### A4DS/UMA 2.0 Authorization Servers

When a [UMA server is configured for a Pod](Pod-Management.md#uma-configuration), Kvasir can delegate policy decisions
for specific resources to that server.

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

When enabling the A4DS delegation, Kvasir acts as a UMA Resource Server, delegating authentication and authorization
to a configured A4DS/UMA-compliant Authorization Server.
The A4DS implementation is being developed and tested using
the [KNoWS UMA Authorization Server](https://github.com/SolidLabResearch/user-managed-access) as a reference. For any
questions you may have regarding setting up an Authorization server, configuring policies, how to create and
authenticate users & clients, etc; please refer to the documentation of the Authorization Server you are using.

Contact information for the KNoWS group can be found at [](https://knows.idlab.ugent.be).

##### How it works

For each request to an API endpoint, Kvasir checks for a Bearer token in the `Authorization` header.

- If no token is present, Kvasir will create a ticket with the configured Authorization Server (AS), using the
  `permission_endpoint` in the UMA configuration of the AS (retrieved by performing a GET at
  `/.well-known/uma2-configuration`).
    - If the AS responds with a 201 Created status code, Kvasir will forward the ticket, along with the URL of the AS,
      to the client in a `WWW-Authenticate` header and respond with a 401 Unauthorized status code.
    - If the AS responds with a 200 OK status code, Kvasir allows the requests to proceed (the Resource represented by
      the API call is a public Resource).
    - For any other response code, Kvasir will respond with a 401 Unauthorized status code (without `WWW-Authenticate`
      challenge).
- If a token is present, it is validated by Kvasir using the `introspection_endpoint` of the AS (also retrieved from the
  UMA configuration).
    - If the token is valid and active, Kvasir checks if the token contains the required permissions to access the
      requested Resource. If so, the request is allowed to proceed.
    - If the above conditions are not met, Kvasir responds with a 401 Unauthorized status code along with a
      `WWW-Authenticate` header containing the ticket and AS URL.

##### Known Limitations

At the moment, the A4DS delegation implementation has the following limitations:

- Kvasir will try to register the requested Resource with the AS every time a ticket is created, to ensure the Resource
  is known to the AS. This may lead to performance issues when a lot of different Resources are being requested. Once
  the KNoWS implementation returns a 400 Bad Request when requesting a ticket for a Resource that is not registered with
  the AS, we can update the implementation to only register the Resource when it is not known to the AS.
- At the moment, there is no reliable way to extract the user identity from the JWT token issued by the AS. This means
  that features that rely on knowing the user identity (e.g. removing data produced by a specific user) will not work
  when using the A4DS Policy Agent. By default, the implementation tries to extract the `sub` claim from the token, but
  this is
  not set by the KNoWS implementation. However, Kvasir
  allows [configuring custom principal extractors](Configuration-Reference.md#pod-configuration) to work around this
  issue.
- A JWKS keyset (hosted at `/.well-known/uma2-configuration`) is exposed by Kvasir, but the keypair is generated on
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

#### Delegating to an external HTTP Endpoint Policy Enforcer

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

##### HTTP Endpoint Policy Enforcer contract

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