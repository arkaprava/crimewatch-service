# Crime Info Service

Spring Boot 3 service that ingests Australian crime data from state and territory
open data portals into MongoDB and exposes it through a GraphQL API.

## Stack

- Java 21
- Spring Boot 3.4
- Spring Data MongoDB
- Spring for GraphQL
- Gradle (Groovy DSL)
- Testcontainers

## Prerequisites

- JDK 21
- Docker (for MongoDB and integration tests)

## Quick start

1. Start MongoDB:

```bash
docker compose -f infra/docker-compose-mongo.yml up -d
```

2. Run the application:

```bash
./gradlew bootRun
```

3. Open GraphiQL at [http://localhost:8080/graphiql](http://localhost:8080/graphiql)

## Docker deployment

Build the war and run the full stack (MongoDB + application) with the compose
files in [`infra/`](infra/):

```bash
./gradlew bootWar
docker compose -f infra/docker-compose-mongo.yml -f infra/docker-compose-app.yml up -d --build
```

The application image is built from [`infra/Dockerfile`](infra/Dockerfile) and
runs the executable war on port 8080 with the `prod` profile.

## Crime data ingestion

Sources are configured under `ingestion.sources` in
[`application.yml`](src/main/resources/application.yml). One entry exists per
Australian state/territory (QLD, NSW, VIC, SA, WA, TAS, NT, ACT), all backed by
CKAN open data portals. For each source you want to use, set the dataset's
datastore `resource-id` and flip `enabled: true`.

List configured sources:

```graphql
query {
  ingestionSources
}
```

Trigger ingestion (one source, or omit `source` to run all enabled ones):

```graphql
mutation {
  ingestCrimeData(source: "qld-police-offences") {
    source
    fetched
    inserted
    duplicates
    failed
    error
  }
}
```

Scheduled ingestion is off by default; enable it via `ingestion.schedule.enabled`
(cron configurable through `ingestion.schedule.cron`).

## Profiles

| Profile | Purpose |
|---------|---------|
| `dev` (default) | Local development with GraphiQL enabled |
| `prod` | Production; uses `MONGODB_URI` env var, GraphiQL disabled |
| `test` | Used by integration tests |

Run with a specific profile:

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
```

## Health check

```bash
curl http://localhost:8080/actuator/health
```

## Testing

```bash
./gradlew test
```

Integration tests use Testcontainers and require Docker. When Docker is not available, those tests are skipped automatically.

## Project structure

```
infra/               # Dockerfile and docker-compose files
src/main/java/com/example/springgraphqlmongo/
├── config/          # Ingestion configuration properties and wiring
├── domain/          # MongoDB @Document entities
├── ingestion/       # Data source contract, CKAN adapter, scheduler
├── repository/      # Spring Data repositories
├── service/         # Business logic
├── graphql/         # GraphQL controllers
└── exception/       # Shared exceptions
```

## Environment variables

See [`.env.example`](.env.example) for production-style configuration.
