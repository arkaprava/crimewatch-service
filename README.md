# Crime Info Service

Spring Boot 3 service that ingests Australian crime data from state and territory
open data portals into MongoDB and exposes it through a GraphQL API.

All application endpoints are served by two GraphQL controllers
(`CrimeIncidentController`, `IngestionController`) plus Spring Boot Actuator.
There are no REST controllers for crime data.

## Stack

- Java 21
- Spring Boot 3.4
- Spring Data MongoDB
- Spring for GraphQL
- Spring Security (API key authentication)
- Caffeine (in-process read cache)
- Apache POI (SA offender spreadsheets)
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

3. Open GraphiQL at [http://localhost:8080/graphiql](http://localhost:8080/graphiql) (enabled in the `dev` profile). Add the read API key in the request headers panel (see [API authentication](#api-authentication)).

## HTTP endpoints

| Method | Path | Description | Auth | Profile |
|--------|------|-------------|------|---------|
| `POST` | `/graphql` | GraphQL API (all queries and mutations below) | API key required | all |
| `GET` | `/graphiql` | Interactive GraphQL IDE | Open (send API key with requests) | `dev` only |
| `GET` | `/actuator/health` | Application health check | Public | all |
| `GET` | `/actuator/info` | Application name and version | API key required | all |

## API authentication

GraphQL and actuator endpoints (except health) require a valid API key. Authentication is
stateless — send the key on every request.

### API key roles

| Key type | Config property | Roles granted | Access |
|----------|---------------|---------------|--------|
| Read | `security.api-keys.read-keys` | `CRIME_READ` | All GraphQL **queries** |
| Ingest | `security.api-keys.ingest-keys` | `CRIME_INGEST`, `CRIME_READ` | Queries + `ingestCrimeData` **mutation** |

### Sending the API key

Use either header format:

```http
X-API-Key: your-key-here
```

```http
Authorization: ApiKey your-key-here
```

### Dev keys (`dev` profile)

| Key | Value |
|-----|-------|
| Read | `dev-read-key` |
| Ingest | `dev-ingest-key` |

### Responses

| Situation | HTTP status | Notes |
|-----------|-------------|-------|
| Missing or invalid API key | `401 Unauthorized` | Request rejected before GraphQL executes |
| Valid read key on `ingestCrimeData` | `200` with GraphQL errors | `FORBIDDEN` — "Access is denied" |
| Valid ingest key on mutation | `200` | Ingestion proceeds |
| Rate limit exceeded | `429 Too Many Requests` | Includes `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining` headers |

### Rate limiting

Per-client token-bucket limits apply to `/graphql` and `/actuator/**` (except
`GET /actuator/health` by default). Limits are tracked in-memory per API key, or
per IP for unauthenticated requests.

| Tier | Applies to | Default (`application.yml`) | `dev` profile |
|------|------------|----------------------------|---------------|
| `read` | Read API keys | 120 req/min | 300 req/min |
| `ingest` | Ingest API keys | 20 req/min | 60 req/min |
| `anonymous` | Unauthenticated callers | 30 req/min | 30 req/min |

Configure in YAML:

```yaml
security:
  rate-limit:
    enabled: true
    limit-health-checks: false
    read:
      requests-per-minute: 120
    ingest:
      requests-per-minute: 20
    anonymous:
      requests-per-minute: 30
```

Production overrides via environment variables:
`RATE_LIMIT_READ_PER_MINUTE`, `RATE_LIMIT_INGEST_PER_MINUTE`,
`RATE_LIMIT_ANONYMOUS_PER_MINUTE`.

### GraphiQL

GraphiQL itself is not login-protected in `dev`, but `/graphql` requests still require a key.
In the GraphiQL UI, open **Headers** and add:

```json
{
  "X-API-Key": "dev-read-key"
}
```

Use `dev-ingest-key` when running `ingestCrimeData` mutations.

### Production

Set separate keys via environment variables (see [Environment variables](#environment-variables)).
GraphQL schema introspection is disabled in the `prod` profile.

### GraphQL transport

```bash
curl -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-read-key' \
  -d '{"query":"{ ingestionSources }"}'
```

Trigger ingestion (requires ingest key):

```bash
curl -X POST http://localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-ingest-key' \
  -d '{"query":"mutation { ingestCrimeData(source: \"sa-police-crime-statistics\") { source inserted } }"}'
```

### Actuator

```bash
curl http://localhost:8080/actuator/health
curl -H 'X-API-Key: dev-read-key' http://localhost:8080/actuator/info
```

## GraphQL API

Schema: [`src/main/resources/graphql/schema.graphqls`](src/main/resources/graphql/schema.graphqls)

### Queries — `CrimeIncidentController`

| Field | Arguments | Returns | Auth | Description |
|-------|-----------|---------|------|-------------|
| `crimeIncident` | `id: ID!` | `CrimeIncident` | Read | Single incident by MongoDB id |
| `crimeIncidents` | `city`, `state`, `crimeType`, `status` (all optional) | `[CrimeIncident!]!` | Read | Filtered search; filters combined with AND |
| `crimesNearLocation` | `latitude: Float!`, `longitude: Float!`, `radiusKm: Float = 10.0`, `state` (optional) | `[CrimeIncident!]!` | Read | Incidents near a point; uses suburb perimeter/centroid for SA aggregates and point proximity for incident-level records |

**Example — get incident by id:**

```graphql
query {
  crimeIncident(id: "664a1b2c3d4e5f6789012345") {
    id
    title
    crimeType
    severity
    status
    granularity
    offenceCount
    reportingPeriod
    geocodeStatus
    location {
      city
      state
      country
      postalCode
      coordinates { latitude longitude }
    }
    suburbBoundary {
      suburbId
      name
      state
      centroid { latitude longitude }
      perimeter { latitude longitude }
    }
    offenderContext {
      offenderCount
      principalOffence
      correlationNote
    }
    occurredAt
    reportedAt
  }
}
```

**Example — search incidents:**

```graphql
query {
  crimeIncidents(city: "Adelaide", state: "SA", crimeType: ASSAULT) {
    title
    granularity
    offenceCount
    location { city state }
  }
}
```

**Example — crimes near a location (SA):**

```graphql
query {
  crimesNearLocation(
    latitude: -34.9285
    longitude: 138.6007
    radiusKm: 5.0
    state: "SA"
  ) {
    title
    granularity
    offenceCount
    reportingPeriod
    suburbBoundary { name }
    offenderContext { offenderCount correlationNote }
  }
}
```

### Queries — `IngestionController`

| Field | Arguments | Returns | Auth | Description |
|-------|-----------|---------|------|-------------|
| `ingestionSources` | — | `[String!]!` | Read | Names of all configured ingestion sources |

```graphql
query {
  ingestionSources
}
```

### Mutations — `IngestionController`

| Field | Arguments | Returns | Auth | Description |
|-------|-----------|---------|------|-------------|
| `ingestCrimeData` | `source` (optional), `refresh` (optional, default `false`) | `[IngestionResult!]!` | Ingest | Trigger ingestion for one source, or all enabled sources when `source` is omitted |

```graphql
mutation {
  ingestCrimeData(source: "sa-police-crime-statistics", refresh: false) {
    source
    fetched
    inserted
    duplicates
    failed
    error
  }
}
```

Ingest all enabled sources:

```graphql
mutation {
  ingestCrimeData {
    source
    inserted
    duplicates
    failed
  }
}
```

Force re-download of SA cached files:

```graphql
mutation {
  ingestCrimeData(source: "sa-police-crime-statistics", refresh: true) {
    source
    inserted
  }
}
```

### Types

| Type | Notes |
|------|-------|
| `CrimeIncident` | Common record stored in `crime_incidents`; supports both incident-level (QLD) and suburb-aggregate (SA) data |
| `RecordGranularity` | `INCIDENT` or `SUBURB_AGGREGATE` |
| `GeocodeStatus` | `RESOLVED`, `UNRESOLVED`, or `APPROXIMATE` |
| `SaOffenderContext` | State-level offender correlation attached to SA aggregate records |
| `SuburbBoundary` | Denormalised suburb name, centroid, and perimeter polygon |
| `IngestionResult` | Per-source ingestion summary (`fetched`, `inserted`, `duplicates`, `failed`, `error`) |

Enums: `CrimeType`, `CrimeSeverity`, `CrimeStatus`, `RecordGranularity`, `GeocodeStatus`.

## Data model

All ingested data is stored in a single MongoDB collection:

| Collection | Entity | Purpose |
|------------|--------|---------|
| `crime_incidents` | `CrimeIncident` | Normalised crime records from all sources |
| `australian_suburbs` | `AustralianSuburb` | Cached suburb names, centroids, and perimeter polygons for geocoding |

Records are deduplicated by `(source, externalId)`.

## Crime data ingestion

Sources are configured under `ingestion.sources` in
[`application.yml`](src/main/resources/application.yml).

### Source types

| `type` | Adapter | Portal | Status |
|--------|---------|--------|--------|
| `ckan` (default) | `CkanCrimeDataSource` | CKAN `datastore_search` API | QLD configured in `dev`; others need `resource-id` |
| `sa-crime-statistics` | `SaCrimeStatisticsDataSource` | [data.sa.gov.au](https://data.sa.gov.au/data/dataset/crime-statistics) CSV cache | Enabled by default |

### CKAN sources (QLD, NSW, VIC, WA, TAS, NT, ACT)

Set the dataset's datastore `resource-id` and `enabled: true` for each source.
The `dev` profile enables QLD police-district statistics:

```yaml
- name: qld-police-offences
  enabled: true
  type: ckan
  base-url: https://www.data.qld.gov.au
  resource-id: 9f7a7eed-bfba-44ba-8288-812f6cc26115
```

### South Australia (cache-first)

SA crime statistics from [data.sa.gov.au](https://data.sa.gov.au/data/dataset/crime-statistics)
are ingested as `SUBURB_AGGREGATE` records. Files are read from `data/sa/` first;
remote downloads occur when the cache is missing, stale, or `refresh: true` is passed.

**Cache freshness** is checked on every ingestion:

1. `ingestion.sa.cache-ttl` (default 7 days)
2. CKAN resource metadata change (`hash`, `last_modified`, `revision_id`, `url`)
3. Local file SHA-256 vs manifest checksum

Offender reference data from
[Recorded Crime - Offenders](https://data.sa.gov.au/data/dataset/recorded-crime-offenders)
is correlated at SA state level and attached as `offenderContext`.

Suburb boundaries and centroids are loaded from
`data/suburbs/australian-suburbs.geojson` into `australian_suburbs` on startup.

**Cache layout:**

```
data/
  sa/
    crime-statistics/
      crime-statistics-2024-25.csv
      manifest-crime-statistics-2024-25.csv.json
    recorded-crime-offenders/
      recorded-crime-offenders.csv
  suburbs/
    australian-suburbs.geojson
```

### Scheduled ingestion

Off by default. Enable via `ingestion.schedule.enabled` (cron: `ingestion.schedule.cron`,
default `0 0 3 * * *`).

## Read cache

GraphQL read queries are cached in-process with Caffeine. Caches are evicted
automatically after ingestion completes.

| Cache | Backed method | Default TTL |
|-------|---------------|-------------|
| `crime-by-id` | `crimeIncident` | 5 min |
| `crime-search` | `crimeIncidents` | 15 min |
| `crimes-near` | `crimesNearLocation` | 10 min |

Configure via `cache.crime.*` in [`application.yml`](src/main/resources/application.yml).

## Profiles

| Profile | Purpose |
|---------|---------|
| `dev` (default) | Local development; GraphiQL enabled; dev API keys; QLD + SA sources enabled |
| `prod` | Production; `MONGODB_URI` and API key env vars; GraphiQL and introspection disabled |
| `test` | Integration tests; fixed test API keys; SA source only |

Run with a specific profile:

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
```

## Docker deployment

Build the war and run the full stack (MongoDB + application):

```bash
./gradlew bootWar
docker compose -f infra/docker-compose-mongo.yml -f infra/docker-compose-app.yml up -d --build
```

The application image is built from [`infra/Dockerfile`](infra/Dockerfile) and
runs on port 8080 with the `prod` profile.

Initialize MongoDB indexes and validators:

```bash
docker exec -i crime-info-mongodb mongosh --quiet < infra/mongo-init.js
```

## Testing

```bash
./gradlew test
```

Integration tests use Testcontainers and require Docker. When Docker is not
available, those tests are skipped automatically.

## Project structure

```
data/                  # SA dataset cache and suburb GeoJSON
infra/                 # Dockerfile, docker-compose, mongo-init.js
src/main/java/com/example/springgraphqlmongo/
├── cache/             # Caffeine read-cache key generation and eviction
├── config/            # Ingestion, cache, security, and scheduling configuration
├── domain/            # MongoDB @Document entities
├── ingestion/         # Data source adapters, SA cache, geocoding
├── repository/        # Spring Data repositories
├── security/          # API key filter and role-based authorization
├── service/           # Business logic (ingestion + read queries)
├── graphql/           # GraphQL controllers and DTOs
└── exception/         # Shared exceptions
```

## Environment variables

| Variable | Description | Default |
|----------|-------------|---------|
| `MONGODB_URI` | MongoDB connection string (`prod` profile) | `mongodb://localhost:27017/crime_info_service` |
| `CRIME_READ_API_KEY` | API key for GraphQL queries (`prod` profile) | — |
| `CRIME_INGEST_API_KEY` | API key for `ingestCrimeData` mutation (`prod` profile) | — |
| `RATE_LIMIT_READ_PER_MINUTE` | Read-tier rate limit (`prod` profile) | `120` |
| `RATE_LIMIT_INGEST_PER_MINUTE` | Ingest-tier rate limit (`prod` profile) | `20` |
| `RATE_LIMIT_ANONYMOUS_PER_MINUTE` | Anonymous rate limit (`prod` profile) | `30` |

API keys can also be configured in YAML under `security.api-keys.read-keys` and
`security.api-keys.ingest-keys` (see [`application-dev.yml`](src/main/resources/application-dev.yml)).

See [`.env.example`](.env.example).
