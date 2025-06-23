# Pod Management

> The Pod Management API can be used to create and manage Pods, but is subject to change in future releases once we have
> a clear view on user identity and onboarding (e.g. as a result of the upcoming task of conforming
> to the [Trustflows specification](https://spec.knows.idlab.ugent.be/trustflows/all/e45c02bd3711f5734eeb75548ff37a70f57c465e/).).
> {style="warning"}

## Pod configuration

### Default context

You can configure a map of URI prefixes as a default context for the Pod. This default context is used when interfacing
with the global GraphQL endpoint of the Pod's Knowledge Graph. This allows the execution of standard GraphQL queries (
which do not have context information) against the Pod's Knowledge Graph.

The context is represented as a JSON string. For example:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "kss:configuration": {
    "kss:defaultContext": "{ \"so\": \"http://schema.org/\" }"
  }
}
```

### Auto-ingest RDF

You can configure the Pod to automatically ingest RDF files uploaded to the Pod's S3 storage into the Knowledge Graph.
By default, this is disabled.

For example:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "kss:configuration": {
    "kss:autoIngestRDF": "true"
  }
}
```

### Auth configuration

You can configure the authorization configuration for the Pod (e.g. which authorization server or protocol to use).
By default, an embedded Keycloak server is used, which is automatically configured by the Kvasir backend.

For example:

```json
{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "kss:configuration": {
    "kss:authConfiguration": {
      "kss:authServerUrl": "https://keycloak.example.com/auth",
      "kss:clientId": "kvasir-server",
      "kss:clientSecret": "someSecret"
    }
  }
}
```

## Creating a Pod

To create a Pod, either provide a configuration file containing the configuration on startup time, or call the Pod
Management API.

### Via configuration file

You can create a Pod by providing a configuration file on startup. The configuration file is in YAML format. For
example:

```yaml
kvasir:
  bootstrap:
    pods:
      - name: alice
        default-context: |
          {
            "kss": "https://kvasir.discover.ilabt.imec.be/vocab#",
            "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
            "xsd": "http://www.w3.org/2001/XMLSchema#",
            "schema": "http://schema.org/",
            "ex": "http://example.org/",
            "saref": "https://saref.etsi.org/core/"
          }
        auto-ingest-rdf: true
```

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
  "kss:configuration": {
    "kss:defaultContext": "{ \"so\": \"http://schema.org/\" }",
    "kss:autoIngestRDF": "true"
  }
}
```

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
  "kss:configuration": {
    "kss:defaultContext": "{ \"so\": \"http://schema.org/\" }",
    "kss:autoIngestRDF": "true"
  }
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
  "kss:configuration": {
    "kss:defaultContext": "{ \"so\": \"http://schema.org/\" }",
    "kss:autoIngestRDF": "false"
  }
}
```

Note that it is not possible to change the Pod's name or the Pod's ID once it has been created.