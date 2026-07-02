# Packages the Spring Boot war built by `./gradlew bootWar`.
# Build context is the project root (see infra/docker-compose-app.yml).
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 1001 appuser
USER appuser

WORKDIR /app
COPY build/libs/*.war app.war

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.war"]
