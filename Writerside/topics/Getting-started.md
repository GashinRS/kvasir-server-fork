# Getting started

## Running in dev mode
To run the server in dev mode, you need Docker (for the dependencies Kafka and [Xtdb](https://xtdb.com)) and a Java 21 JDK.

Checkout the repository and then run the following commands:

```bash
docker compose up -d
./gradlew :services:monolith:quarkusDev
```
