Technical preview of the Kvasir Data Storage Server

# Running in dev mode
To run the server in dev mode, you need Docker (for the dependencies Kafka and [Xtdb](https://xtdb.com)) and a Java 21 JDK.

```bash
docker compose up -d
./gradlew :services:monolith:quarkusDev
```

# Running with Docker

# Documentation
Visit the documentation pages at [https://ibcndevs.pages.ilabt.imec.be/solid/kvasir-next/](https://ibcndevs.pages.ilabt.imec.be/solid/kvasir-next/).

# Future Work
* Authentication and authorization (waiting to integrate with the Knows UMA Policy Server implementation)
* Complete the GraphQL query model with more advanced features
* Complete the subgraph (Slices) prototype implementation
* Provide a decent cursor-based pagination mechanism
* Optimize and harden the current prototype