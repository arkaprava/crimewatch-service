package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeSeverity;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.domain.Location;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.support.GraphQlSecurityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class CrimeIncidentGraphQlIntegrationTest {

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

	@BeforeEach
	void seed() {
		repository.deleteAll();
		// Sydney CBD
		repository.save(incident("ext-1", "Phone snatched at Town Hall", CrimeType.THEFT, "Sydney", "NSW",
				-33.8732, 151.2069));
		// Parramatta, ~20 km from Sydney CBD
		repository.save(incident("ext-2", "Car stolen overnight", CrimeType.THEFT, "Parramatta", "NSW",
				-33.8151, 151.0011));
		// Melbourne CBD, no coordinates
		CrimeIncident melbourne = incident("ext-3", "Shopfront vandalised", CrimeType.VANDALISM, "Melbourne",
				"VIC", 0, 0);
		melbourne.setGeoCoordinates(null);
		melbourne.getLocation().setCoordinates(null);
		repository.save(melbourne);
	}

	private CrimeIncident incident(String externalId, String title, CrimeType type, String city, String state,
			double lat, double lng) {
		GeoJsonPoint point = new GeoJsonPoint(new Point(lng, lat));
		return CrimeIncident.builder()
				.source("test-source")
				.externalId(externalId)
				.title(title)
				.crimeType(type)
				.severity(CrimeSeverity.MEDIUM)
				.status(CrimeStatus.REPORTED)
				.location(Location.builder().city(city).state(state).country("Australia").coordinates(point).build())
				.geoCoordinates(point)
				.occurredAt(Instant.parse("2026-06-01T10:00:00Z"))
				.createdAt(Instant.now())
				.build();
	}

	private HttpGraphQlTester graphQlTester() {
		return GraphQlSecurityTestSupport.withReadKey(graphQlTester);
	}

	@Test
	void filtersIncidentsByCity() {
		graphQlTester()
				.document("""
						query {
						  crimeIncidents(city: "sydney") {
						    title
						    location { city state coordinates { latitude longitude } }
						    occurredAt
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
	}

	@Test
	void filtersIncidentsByStateAndType() {
		graphQlTester()
				.document("""
						query {
						  crimeIncidents(state: "NSW", crimeType: THEFT) {
						    title
						  }
						}
						""")
				.execute()
				.path("crimeIncidents")
				.entityList(Object.class)
				.hasSize(2);
	}

	@Test
	void findsCrimesNearSydneyCbdOnly() {
		graphQlTester()
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
	}

	@Test
	void widerRadiusIncludesParramatta() {
		graphQlTester()
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
	void paginatesIncidentsWithLimitAndOffset() {
		graphQlTester()
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

		graphQlTester()
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
	void rejectsInvalidPagination() {
		graphQlTester()
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
	}

	@Test
	void unknownIncidentIdReturnsNotFoundError() {
		graphQlTester()
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

}
