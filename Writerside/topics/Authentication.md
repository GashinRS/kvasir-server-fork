# Authentication

Kvasir uses Keycloak as authentication solution. For now it will also manage authorization. Some of the main benefits for choosing Keycloak are:

 * Uses battle-tested standards ([OpenID Connect 1.0](https://openid.net/specs/openid-connect-core-1_0.html), [OAuth 2.1](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-11))
 * Also acts as an Identity Broker, allowing integration with other OpenId Providers for authentication.
 * Widespread use & large support community
 * Client libraries available in multiple languages (not all official): [javascript](https://www.keycloak.org/securing-apps/javascript-adapter), [java](https://github.com/keycloak/keycloak-client), [python](https://pypi.org/project/python-keycloak/)   

## Keycloak boostrap configuration

Once you start Kvasir (through either [Docker Compose](Getting-started.md#running-with-docker-compose) or [Dev mode](Getting-started.md#running-in-dev-mode)) the following will happen on boot:
 * A keycloak instance is spun up, it will have a default `master` realm and an imported `quarkus` realm for initial setup
 * For each pod an additional realm will be created, with the same name as the pod. (eg. `alice`)
 * In each realm, two clients are created:
   * **kvasir-server**: this client manages the pod's authorization. A default policy is created that will allow users with the realm role `owner` to access any resources on their pod.
   * **kvasir-ui**: this client manages authentication for the Kvasir UI client, which is a Single Page Application. It is there mainly to be able to log into the pod's realm and thus get a token with the realm role `owner`. This bearer token can then be sent to the [Kvasir APIs](API-Reference.md).
 * They will also have a default user named after their pod name. Temporary credentials for that user are set to `podname:podname` (eg. `alice:alice`). Upon a first login, these will be prompted for change.

## Creating your own client

When creating your own client, an important distinction must be made:  _Is the client code publicly readable?_ 

* If so, it is called a _public client_, and it can't be trusted to keep a `client_secret` secret. 
* If the code is not publicly accessible, it can indeed keep a `client_secret` safe, it is called a _confidential client_. 
 
Examples of public clients are Single Page Applications (client-side code), examples of confidential clients are Unattended backend services.

### Public client

To be able to get a bearer token that can access the [Kvasir APIs](API-Reference.md), a public (browser) client has to authenticate on behalf of that user. This means it will follow the [Authorization Code Flow](https://openid.net/specs/openid-connect-core-1_0.html#CodeFlowAuth), as described by [OpenID Connect 1.0](https://openid.net/specs/openid-connect-core-1_0.html). 

#### 1. Create a public client in Keycloak

You will first need a public client configured in your pod's keycloak realm. 

* To do that open the keycloak instance via its default URL: [](http://localhost:8280)
* Log in with the default keycloak admin credentials: `admin:admin`
* In the top left, there is a realm selector: select your pod's realm (`alice` in this example)
  ![Keycloak realm selection](kc_realm_sel.png)
* Go to the `Clients` section and click the `Create client` button
* Enter a `Client ID` of your choosing
  ![kc_client_creation_1](kc_client_creation_1.png)
* In step 2, you can leave everything on default settings
  ![kc_client_creation_2.png](kc_client_creation_2.png)
* In step 3, you must add `Valid redirect URIs` (the url on which you host your client) and a `Web origins` value of `+` (shortcut for _same hosts as all `Valid redirect URIs`_)
  ![kc_client_creation_3](kc_client_creation_3.png)
* Now click `Save` and your new client config opens.

Now you have all you need to let your public client request a user to authenticate with the keycloak realm and receive a bearer token.

>**PKCE** is an extension to the Authorization Code flow to prevent CSRF and authorization code injection attacks. For development, you can leave the client configuration as is. But when your client is exposed to the public internet, it is better to require PKCE when authenticating via your application. To force-enable PKCE open the Advanced tab and set `Proof Key for Code Exchange Code Challenge Method` to `S256`. Without this setting Keycloak will allow both PKCE and non-PKCE authentication protocols. [(more on pkce...)](https://oauth.net/2/pkce/)
>
>![kc_client_creation_4.png](kc_client_creation_4.png){style="inline"}
{style="warning"}

#### 2. Code the Authorization Code Flow
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
    const { authorization_endpoint, token_endpoint } = await config;
    
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

You can now use this token as a Bearer token in the `Authorization` header, each time you do a [Kvasir API](API-Reference.md) request:

```http
Authorization: Bearer <token>
```

### Confidential client

To be able to get a bearer token that can access the Kvasir APIs, a confidential client can authenticate as itself.
To do this it can use its client credentials to request a bearer token directly from the `token_endpoint`.

#### 1. Create a confidential client in keycloak

You will first need a confidential client configured in your pod's keycloak realm.

* To do that open the keycloak instance via its default URL: [](http://localhost:8280)
* Log in with the default keycloak admin credentials: `admin:admin`
* In the top left, there is a realm selector: select your pod's realm (`alice` in this example)
  ![Keycloak realm selection](kc_realm_sel.png)
* Go to the `Clients` section and click the `Create client` button
* Enter a `Client ID` of your choosing
  ![kc_client_creation_conf_1.png](kc_client_creation_conf_1.png)
* In step 2, set `Client authentication` to `on`. Now also select `Service accounts roles` (this will enable the [Client Credential Grant](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-v2-1-11#name-client-credentials-grant) to get the bearer token)
  ![kc_client_creation_conf_2.png](kc_client_creation_conf_2.png)
* In step 3, you can leave everything on default.
* Now click `Save` and your new client config opens.
* Now go the `Credentials` tab and your can view your generated `client_secret` there.
* Your chosen `Client ID` and generated `client_secret` form your pair of _Client credentials_ for the next step.

> If you want the client itself to be able to access the resources protected by the default policy of this realm, 
> you will have to set the _service-account_ of this client to have the `owner` role.
> 
> * Go to the Client configuration of your client, and open the `Service accounts roles` tab.
> * Click `Assign role`
> * Change the selection box from ~~`Filter by clients`~~ to `Filter by realm roles`.
> * Select `owner` and click `Assign` below.
> * Granted tokens will now include the `owner` realm role.
>
{style="note"}

Now you have all you need to let your confidential client request a bearer token from the keycloak realm and be authenticated as itself.

#### 2. Code the Client Credentials Flow

To get a bearer token, you just need to do a POST request to the token endpoint. Your client credentials pair has to be sent using the [Basic Authentication Scheme](https://datatracker.ietf.org/doc/html/rfc7617) and an additional `grant_type` parameter is required. 

```http
POST http://localhost:8280/realms/alice/protocol/openid-connect/token
Authorization: Basic bXktY29uZmlkZW50aWFsLWNsaWVudDpteS1zZWNyZXQ=
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

You can now use this token as a Bearer token in the Authorization header, each time you do a [Kvasir API](API-Reference.md) request:
```http
Authorization: Bearer <token>
```