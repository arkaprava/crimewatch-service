# Packages the Spring Boot war built by `./gradlew bootWar`.
# Build context is the project root (see infra/docker-compose-app.yml).
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 1001 appuser
USER appuser

WORKDIR /app

# Suburb geocoding reference data — SuburbCacheLoader resolves this as a
# plain filesystem path relative to the working directory at startup, not
# a classpath resource, so it must be physically present in the image.
# Without it, australian_suburbs stays empty and every crime record fails
# to geocode (harmless — records still ingest with geocodeStatus:
# UNRESOLVED — but crimesNearLocation and suburb-boundary lookups don't work).
COPY data/suburbs/australian-suburbs.geojson data/suburbs/australian-suburbs.geojson

COPY build/libs/*.war app.war

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.war"]
