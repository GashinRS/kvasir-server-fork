# Configuration Reference

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

## Kvasir Core Configuration

### HTTP Configuration

| Variable                    | Description                                        | Default value               |
|-----------------------------|----------------------------------------------------|-----------------------------|
| `kvasir.http.base-uri`      | The base URI where Kvasir is accessible.           | http://localhost:8080/      |
| `kvasir.http.webclient-uri` | The URI where the Kvasir web client is accessible. | ${kvasir.http.base-uri}_ui/ |

### Pod Configuration

Default configuration related to Pods. May be overridden per Pod (see [](Pod-Management.md#pod-configuration)).

| Variable                                                            | Description                                                                                                                                                                                                                                                                                                                 | Default value                                                               |
|---------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `kvasir.pod.default-context`                                        | The default JSON-LD context to use when interfacing with the global GraphQL endpoint of the Pod's Knowledge Graph. This allows the execution of standard GraphQL queries (which do not have context information) against the Pod's Knowledge Graph. Restricted to direct prefix mappings (nested JSON-LD is not supported). | n/a                                                                         |
| `kvasir.pod.auto-ingest-rdf`                                        | If true, RDF data will be automatically ingested into the KG when uploaded via the storage-api.                                                                                                                                                                                                                             | `false`                                                                     |
| `kvasir.pod.auth.oidc.server-url`                                   | URL of the OIDC-compliant server.                                                                                                                                                                                                                                                                                           | ${kvasir.auth.keycloak.url}/realms/quarkus                                  |     
| `kvasir.pod.auth.oidc.principal-extractor.class-name`               | The fully qualified Java class name of the JWT principal extractor implementation to use.                                                                                                                                                                                                                                   | `kvasir.plugins.policyagent.openfga.extractors.SimpleJWTPrincipalExtractor` |
| `kvasir.pod.auth.oidc.principal-extractor.config`                   | A set of key-value pairs (String) with config to be supplied to the configured principal extractor class.                                                                                                                                                                                                                   | `{ "attribute-name":"preferred_username"}`                                  |
| `kvasir.pod.auth.oidc.jwt-allowed-clock-skew-seconds`               | Allowed clock skew in seconds to apply during JWT token validation.                                                                                                                                                                                                                                                         | `30`                                                                        |
| `kvasir.pod.auth.uma.server-url`                                    | URL of the UMA-compliant server.                                                                                                                                                                                                                                                                                            | http://localhost:4000/uma                                                   |
| `kvasir.pod.auth.uma.jwt-allowed-clock-skew-seconds`                | The fully qualified Java class name of the JWT principal extractor implementation to use.                                                                                                                                                                                                                                   | `kvasir.plugins.policyagent.openfga.extractors.SimpleJWTPrincipalExtractor` |
| `kvasir.pod.auth.uma.principal-extractor.config`                    | A set of key-value pairs (String) with config to be supplied to the configured principal extractor class.                                                                                                                                                                                                                   | `{ "attribute-name":"sub"}`                                                 |
| `kvasir.pod.auth.uma.jwt-allowed-clock-skew-seconds`                | Allowed clock skew in seconds to apply during JWT token validation.                                                                                                                                                                                                                                                         | `30`                                                                        |
| `kvasir.pod.auth.enable-solid-web-id`                               | Whether to enable Solid OIDC (i.e. allow authenticating with a Solid WebID).                                                                                                                                                                                                                                                | `false`                                                                     |
| `kvasir.pod.auth.require-dpop`                                      | Whether to require DPoP (Demonstrating Proof-of-Possession) tokens for protected resources (more secure).                                                                                                                                                                                                                   | `false`                                                                     |
| `kvasir.pod.auth.skip-dpop-ath-check`                               | Whether to skip the access token hash (ath) check for DPoP tokens. This setting is primarily intended for backward compatibility with clients that implement an earlier version of the DPoP specification.                                                                                                                  | `false`                                                                     |
| `kvasir.pod.auth.http-endpoint-policy-enforcer.url`                 | URL of the external HTTP endpoint for the policy enforcer.                                                                                                                                                                                                                                                                  |                                                                             |
| `kvasir.pod.auth.http-endpoint-policy-enforcer.basic-auth.username` | Sets the username when using Basic Auth for authenticating requests to the HTTP policy enforcer.                                                                                                                                                                                                                            |                                                                             |
| `kvasir.pod.auth.http-endpoint-policy-enforcer.basic-auth.password` | Sets the password when using Basic Auth for authenticating requests to the HTTP policy enforcer.                                                                                                                                                                                                                            |                                                                             |
| `kvasir.pod.auth.http-endpoint-policy-enforcer.api-key.key-name`    | Sets the API key name when using an API key for authenticating requests to the HTTP policy enforcer.                                                                                                                                                                                                                        |                                                                             |
| `kvasir.pod.auth.http-endpoint-policy-enforcer.api-key.key-value`   | Sets the API key value when using an API key for authenticating requests to the HTTP policy enforcer.                                                                                                                                                                                                                       |                                                                             |
| `kvasir.pod.auth.http-endpoint-policy-enforcer.api-key.send-via`    | Sets whether the API key should be passed via a HTTP Header (`header`) or a Query Parameter (`query`) when using an API key for authenticating requests to the HTTP policy enforcer.                                                                                                                                        |                                                                             |

### Bootstrap Configuration

Note: the `i` in `kvasir.bootstrap.pods[i]` refers to the index of the Pod in the bootstrap Pods list (
see [Quarkus guide on how collection type properties are mapped](https://quarkus.io/guides/config-mappings#collections)).

| Variable                                                                                | Description                                                                                                                                                                                                  | Default value |
|-----------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|
| `kvasir.bootstrap.pods[i].name`                                                         | The name of the pod to create at startup.                                                                                                                                                                    |               | 
| `kvasir.bootstrap.pods[i].owner-user-id`                                                | The user ID of the owner of the pod (optional). Primarily useful when using an external OIDC server (not the Kvasir built-in Keycloak). If not provided, we assume the userId is the same as the pod name.   |               |
| `kvasir.bootstrap.pods[i].auto-register-uma`                                            | If set to true, authorization is delegated to the configured UMA server for all resources (by default).                                                                                                      | `false`       |
| `kvasir.bootstrap.pods[i].auto-register-http-endpoint-policy-enforcer`                  | If set to true, authorization is delegated to the configured HTTP Endpoint Policy Enforcer for all resources (by default).                                                                                   | `false`       |
| `kvasir.bootstrap.pods[i].generate-clients[i].client-id`                                | Client id for the client to generate (using the built-in Keycloak).                                                                                                                                          |               |
| `kvasir.bootstrap.pods[i].generate-clients[i].enable-service-account`                   | Whether to enable the service account for this client.                                                                                                                                                       | `false`       |
| `kvasir.bootstrap.pods[i].generate-clients[i].client-secret`                            | The client secret, if applicable. Not required for public clients.                                                                                                                                           |               |
| `kvasir.bootstrap.pods[i].generate-clients[i].redirect-uris`                            | The redirect URIs for the client. Optional, as some clients may not require a redirect URI (e.g., service clients).                                                                                          |               |
| `kvasir.bootstrap.pods[i].generate-clients[i].enable-force-pkce`                        | Enforces use of Proof Key for Code Exchange ([PKCE](https://oauth.net/2/pkce/)) when enabled.                                                                                                                | `false`       |
| `kvasir.bootstrap.pods[i].generate-clients[i].openfga.relationships[i].target-resource` | Optional default relationships to add to OpenFGA related to this client. The target resource identifies the endpoint the relationship applies to, relative to the Pod URL (e.g. `/` is the root of the Pod). |               |
| `kvasir.bootstrap.pods[i].generate-clients[i].openfga.relationships[i].relations`       | The list of relations that apply to the target resource. For example, "reader", "writer", "owner" etc. Possible relations are defined in the OpenFGA schema being used.                                      |               |
| `kvasir.bootstrap.exit-after-setup`                                                     | If true, the Kvasir init-service will terminate after the setup is completed (or failed). (Useful for init containers, e.g. in Kubernetes).                                                                  | `false`       |

## Dependency Configuration

### Clickhouse Configuration

| Variable                        | Description                 | Default value |
|---------------------------------|-----------------------------|---------------|
| `kvasir.kg.clickhouse.host`     | Clickhouse server hostname. | localhost     |
| `kvasir.kg.clickhouse.port`     | Clickhouse server port.     | `8123`        |
| `kvasir.kg.clickhouse.user`     | Clickhouse username.        |               |
| `kvasir.kg.clickhouse.password` | Clickhouse password.        |               |

### Kafka configuration

| Variable                                     | Description                             | Default value  |
|----------------------------------------------|-----------------------------------------|----------------|
| `kvasir.messaging.kafka.bootstrap-servers`   | Comma-separated list of Kafka brokers.  | localhost:9092 |
| `kvasir.messaging.kafka.advertised-hostname` | Hostname to advertise to Kafka clients. | localhost      |   

### Auth Configuration (OpenFGA & Keycloak)

| Variable                   | Description          | Default value         |
|----------------------------|----------------------|-----------------------|
| `kvasir.pep.openfga.url`   | OpenFGA server url.  | http://localhost:8380 |
| `kvasir.auth.keycloak.url` | Keyclaok server url. | http://localhost:8280 |

### S3 Configuration

| Variable                       | Description         | Default value         |
|--------------------------------|---------------------|-----------------------|
| `kvasir.storage.s3.endpoint`   | S3 server endpoint. | http://localhost:9000 |               
| `kvasir.storage.s3.region`     | S3 server region.   | us-east-1             |
| `kvasir.storage.s3.access-key` | S3 access key.      | `kvasir`              |               
| `kvasir.storage.s3.secret-key` | S3 secret key.      | `kvasirkvasir`        |
