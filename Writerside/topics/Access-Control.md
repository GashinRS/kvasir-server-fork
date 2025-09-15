# Authentication & Access Control

<show-structure depth="4"/>

Kvasir supports multiple authentication and authorization mechanisms via a modular, pluggable architecture.

A policy agent implementation can be selected at build time using config properties. At the moment, the following
implementations are available:

|                                               | Description                                                                                                                                                                            | Build property                                     | Image suffix                      | Status                                                              |
|-----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------|-----------------------------------|---------------------------------------------------------------------|
| [openfga-policy-agent](#openfga-policy-agent) | Uses [Keycloak](https://www.keycloak.org/) for authentication and [OpenFGA](https://openfga.dev) for fine-grained access control. Auth flow conforms to OpenID Connect and OAuth 2.1.  | `kvasir.plugins.policy-agent.openfga.enabled=true` | `-openfga` or no suffix (default) | Ready for use (included in default builds)                          |
| [a4ds-policy-agent](#a4ds-policy-agent)       | Turns Kvasir into an 'Authorization for Data Spaces (A4DS)' compatible Resource server. The [A4DS specification](https://spec.knows.idlab.ugent.be/A4DS/L1/latest/) builds on UMA 2.0. | `kvasir.plugins.policy-agent.a4ds.enabled=true`    | `-a4ds`                           | Basic implementation available (check known limitations before use) |
| No policy agent plugin                        | Disables authentication and access control. May be useful for specific use cases or development purposes.                                                                              | n/a                                                | `-noauth`                         |                                                                     |

## OpenFGA Policy Agent

The OpenFGA Policy Agent uses Keycloak as authentication solution. OpenFGA is used to manage authorization. Some of the
main benefits
for choosing Keycloak are:

* Uses battle-tested
  standards ([OpenID Connect 1.0](https://openid.net/specs/openid-connect-core-1_0.html), [OAuth 2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-11))
* Also acts as an Identity Broker, allowing integration with other OpenId Providers for authentication.
* Widespread use & large support community
* Client libraries available in multiple languages (not all
  official): [javascript](https://www.keycloak.org/securing-apps/javascript-adapter), [java](https://github.com/keycloak/keycloak-client), [python](https://pypi.org/project/python-keycloak/)

OpenFGA on the other hand is an open-source implementation of the Google Zanzibar paper, a globally distributed
authorization
system that manages permissions at scale (powering authorization policies for Google Services such as YouTube, Drive,
Calendar, Cloud and Maps.).

### Authentication

#### Keycloak boostrap configuration

Once you start Kvasir (through either [Docker Compose](Getting-started.md#running-with-docker-compose)
or [Dev mode](Getting-started.md#running-in-dev-mode)) the following will happen on boot:

* A keycloak instance is spun up, it will have a default `master` realm and a generated `quarkus` realm for initial
  setup
* In the `quarkus` realm, two clients are created:
    * **quarkus-app**: this client is used by the Kvasir backend to verify tokens issued by this Keycloak instance.
    * **kvasir-ui**: this client manages authentication for the Kvasir UI client, which is a Single Page Application. It
      is there mainly to be able to log into the pod's realm and thus get a token. This
      bearer token can then be sent to the [Kvasir APIs](API-Reference.md).
* A default user is created for each Pod. Temporary credentials for that user are set to
  `podname:podname` (eg. `alice:alice`). Upon a first login, these will be prompted for change.

#### Creating your own client

When creating your own client, an important distinction must be made:  _Is the client code publicly readable?_

* If so, it is called a _public client_, and it can't be trusted to keep a `client_secret` secret.
* If the code is not publicly accessible, it can indeed keep a `client_secret` safe, it is called a _confidential
  client_.

Examples of public clients are Single Page Applications (client-side code), examples of confidential clients are
Unattended backend services.

##### Public client

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
        configuration: |
          {}
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
/** Fetch important urls */
async function init() {
    // Discover authServerUrl of the pod
    const kvasirHost = 'http://localhost:8080';
    const pods = (await fetch(kvasirHost)).json();
    const profileUrl = (await pods)['@graph'].find(
        (pod: any) => `${kvasirHost}/alice` == pod['@id'],
    )['kss:profile'];
    const profile = (await fetch(profileUrl)).json();
    const authUrl = (await profile)['kss:authServerUrl'];

    // Fetch auth and token url from openid config
    const config = (await fetch(
        `${authUrl}/.well-known/openid-configuration`
    )).json();
    const {authorization_endpoint, token_endpoint} = await config;

    // Printout
    console.log(authorization_endpoint);
    console.log(token_endpoint);
}

/** Trigger a login and redirect back to this page */
async function login() {
    const params = new URLSearchParams({
        client_id: 'my-public-client',
        redirect_uri: 'http://localhost:4200/test',
        response_type: 'code',
        response_mode: 'fragment',
        scope: 'openid'
    });
    // Redirect user agent to login page
    window.location.assign(
        `${authorization_endpoint}?${params.toString()}`
    );
}

/** Function to check for code in fragment parameters, once page loads */
async function checkCodeResponse() {
    const fragment = new URLSearchParams(window.location.hash);
    const code = fragment.get('code');
    if (code) {
        const tokenRequest = new URLSearchParams({
            code: code,
            grant_type: 'authorization_code',
            client_id: 'my-public-client',
            redirect_uri: 'http://localhost:4200/test',
        });

        // send code back to token endpoint
        const response = await fetch(token_endpoint, {
            method: 'post',
            body: tokenRequest,
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            }
        });
        const token = await response.json();
        console.log('Bearer token', token['access_token']);
    }
}
```

You can now use this token as a Bearer token in the `Authorization` header, each time you do
a [Kvasir API](API-Reference.md) request:

```http
Authorization: Bearer <token>
```

##### Confidential client

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
        configuration: |
          {}
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

## A4DS Policy Agent

When enabling the A4DS policy agent, Kvasir acts as a UMA Resource Server, delegating authentication and authorization
to a configured A4DS/UMA-compliant Authorization Server.
The A4DS Policy Agent implementation is being developed and tested using
the [KNoWS UMA Authorization Server](https://github.com/SolidLabResearch/user-managed-access) as a reference. For any
questions you may have regarding setting up an Authorization server, configuring policies, how to create and
authenticate users & clients, etc; please refer to the documentation of the Authorization Server you are using.

Contact information for the KNoWS group can be found at [](https://knows.idlab.ugent.be).

### Usage

An A4DS specific build of Kvasir is required to use this feature. When running in dev mode, you can enable the A4DS
Policy Agent by setting the property `kvasir.plugins.policy-agent.a4ds.enabled` to true.

When using our Container Image builds, look for tags with the `-a4ds` suffix.

The default A4DS/UMA compliant Authorization Server to use, can be configured via the property
`kvasir.plugins.policy-agent.a4ds.default-uma-server-url`.

Individual Pods can be configured to use a different Authorization Server via the `authServerUrl` property in the Pod
configuration. If not set, the default server will be used.

Example of a Pod configuration using a custom Authorization Server:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "kss:autoIngestRDF": true,
  "kss:authConfiguration": {
    "authServerUrl": "https://alice.example.org/uma"
  }
}
```

For each request to an API endpoint, Kvasir checks for a Bearer token in the `Authorization` header.

* If no token is present, Kvasir will create a ticket with the configured Authorization Server (AS), using the
  `permission_endpoint` in the UMA configuration of the AS (retrieved by performing a GET at
  `/.well-known/uma2-configuration`).
    * If the AS responds with a 201 Created status code, Kvasir will forward the ticket, along with the URL of the AS,
      to the client in a `WWW-Authenticate` header and respond with a 401 Unauthorized status code.
    * If the AS responds with a 200 OK status code, Kvasir allows the requests to proceed (the Resource represented by
      the API call is a public Resource).
    * For any other response code, Kvasir will respond with a 401 Unauthorized status code (without `WWW-Authenticate`
      challenge).
* If a token is present, it is validated by Kvasir using the `introspection_endpoint` of the AS (also retrieved from the
  UMA configuration).
    * If the token is valid and active, Kvasir checks if the token contains the required permissions to access the
      requested Resource. If so, the request is allowed to proceed.
    * If the above conditions are not met, Kvasir responds with a 401 Unauthorized status code along with a
      `WWW-Authenticate` header containing the ticket and AS URL.

### Known Limitations

At the moment, the A4DS Policy Agent implementation has the following limitations:

* Kvasir will try to register the requested Resource with the AS every time a ticket is created, to ensure the Resource
  is known to the AS. This may lead to performance issues when a lot of different Resources are being requested. Once
  the KNoWS implementation returns a 400 Bad Request when requesting a ticket for a Resource that is not registered with
  the AS, we can update the implementation to only register the Resource when it is not known to the AS.
* At the moment, there is no reliable way to extract the user identity from the JWT token issued by the AS. This means
  that features that rely on knowing the user identity (e.g. removing data produced by a specific user) will not work
  when using the A4DS Policy Agent. _The implementation tries to extract the `sub` claim from the token, but as this is
  not set by the KNoWS implementation, it will fallback to the token identifier (jti)._
* A JWKS keyset (hosted at `/.well-known/uma2-configuration`) is exposed by Kvasir, but the keypair is generated on
  each startup. This means that any tokens issued by Kvasir will be invalid after a restart. A proper key management
  solution should be implemented to solve this.
* At the moment, Kvasir uses a simplified mapping of its internal permissions to UMA requested scopes. Only
  `urn:example:css:modes:read`,
  `urn:example:css:modes:write`
  and `urn:example:css:modes:delete` scopes are used, meaning no distinction is made between creating new data,
  overriding data or appending
  data.
* Kvasir assumes the resource identifier used by the UMA server is the full URL of the requested resource. This
  currently applies for the KNoWS implementation, but may not be the case for all UMA servers. Future updates will
  include persistent mapping of Kvasir resources to UMA resource identifiers.