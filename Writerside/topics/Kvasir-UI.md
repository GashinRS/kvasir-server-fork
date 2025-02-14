# Kvasir UI

You can interface with your pod using the Kvasir UI. For advanced operations we always refer to the actual 
[Kvasir APIs](API-Reference.md).

## S3 Browser
Interface with your pod as an S3 storage space. Files can be uploaded to and downloaded from your pod. 

![Kvasir UI - S3](ui_s3.png){ thumbnail="true" width="700" }

>Uploaded files in the proper RDF formats, can even be auto-ingested to the Knowledge Graph. (This can be managed in the
>_Settings_ page)
> 
{style="note"}


## GraphiQL
Query all knowledge in your pod using the generated GraphQL Schema. The Schema is updated every time new knowledge is 
added. The user interface is an embedded [GraphiQL](https://github.com/graphql/graphiql) interface.

![Kvasir UI - GraphiQL](ui_graphiql.png){ thumbnail="true" width="700" }

## Changes
Adding content to the knowledge graph is done through [Change requests](Changes.md). From this page you can create and 
view these change requests. The Changes page shows an overview of the Changes history. You can click on a change report 
to see its details.

![Kvasir UI - Changes](ui_changes.png){ thumbnail="true" width="700" }

## Slices
You can create a subgraph - [Slice](Slices.md) - of your knowledge graph. You can define the data model of this Slice,
by creating and defining a new Slice schema. 

![Kvasir UI - Slices](ui_slices.png){ thumbnail="true" width="700" }

Once a Slice is created, you can click the _query_ button to open an GraphiQL editor specifically for this Slice and 
its definition. You can query the custom created GraphQL Schema based on your Slice definition.

![Kvasir UI - Slices - Query](ui_slices_query.png){ thumbnail="true" width="700" }

> These Slices can (later) be used as an object in _access policies_, allowing access control rules for read/write (and 
> more specific scopes) to be put in place for specific users/clients.
> 
{style="note"}