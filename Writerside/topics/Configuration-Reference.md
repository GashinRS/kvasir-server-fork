# Configuration Reference

<show-structure for="chapter,procedure" depth="3"/>

This page provides a reference of the configuration options available for Kvasir.

Configuration options can be set using environment variables, configuration files (Java properties or YAML), or
command-line arguments (Java system properties). See
the [Quarkus guide on configuration](https://quarkus.io/guides/config-reference) for in-depth information.

For example, the configuration variable `kvasir.pod.auto-ingest-rdf` can be set in the following ways:

- Environment variable: `KVASIR_POD_AUTO_INGEST_RDF=true`
- Java properties file: `kvasir.pod.auto-ingest-rdf=true`
- YAML configuration file:

  ```yaml
  kvasir:
    pod:
      auto-ingest-rdf: true
  ```

- Command-line argument: `-Dkvasir.pod.auto-ingest-rdf=true`

The following sections provide an overview of the main configuration categories and their options.

> Configuration variables that do not specify a default value are unset by default and must be explicitly configured when required.
> {style=note}

## Kvasir Core Configuration

### HTTP Configuration

<deflist type="full">
<def title="kvasir.http.base-uri">
default: <code>http://localhost:8080/</code>

The base URI where Kvasir is accessible.
</def>
<def title="kvasir.http.webclient-uri">
default: <code>${kvasir.http.base-uri}/\_ui/</code>

The URI where the Kvasir web client is accessible.
</def>
<def title="kvasir.http.redirect-to-webclient">
default: <code>true</code>

If true, HTTP requests to the base URI will be redirected to the web client URI.
</def>
</deflist>

### Default Pod Configuration

Default configuration related to Pods. May be overridden per Pod (see [Pod configuration](Pod-Management.md#pod-configuration)).

<deflist type="full">
<def title="kvasir.pod.default-context">
The default JSON-LD context to use when interfacing with the global GraphQL endpoint of the Pod's Knowledge Graph. This allows the execution of standard GraphQL queries (which do not have context information) against the Pod's Knowledge Graph. Restricted to direct prefix mappings (nested JSON-LD is not supported).
</def>
<def title="kvasir.pod.auto-ingest-rdf">
default: <code>false</code>

If true, RDF data will be automatically ingested into the KG when uploaded via the storage-api.
</def>
</deflist>

<deflist>
<def title="kvasir.pod.auth.oidc.server-url">
default: <code>${kvasir.auth.keycloak.url}/realms/quarkus</code>

URL of the OIDC-compliant server.
</def>
<def title="kvasir.pod.auth.oidc.principal-extractor.class-name">
default: <code>kvasir.plugins.policyagent.openfga.extractors.SimpleJWTPrincipalExtractor</code>

The fully qualified Java class name of the JWT principal extractor implementation to use.
</def>
<def title="kvasir.pod.auth.oidc.principal-extractor.config">
default: <code>{ "attribute-name":"preferred_username"}</code>

A set of key-value pairs (String) with config to be supplied to the configured principal extractor class.
</def>
<def title="kvasir.pod.auth.oidc.jwt-allowed-clock-skew-seconds">
default: <code>30</code>

Allowed clock skew in seconds to apply during JWT token validation.
</def>
</deflist>

<deflist>
<def title="kvasir.pod.auth.uma.server-url">
default: <code>http://localhost:4000/uma</code>

URL of the UMA-compliant server.
</def>
<def title="kvasir.pod.auth.uma.principal-extractor.class-name">
default: <code>kvasir.plugins.policyagent.openfga.extractors.SimpleJWTPrincipalExtractor</code>

The fully qualified Java class name of the JWT principal extractor implementation to use.
</def>
<def title="kvasir.pod.auth.uma.principal-extractor.config">
default: <code>{ "attribute-name":"sub"}</code>

A set of key-value pairs (String) with config to be supplied to the configured principal extractor class.
</def>
<def title="kvasir.pod.auth.uma.jwt-allowed-clock-skew-seconds">
default: <code>30</code>

Allowed clock skew in seconds to apply during JWT token validation.
</def>
</deflist>

<deflist>
<def title="kvasir.pod.auth.enable-solid-web-id">
default: <code>false</code>

Whether to enable Solid OIDC (i.e. allow authenticating with a Solid WebID).
</def>
<def title="kvasir.pod.auth.require-dpop">
default: <code>false</code>

Whether to require DPoP (Demonstrating Proof-of-Possession) tokens for protected resources (more secure).
</def>
<def title="kvasir.pod.auth.skip-dpop-ath-check">
default: <code>false</code>

Whether to skip the access token hash (ath) check for DPoP tokens. This setting is primarily intended for backward compatibility with clients that implement an earlier version of the DPoP specification.
</def>
</deflist>

<deflist>
<def title="kvasir.pod.auth.http-endpoint-policy-enforcer.url">
URL of the external HTTP endpoint for the policy enforcer.
</def>
<def title="kvasir.pod.auth.http-endpoint-policy-enforcer.basic-auth.username">
Sets the username when using Basic Auth for authenticating requests to the HTTP policy enforcer.
</def>
<def title="kvasir.pod.auth.http-endpoint-policy-enforcer.basic-auth.password">
Sets the password when using Basic Auth for authenticating requests to the HTTP policy enforcer.
</def>
<def title="kvasir.pod.auth.http-endpoint-policy-enforcer.api-key.key-name">
Sets the API key name when using an API key for authenticating requests to the HTTP policy enforcer.
</def>
<def title="kvasir.pod.auth.http-endpoint-policy-enforcer.api-key.key-value">
Sets the API key value when using an API key for authenticating requests to the HTTP policy enforcer.
</def>
<def title="kvasir.pod.auth.http-endpoint-policy-enforcer.api-key.send-via">
Sets whether the API key should be passed via a HTTP Header (<code>header</code>) or a Query Parameter (<code>query</code>) when using an API key for authenticating requests to the HTTP policy enforcer.
</def>
</deflist>

### Bootstrap Configuration

Note: the `i` in `kvasir.bootstrap.pods[i]` refers to the index of the Pod in the bootstrap Pods list (
see [Quarkus guide on how collection type properties are mapped](https://quarkus.io/guides/config-mappings#collections)).

<deflist type="full">
<def title="kvasir.bootstrap.pods[i].name">
The name of the pod to create at startup.
</def>
<def title="kvasir.bootstrap.pods[i].owner-user-id">
The user ID of the owner of the pod (optional). Primarily useful when using an external OIDC server (not the Kvasir built-in Keycloak). If not provided, we assume the userId is the same as the pod name.
</def>
<def title="kvasir.bootstrap.pods[i].auto-register-uma">
default: <code>false</code>

If set to true, authorization is delegated to the configured UMA server for all resources (by default).
</def>
<def title="kvasir.bootstrap.pods[i].auto-register-http-endpoint-policy-enforcer">
default: <code>false</code>

If set to true, authorization is delegated to the configured HTTP Endpoint Policy Enforcer for all resources (by default).
</def>
<def title="kvasir.bootstrap.pods[i].generate-clients[i].client-id">
Client id for the client to generate (using the built-in Keycloak).
</def>
<def title="kvasir.bootstrap.pods[i].generate-clients[i].enable-service-account">
default: <code>false</code>

Whether to enable the service account for this client.
</def>
<def title="kvasir.bootstrap.pods[i].generate-clients[i].client-secret">
The client secret, if applicable. Not required for public clients.
</def>
<def title="kvasir.bootstrap.pods[i].generate-clients[i].redirect-uris">
The redirect URIs for the client. Optional, as some clients may not require a redirect URI (e.g., service clients).
</def>
<def title="kvasir.bootstrap.pods[i].generate-clients[i].enable-force-pkce">
default: <code>false</code>

Enforces use of Proof Key for Code Exchange (<a href="https://oauth.net/2/pkce/">PKCE</a>) when enabled.
</def>
<def title="kvasir.bootstrap.pods[i].generate-clients[i].openfga.relationships[i].target-resource">
Optional default relationships to add to OpenFGA related to this client. The target resource identifies the endpoint the relationship applies to, relative to the Pod URL (e.g. <code>/</code> is the root of the Pod).
</def>
<def title="kvasir.bootstrap.pods[i].generate-clients[i].openfga.relationships[i].relations">
The list of relations that apply to the target resource. For example, "reader", "writer", "owner" etc. Possible relations are defined in the OpenFGA schema being used.
</def>
<def title="kvasir.bootstrap.exit-after-setup">
default: <code>false</code>

If true, the Kvasir init-service will terminate after the setup is completed (or failed). (Useful for init containers, e.g. in Kubernetes).
</def>
</deflist>

## Dependency Configuration

### Clickhouse Configuration

<deflist type="full">
<def title="kvasir.kg.clickhouse.host">
default: <code>localhost</code>

Clickhouse server hostname.
</def>
<def title="kvasir.kg.clickhouse.port">
default: <code>8123</code>

Clickhouse server port.
</def>
<def title="kvasir.kg.clickhouse.user">
Clickhouse username. If unset defaults to unauthenticated requests.
Setting either user or password values will enable authenticated CH requests.
</def>
<def title="kvasir.kg.clickhouse.password">
Clickhouse password. If unset defaults to unauthenticated requests.
Setting either user or password values will enable authenticated CH requests.
</def>
</deflist>

### Kafka configuration

<deflist type="full">
<def title="kvasir.messaging.kafka.bootstrap-servers">
default: <code>localhost:9092</code>

Comma-separated list of Kafka brokers.
</def>

[//]: # '<def title="kvasir.messaging.kafka.advertised-hostname">'
[//]: # "default: <code>localhost</code>"
[//]: #
[//]: # "Hostname to advertise to Kafka clients."
[//]: # "</def>"

</deflist>

### Auth Configuration (OpenFGA & Keycloak)

<deflist type="full">
<def title="kvasir.pep.openfga.url">
default: <code>http://localhost:8380</code>

OpenFGA server url.
</def>
<def title="kvasir.auth.keycloak.url">
default: <code>http://localhost:8280</code>

Keycloak server url.
</def>
<def title="kvasir.auth.keycloak.realm">
default: <code>quarkus</code>

Realm name to target for authentication.
</def>
<def title="kvasir.auth.keycloak.admin-client.grant-type">
default: <code>password</code>

Grant type used for admin client authentication. Can be either <code>password</code> or <code>client_credentials</code>.
</def>
<def title="kvasir.auth.keycloak.admin-client.username">
default: <code>admin</code>
</def>
<def title="kvasir.auth.keycloak.admin-client.password">
default: <code>admin</code>
</def>
<def title="kvasir.auth.keycloak.admin-client.client-id">
default: <code>admin-cli</code>
</def>
<def title="kvasir.auth.keycloak.admin-client.client-secret">
default: <code>admin</code>
</def>
</deflist>

### S3 Configuration

<deflist type="full">
<def title="kvasir.storage.s3.endpoint">
default: <code>http://localhost:8333</code>

S3 server endpoint.
</def>
<def title="kvasir.storage.s3.region">
default: <code>us-east-1</code>

S3 server region.
</def>
<def title="kvasir.storage.s3.access-key">
default: <code>kvasir</code>

S3 access key.
</def>
<def title="kvasir.storage.s3.secret-key">
default: <code>kvasirkvasir</code>

S3 secret key.
</def>
</deflist>
