package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeSeverity;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.Location;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.domain.SaOffenderContext;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.support.GraphQlSecurityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class GraphQlApiIntegrationTest {

	private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7");

	@Container
	static MongoDBContainer mongoDBContainer = new MongoDBContainer(MONGO_IMAGE);

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
	}

	@Autowired
	private HttpGraphQlTester graphQlTester;

	@Autowired
	private CrimeIncidentRepository repository;

	private String sydneyIncidentId;

	private HttpGraphQlTester readClient() {
		return GraphQlSecurityTestSupport.withReadKey(graphQlTester);
	}

	private HttpGraphQlTester ingestClient() {
		return GraphQlSecurityTestSupport.withIngestKey(graphQlTester);
	}

	@BeforeEach
	void seed() {
		repository.deleteAll();

		CrimeIncident sydney = repository.save(incident("ext-1", "Phone snatched at Town Hall", CrimeType.THEFT,
				CrimeStatus.REPORTED, "Sydney", "NSW", -33.8732, 151.2069));
		sydneyIncidentId = sydney.getId();

		repository.save(incident("ext-2", "Car stolen overnight", CrimeType.THEFT, CrimeStatus.UNDER_INVESTIGATION,
				"Parramatta", "NSW", -33.8151, 151.0011));

		CrimeIncident melbourne = incident("ext-3", "Shopfront vandalised", CrimeType.VANDALISM, CrimeStatus.CLOSED,
				"Melbourne", "VIC", -37.8136, 144.9631);
		melbourne.setGeoCoordinates(new GeoJsonPoint(new Point(144.9631, -37.8136)));
		repository.save(melbourne);

		repository.save(richAggregateIncident());
	}

	@Test
	void crimeIncidentQueryReturnsIncidentById() {
		readClient()
				.document("""
						query($id: ID!) {
						  crimeIncident(id: $id) {
						    id
						    title
						    crimeType
						    severity
						    status
						    source
						    externalId
						    location { city state country coordinates { latitude longitude } }
						    occurredAt
						  }
						}
						""")
				.variable("id", sydneyIncidentId)
				.execute()
				.path("crimeIncident.title")
				.entity(String.class)
				.isEqualTo("Phone snatched at Town Hall")
				.path("crimeIncident.crimeType")
				.entity(String.class)
				.isEqualTo("THEFT")
				.path("crimeIncident.location.city")
				.entity(String.class)
				.isEqualTo("Sydney")
				.path("crimeIncident.occurredAt")
				.entity(String.class)
				.isEqualTo("2026-06-01T10:00:00Z");
	}

	@Test
	void crimeIncidentQueryReturnsNotFoundForUnknownId() {
		readClient()
				.document("""
						query {
						  crimeIncident(id: "does-not-exist") {
						    id
						  }
						}
						""")
				.execute()
				.errors()
				.expect(error -> error.getMessage().contains("Crime incident not found"))
				.verify();
	}

	@Test
	void crimeIncidentsQueryFiltersByCityStateTypeAndStatus() {
		readClient()
				.document("""
						query {
						  crimeIncidents(city: "sydney", state: "NSW", crimeType: THEFT, status: REPORTED) {
						    title
						    location { city }
						  }
						}
						""")
				.execute()
				.path("crimeIncidents")
				.entityList(Object.class)
				.hasSize(1)
				.path("crimeIncidents[0].location.city")
				.entity(String.class)
				.isEqualTo("Sydney");

		readClient()
				.document("""
						query {
						  crimeIncidents(state: "NSW", status: UNDER_INVESTIGATION) {
						    title
						  }
						}
						""")
				.execute()
				.path("crimeIncidents")
				.entityList(Object.class)
				.hasSize(1)
				.path("crimeIncidents[0].title")
				.entity(String.class)
				.isEqualTo("Car stolen overnight");
	}

	@Test
	void crimeIncidentsQueryPaginatesResults() {
		readClient()
				.document("""
						query {
						  crimeIncidents(state: "NSW", limit: 1, offset: 0) {
						    title
						  }
						}
						""")
				.execute()
				.path("crimeIncidents")
				.entityList(Object.class)
				.hasSize(1);

		readClient()
				.document("""
						query {
						  crimeIncidents(state: "NSW", limit: 1, offset: 1) {
						    title
						  }
						}
						""")
				.execute()
				.path("crimeIncidents")
				.entityList(Object.class)
				.hasSize(1);
	}

	@Test
	void crimeIncidentsQueryRejectsInvalidPagination() {
		readClient()
				.document("""
						query {
						  crimeIncidents(limit: 0) {
						    title
						  }
						}
						""")
				.execute()
				.errors()
				.expect(error -> error.getMessage().contains("limit must be positive"))
				.verify();

		readClient()
				.document("""
						query {
						  crimeIncidents(offset: -1) {
						    title
						  }
						}
						""")
				.execute()
				.errors()
				.expect(error -> error.getMessage().contains("offset must not be negative"))
				.verify();
	}

	@Test
	void crimesNearLocationQueryFindsIncidentsWithinRadius() {
		readClient()
				.document("""
						query {
						  crimesNearLocation(latitude: -33.8688, longitude: 151.2093, radiusKm: 5.0) {
						    title
						    location { city }
						  }
						}
						""")
				.execute()
				.path("crimesNearLocation")
				.entityList(Object.class)
				.hasSize(1)
				.path("crimesNearLocation[0].location.city")
				.entity(String.class)
				.isEqualTo("Sydney");

		readClient()
				.document("""
						query {
						  crimesNearLocation(latitude: -33.8688, longitude: 151.2093, radiusKm: 30.0) {
						    location { city }
						  }
						}
						""")
				.execute()
				.path("crimesNearLocation")
				.entityList(Object.class)
				.hasSize(2);
	}

	@Test
	void crimesNearLocationQueryFiltersByState() {
		readClient()
				.document("""
						query {
						  crimesNearLocation(
						    latitude: -37.8136
						    longitude: 144.9631
						    radiusKm: 10.0
						    state: "VIC"
						  ) {
						    location { city state }
						  }
						}
						""")
				.execute()
				.path("crimesNearLocation")
				.entityList(Object.class)
				.hasSize(1)
				.path("crimesNearLocation[0].location.state")
				.entity(String.class)
				.isEqualTo("VIC");

		readClient()
				.document("""
						query {
						  crimesNearLocation(
						    latitude: -37.8136
						    longitude: 144.9631
						    radiusKm: 10.0
						    state: "NSW"
						  ) {
						    location { city }
						  }
						}
						""")
				.execute()
				.path("crimesNearLocation")
				.entityList(Object.class)
				.hasSize(0);
	}

	@Test
	void crimesNearLocationQueryRejectsInvalidInput() {
		readClient()
				.document("""
						query {
						  crimesNearLocation(latitude: 91.0, longitude: 151.2093, radiusKm: 5.0) {
						    title
						  }
						}
						""")
				.execute()
				.errors()
				.expect(error -> error.getMessage().contains("Invalid coordinates"))
				.verify();

		readClient()
				.document("""
						query {
						  crimesNearLocation(latitude: -33.8688, longitude: 151.2093, radiusKm: 0.0) {
						    title
						  }
						}
						""")
				.execute()
				.errors()
				.expect(error -> error.getMessage().contains("radiusKm must be positive"))
				.verify();
	}

	@Test
	void crimeIncidentQueryMapsExtendedSchemaFields() {
		readClient()
				.document("""
						query {
						  crimeIncidents(city: "Norwood") {
						    title
						    description
						    granularity
						    offenceCount
						    reportingPeriod
						    geocodeStatus
						    reportedAt
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
						  }
						}
						""")
				.execute()
				.path("crimeIncidents[0].granularity")
				.entity(String.class)
				.isEqualTo("SUBURB_AGGREGATE")
				.path("crimeIncidents[0].offenceCount")
				.entity(Integer.class)
				.isEqualTo(12)
				.path("crimeIncidents[0].reportingPeriod")
				.entity(String.class)
				.isEqualTo("2024-25")
				.path("crimeIncidents[0].geocodeStatus")
				.entity(String.class)
				.isEqualTo("RESOLVED")
				.path("crimeIncidents[0].suburbBoundary.suburbId")
				.entity(String.class)
				.isEqualTo("sa-norwood")
				.path("crimeIncidents[0].offenderContext.offenderCount")
				.entity(Integer.class)
				.isEqualTo(3)
				.path("crimeIncidents[0].offenderContext.principalOffence")
				.entity(String.class)
				.isEqualTo("Theft");
	}

	@Test
	void ingestionSourcesQueryReturnsConfiguredSources() {
		readClient()
				.document("""
						query {
						  ingestionSources
						}
						""")
				.execute()
				.path("ingestionSources")
				.entityList(String.class)
				.contains("sa-police-crime-statistics");
	}

	@Test
	void ingestCrimeDataMutationRejectsUnknownSource() {
		ingestClient()
				.document("""
						mutation {
						  ingestCrimeData(source: "does-not-exist") {
						    source
						  }
						}
						""")
				.execute()
				.errors()
				.expect(error -> error.getMessage().contains("Unknown ingestion source"))
				.verify();
	}

	private CrimeIncident incident(String externalId, String title, CrimeType type, CrimeStatus status, String city,
			String state, double lat, double lng) {
		GeoJsonPoint point = new GeoJsonPoint(new Point(lng, lat));
		Instant occurredAt = Instant.parse("2026-06-01T10:00:00Z");
		return CrimeIncident.builder()
				.source("test-source")
				.externalId(externalId)
				.title(title)
				.crimeType(type)
				.severity(CrimeSeverity.MEDIUM)
				.status(status)
				.location(Location.builder().city(city).state(state).country("Australia").coordinates(point).build())
				.geoCoordinates(point)
				.occurredAt(occurredAt)
				.reportedAt(occurredAt.plusSeconds(3600))
				.createdAt(Instant.now())
				.build();
	}

	private CrimeIncident richAggregateIncident() {
		GeoJsonPoint centroid = new GeoJsonPoint(new Point(138.6369, -34.9212));
		GeoJsonPolygon boundary = new GeoJsonPolygon(List.of(
				new Point(138.63, -34.92),
				new Point(138.64, -34.92),
				new Point(138.64, -34.93),
				new Point(138.63, -34.93),
				new Point(138.63, -34.92)));

		Instant occurredAt = Instant.parse("2025-06-30T14:00:00Z");
		return CrimeIncident.builder()
				.source("sa-police-crime-statistics")
				.externalId("sa-norwood-theft-2024-25")
				.title("Theft from retail premises")
				.description("Aggregate suburb offence count")
				.crimeType(CrimeType.THEFT)
				.severity(CrimeSeverity.LOW)
				.status(CrimeStatus.REPORTED)
				.granularity(RecordGranularity.SUBURB_AGGREGATE)
				.offenceCount(12)
				.reportingPeriod("2024-25")
				.geocodeStatus(GeocodeStatus.RESOLVED)
				.offenderContext(SaOffenderContext.builder()
						.offenderCount(3)
						.principalOffence("Theft")
						.correlationNote("Offender count is not incident-level.")
						.build())
				.location(Location.builder()
						.city("Norwood")
						.state("SA")
						.country("Australia")
						.suburbId("sa-norwood")
						.coordinates(centroid)
						.boundary(boundary)
						.build())
				.geoCoordinates(centroid)
				.occurredAt(occurredAt)
				.reportedAt(occurredAt)
				.createdAt(Instant.now())
				.build();
	}

}
