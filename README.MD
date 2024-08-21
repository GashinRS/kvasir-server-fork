PoC of a possible future Kvasir Data Storage Server

# Running in dev mode
To run the server in dev mode, you need Docker (for the dependencies Kafka and [Xtdb](https://xtdb.com)) and a Java 21 JDK.

```bash
docker compose up -d
./gradlew :services:monolith:quarkusDev
```

# Documentation
Visit the documentation pages at [https://ibcndevs.pages.ilabt.imec.be/solid/kvasir-next/](https://ibcndevs.pages.ilabt.imec.be/solid/kvasir-next/).

# Future Work
* Authentication and authorization
* Complete the GraphQL query model with more advanced features
* ~~Complete the inbox API with updates and deletes~~
* Design and implement a model for Virtual Knowledge Graphs (subsets of the KG based on a specific schema, with their own restricted inbox/query endpoints)
* Design and implement an API for low-level storage (e.g. based on S3 ~~or LDP~~). More advanced features can be supported via this layer in the future:
  * Auto-ingestion of semantic/structured data into the Knowledge Graph from uploaded files. [Status: a PoC was implemented for JSON-LD and Turtle files.]
  * Support for streaming audio/video by adding pipelines for transcoding and indexing.
* Add time-travel and historic access for KG data
* Proper JSON-LD request & response types for the Query endpoint, while still conforming to the GraphQL spec w.r.t. HTTP binding.
* Etc