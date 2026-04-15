# Authentication

<show-structure depth="3" />

Authentication is handled by Keycloak, an open-source Identity and Access Management solution that supports
industry standards such as OpenID Connect and OAuth 2.0. Kvasir uses Keycloak as its Identity Provider (IdP) to manage 
user authentication and issue JSON Web Tokens (JWTs) for secure access to its APIs.

## Bootstrap configuration

Once you start Kvasir (through either [Docker Compose](Getting-started.md#running-with-compose)
or [Dev mode](Getting-started.md#running-in-dev-mode)) the following will happen on boot:

- A Keycloak instance is spun up, it will have a default `master` realm and a generated `quarkus` realm for initial
  setup
- In the `quarkus` realm, one client is created automatically:
    - **kvasir-ui**: this client manages authentication for the Kvasir UI client, which is a Single Page Application. It
      is there mainly to be able to log into the pod's realm and thus get a token. This
      bearer token can then be sent to the [Kvasir APIs](API-Reference.md).
- A default user is created for each Pod. Temporary credentials for that user are set to
  `podname:podname` (e.g. `alice:alice`).

## Creating your own client

When creating your own client, an important distinction must be made: _Is the client code publicly readable?_

- If so, it is called a _public client_, and it can't be trusted to keep a `client_secret` secret.
- If the code is not publicly accessible, it can indeed keep a `client_secret` safe, it is called a _confidential
  client_.

Examples of public clients are Single Page Applications (client-side code), examples of confidential clients are
unattended backend services. Usage examples for both types of clients are provided below.

### Public client {collapsible="true" default-state="expanded"}

To be able to get a bearer token that can access the [Kvasir APIs](API-Reference.md), a public (browser) client has to
authenticate on behalf of that user. This means it will follow
the [Authorization Code Flow](https://openid.net/specs/openid-connect-core-1_0.html#CodeFlowAuth), as described
by [OpenID Connect 1.0](https://openid.net/specs/openid-connect-core-1_0.html).

#### 1. Create a public client for a Pod

At the time of writing, the easiest way to create a public client is via the Pod bootstrap configuration (
see [Via configuration file](Pod-Management.md#via-configuration-file)).

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

Now you have all you need to let your public client request a user to authenticate with the Keycloak realm and receive a
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

#### 2. Code the Authorization Code Flow

In the example below, you can see how to request a token via the Authorization Code Flow in TypeScript.

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

### Confidential client {collapsible="true" default-state="expanded"}

To be able to get a bearer token that can access the Kvasir APIs, a confidential client can authenticate as itself.
To do this, it can use its client credentials to request a bearer token directly from the `token_endpoint`.

#### 1. Create a confidential client in Keycloak

At the time of writing, the easiest way to create a confidential client is via the Pod bootstrap configuration (
see [Via configuration file](Pod-Management.md#via-configuration-file)).

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

Now you have all you need to let your confidential client request a bearer token from the Keycloak realm and be
authenticated as itself.

#### 2. Code the Client Credentials Flow

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