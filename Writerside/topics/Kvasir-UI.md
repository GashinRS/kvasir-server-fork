# Kvasir UI

You can interface with your pod using the Kvasir UI. For advanced operations we always refer to the actual
[Kvasir APIs](API-Reference.md).

The Kvasir UI is divided into different pages, accessible through the top menu.

## GraphiQL

Query all knowledge in your pod using the generated GraphQL Schema. The Schema is updated every time new knowledge is
added. The user interface is an embedded [GraphiQL](https://github.com/graphql/graphiql) interface.

![Kvasir UI - GraphiQL](01_graphiql.png){ thumbnail="true" width="700" }

## S3 Browser

Interface with your pod as an S3 storage space. Files can be uploaded to and downloaded from your pod.

![Kvasir UI - S3](02_s3browser.png){ thumbnail="true" width="700" }

> Uploaded files in the proper RDF formats, can even be auto-ingested to the Knowledge Graph. (This can be managed in
> the _Settings_ page)
> {style="note"}

## Changes

Adding content to the knowledge graph is done through [Change requests](Changes.md). From this page you can create and
view these change requests. The Changes page shows an overview of the Changes history. 

![Kvasir UI - Changes](03_changes.png){ thumbnail="true" width="700" }

You can click on a change report to see its details.

![Kvasir UI - Change Report](04_changes_details.png){ thumbnail="true" width="700" }

## Slices

You can create a subgraph - [Slice](Slices.md) - of your knowledge graph. You can define the data model of this Slice,
by creating and defining a new Slice schema.

![Kvasir UI - Slices](05_slices.png){ thumbnail="true" width="700" }

Once a Slice is created, you can click the _query_ button to open an GraphiQL editor specifically for this Slice and
its definition. You can query the custom created GraphQL Schema based on your Slice definition.

![Kvasir UI - Slices - Query](06_slices_query.png){ thumbnail="true" width="700" }

> These Slices can (later) be used as an object in _access policies_, allowing access control rules for read/write (and
> more specific scopes) to be put in place for specific users/clients.
> {style="note"}

## Access Control

This page allows you to manage the access control policies for your pod, by creating or deleting relationships between
users and resources.

You can even create access control delegations, referring to an externally configured UMA server or Http Policy 
Enforcement Point. 

![Kvasir UI - Access Control](07_accesscontrol.png){ thumbnail="true" width="700" }

## Settings
The Settings page allows you to configure some general settings overrides for your pod. Settings that appear _locked_ 
are following the default platform configures settings. Unlock them by clicking them, and you can override them and save
you pod-specific overrides. (The _Runtime Config_ button shows the effective configuration of your pod, including all 
overrides)

![Kvasir UI - Settings](08_settings.png){ thumbnail="true" width="700" }