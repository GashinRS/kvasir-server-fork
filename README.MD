Technical preview of the Kvasir Data Storage Server

# Running in dev mode

To run the server in dev mode, you need Docker (for the dependencies Minio, Kafka
and [Clickhouse](https://clickhouse.com)) and a Java 21 JDK.

```bash
docker compose up -d
./gradlew :services:monolith:quarkusDev
```

# Running with Docker Compose

When you don't need to build the code, a faster way to get a dev server (with persistent storage) up and running is to
use Docker Compose.

Download the folder [.deployment/docker-compose](./.deployment/docker-compose) to your local machine and run the
following command:

```bash
docker compose up -d
```

This will automatically create a pod at `http://localhost:8080/alice` for you to play with.
The settings for this pod can be modified via the file `application.yaml` in the `kvasir-config` folder.

# Documentation

Visit the documentation pages
at [https://ibcndevs.pages.ilabt.imec.be/solid/kvasir-next/](https://ibcndevs.pages.ilabt.imec.be/solid/kvasir-next/).

# Issues

The project is still in a very early stage of development, so there are many issues and missing features. If you find
any, please report them in the [Issues](https://gitlab.ilabt.imec.be/kvasir/kvasir-server/-/issues) section.

# Future Work

* Authentication and authorization (waiting to integrate with the Knows UMA Policy Server implementation)
* Complete the GraphQL query model with more advanced features
* Complete the subgraph (Slices) prototype implementation
* Provide a decent cursor-based pagination mechanism
* Optimize and harden the current prototype