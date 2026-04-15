# Pod Management

<show-structure depth="2"/>

The Pod Management API can be used to create and manage Pods, but is subject to change in future releases. We are
working on a more extensible mechanism to allow integrations with other specifications and ecosystems (
e.g. [Trustflows specification](https://spec.knows.idlab.ugent.be/trustflows/all/e45c02bd3711f5734eeb75548ff37a70f57c465e/)
and EU Data Spaces).

## Pod configuration

A Pod can be configured with various settings that affect its behavior. The configuration is supplied as a YAML (or
JSON) string for which the structure and possible values are defined by the PodConfig interface
in [KvasirConfig.kt](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/blob/main/libs/definitions/src/main/kotlin/kvasir/definitions/config/KvasirConfig.kt).
See also: [Configuration Reference](Configuration-Reference.md#default-pod-configuration)

The following sections describe the available configuration options.

### Default context

You can configure a map of URI prefixes as a default context for the Pod. This default context is used when interfacing
with the global GraphQL endpoint of the Pod's Knowledge Graph. This allows the execution of standard GraphQL queries (
which do not have context information) against the Pod's Knowledge Graph.

For example:

```yaml
default-context:
  ex: http://example.org/
  so: http://schema.org/
```

Only basic prefix-to-URI mappings are supported in the default context. More complex JSON-LD constructs (e.g.
`@reverse`, `@type`, etc.) are not supported in the default context.

### Auto-ingest RDF

You can configure the Pod to automatically ingest RDF files uploaded to the Pod's S3 storage into the Knowledge Graph.
By default, this is disabled.

For example:

```yaml
auto-ingest-rdf: true
```

### OIDC configuration

You can configure the OIDC server the Pod should use for authentication.
By default, an embedded Keycloak server is used, which is automatically configured by the Kvasir backend.

For example:

```yaml
auth:
  oidc:
    server-url: http://localhost:8280/auth/realms/quarkus
    principal-extractor:
      class-name: kvasir.plugins.policyagent.openfga.extractors.SimpleJWTPrincipalExtractor
      config:
        attribute-name: preferred_username
    jwt-allowed-clock-skew-seconds: 30
```

Both `principal-extractor` and `jwt-allowed-clock-skew-seconds` are optional configuration options.
The `principal-extractor` allows you to specify a custom class that extracts the principal from the JWT token, as
different OIDC providers may use different claim names for the principal.

### UMA configuration

You can configure the UMA server the Pod should use for authorization. If a UMA server is configured, it is not required
to also configure an OIDC server, as the UMA supplied token contains all necessary information for authentication and
authorization.

For example:

```yaml
auth:
  uma:
    server-url: https://uma.example.org
    client-id: 36b11819-39cf-4bef-8b3b-f38de55c1b6d
    client-secret: 412d7759ba54850b915...d7b9a2ace6649b19a011b139fce3079
```

When configuring UMA, you need to provide the `server-url`. You might need a valid `client-id` and `client-secret` to
register Kvasir as a Resource Server. Those credentials can be filled in here or via the settings page in
the [Kvasir UI](Kvasir-UI.md#settings).

Note: the UMA configuration is similar to the OIDC configuration. You can also specify a custom `principal-extractor`
and customize the `jwt-allowed-clock-skew-seconds`.

### External HTTP Endpoint Policy Enforcer

You can configure an external HTTP Endpoint Policy Enforcer for the Pod. This allows delegating policy decisions to an
external service (see [Delegating to an external HTTP Endpoint Policy Enforcer](Access-Control.md#delegating-to-an-external-http-endpoint-policy-enforcer)).

For example:

```yaml
auth:
  http-endpoint-policy-enforcer:
    url: https://policy-enforcer.example.org
    basic-auth: # Optional basic authentication for the policy enforcer endpoint
      username: pep-user
      password: pep-password
    api-key: # Optional API key authentication for the policy enforcer endpoint
      key-name: x-api-key
      key-value: pepApiKey
      send-via: header # or 'query'
```

### Additional auth settings

The following example demonstrates additional (optional) authentication configuration options that can be set for a Pod:

```yaml
auth:
  # Whether to enable WebID support for the Pod (Solid-OIDC).
  enable-solid-web-id: true
  # Whether to require DPoP (Demonstrating Proof of Possession) tokens for requests to the Pod (more secure).
  require-dpop: true
  # Whether to skip DPoP ATH checks (for compatibility with legacy DPoP clients, do not use in production).
  skip-dpop-ath-check: false
```

For more context on these options, please refer to the [Identity & Security](Identity-and-Security.md)
section.

## Creating a Pod

To create a Pod, either provide a configuration file containing the configuration on startup time, or call the Pod
Management API.

### Via configuration file

You can create a Pod by adding entries to the `kvasir.bootstrap.pods` node in the Kvasir configuration file.
Each pod bootstrap entry consists of a Pod configuration as described in the previous section, along with some
additional settings (such as the pod name, user-id for the owner, etc).

The pod bootstrap configuration is defined by the `BootstrapPodConfig` interface
in [KvasirConfig.kt](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/blob/main/libs/definitions/src/main/kotlin/kvasir/definitions/config/KvasirConfig.kt).

For example:

```yaml
kvasir:
  bootstrap:
    pods:
      - name: alice
        configuration:
          auto-ingest-rdf: true
          auth:
            oidc:
              server-url: https://alice.example.org/oidc/
        generate-clients:
          - client-id: demo-client
            client-secret: testtest
            enable-service-account: true
            openfga:
              relationships:
                - target-resource: "/"
                  relations: [ "reader", "writer", "deleter" ]
```

As you can see, from this configuration entry you can also create clients and setup permissions for the Pod. The
`generate-clients` section allows you to specify a list of clients that should be created for the Pod. Each client
can have a `client-id`, `client-secret`, and an optional `enable-service-account` flag to indicate whether
a service account should be created for the client. The `openfga` section allows you to specify the OpenFGA
relationships for the client, which define the permissions that the client has on the Pod's resources. In this example,
the client has `reader`, `writer`, and `deleter` permissions on the Pod's root resource (`/`). See
also: [Configuration Reference](Configuration-Reference.md#bootstrap-configuration).

> Creating clients is only supported when using a Keycloak OIDC server that is managed by Kvasir (i.e. the Kvasir
> instance has a Keycloak admin client configured).
> { style="note" }

### Via Pod Management API

To create a Pod via the Pod Management API, you can send a POST request to the API root (`/`) endpoint with a JSON-LD
request body, containing its configuration and a name for the Pod. This name is used in the path of the Pod's full URI
and must be unique within the scope of the Kvasir instance.

**POST** `http://localhost:8080/`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "kss:name": "alice",
  "kss:configuration": "{}"
}
```

The value for `kss:configuration` is a YAML (or JSON) string representing the Pod configuration, as described
in [Pod configuration](#pod-configuration). An empty object (`{}`) can be provided to use the system defaults.

## Get Pod configuration

To retrieve the configuration of a Pod, you can send a GET request to the Pod's root endpoint (`/{podId}/`).

**GET** `http://localhost:8080/alice/`
Response body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "kss:name": "alice",
  "kss:configuration": "{\"auth\":{\"http-endpoint-policy-enforcer\":null,\"require-dpop\":false,\"uma\":null,\"oidc\":null,\"enable-solid-web-id\":true},\"default-context\":{},\"auto-ingest-rdf\":true}"
}
```

## Updating a Pod

To update the configuration of a Pod, you can send a PUT request to the Pod's root endpoint (`/{podId}/`) with the
updated configuration.

**PUT** `http://localhost:8080/alice/`

Request body:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "kss:configuration": "{\"auth\":{\"http-endpoint-policy-enforcer\":null,\"require-dpop\":true,\"uma\":null,\"oidc\":null,\"enable-solid-web-id\":true},\"default-context\":{},\"auto-ingest-rdf\":true}"
}
```

Note that it is not possible to change the Pod's name or the Pod's ID once it has been created.
