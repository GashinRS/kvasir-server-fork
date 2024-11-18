# Slices

The Changes and Query APIs are expressive ways to interact with the Knowledge Graph of a Pod. However, they provide
access to the entire Knowledge Graph, which may not always be necessary or desirable. In some cases, it may be more
efficient to work with a subset of the Knowledge Graph, known as a "slice". Also from the perspective of access control,
it is easier to manage permissions on a slice than on the entire Knowledge Graph.

In a way, a slice is similar to a view in a relational database, where only a subset of the data is exposed to the user.
Slices can be used to filter the data based on certain criteria, such as a specific type of resource, a particular
property, a specific value range, etc. However, unlike views, slices are not restricted to read-only access; they can
also be used for write operations (e.g. restricting the data that can be inserted for a specific slice to a certain
shape).

## Defining a Slice

## Retrieving Slice data

## Mutations on a Slice

TODO

