# Authentication & Access Control

<show-structure depth="4"/>

Kvasir supports multiple authentication and authorization mechanisms via a modular, pluggable architecture.

A policy agent implementation can be selected at build time using config properties. At the moment, the following
implementations are available:

|                           | Description                                                                                                                                                                                              | Build property                                        | Status                                     |
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|--------------------------------------------|
| `openfga-policy-agent`    | Uses [Keycloak](https://www.keycloak.org/) for authentication and [OpenFGA](https://openfga.dev) for fine-grained access control. Auth flow conforms to OpenID Connect and OAuth 2.1.                    | `kvasir.plugins.policy-agent.openfga.enabled=true`    | Ready for use (included in default builds) |
| `trustflows-policy-agent` | Turns Kvasir into a Trustflows compatible Resource server. The [Trustflows specification](https://spec.knows.idlab.ugent.be/trustflows/all/e45c02bd3711f5734eeb75548ff37a70f57c465e/) builds on UMA 2.0. | `kvasir.plugins.policy-agent.trustflows.enabled=true` | Work in progress                           |

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

OpenFGA on the other hand is an open-source implementation of the Google Zanzibar paper, a globally distributed authorization
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