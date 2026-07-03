package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.cache.CrimeReadCacheEvictor;
import com.example.springgraphqlmongo.config.CrimeCacheNames;
import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeSeverity;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.domain.Location;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.support.GraphQlSecurityTestSupport;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class CrimeIncidentCacheIntegrationTest {

	private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7");

	private static final String NEAR_QUERY = """
			query {
			  crimesNearLocation(latitude: -33.8688, longitude: 151.2093, radiusKm: 5.0) {
			    title
			  }
			}
			""";

	@Container
	static MongoDBContainer mongoDBContainer = new MongoDBContainer(MONGO_IMAGE);

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
	}

	@Autowired
	private HttpGraphQlTester graphQlTester;

	private HttpGraphQlTester readGraphQlTester() {
		return GraphQlSecurityTestSupport.withReadKey(graphQlTester);
	}

	@Autowired
	private CrimeIncidentRepository repository;

	@Autowired
	private CacheManager cacheManager;

	@Autowired
	private CrimeReadCacheEvictor crimeReadCacheEvictor;

	@BeforeEach
	void seed() {
		repository.deleteAll();
		crimeReadCacheEvictor.evictAll();
		repository.save(incident("ext-1", "Phone snatched at Town Hall", CrimeType.THEFT, "Sydney", "NSW",
				-33.8732, 151.2069));
	}

	@Test
	void cachesCrimesNearLocationResults() {
		readGraphQlTester().document(NEAR_QUERY).execute().path("crimesNearLocation").entityList(Object.class).hasSize(1);

		assertThat(nativeCacheSize(CrimeCacheNames.CRIMES_NEAR)).isEqualTo(1);

		repository.save(incident("ext-2", "Wallet stolen at Circular Quay", CrimeType.THEFT, "Sydney", "NSW",
				-33.8610, 151.2108));

		readGraphQlTester().document(NEAR_QUERY).execute().path("crimesNearLocation").entityList(Object.class).hasSize(1);

		crimeReadCacheEvictor.evictAll();

		readGraphQlTester().document(NEAR_QUERY).execute().path("crimesNearLocation").entityList(Object.class).hasSize(2);
	}

	@Test
	void cachesFilteredAndUnfilteredPaginatedSearch() {
		readGraphQlTester()
				.document("""
						query {
						  crimeIncidents(state: "NSW") {
						    title
						  }
						}
						""")
				.execute()
				.path("crimeIncidents")
				.entityList(Object.class)
				.hasSize(1);

		assertThat(nativeCacheSize(CrimeCacheNames.CRIME_SEARCH)).isEqualTo(1);

		readGraphQlTester()
				.document("""
						query {
						  crimeIncidents {
						    title
						  }
						}
						""")
				.execute()
				.path("crimeIncidents")
				.entityList(Object.class)
				.hasSize(1);

		assertThat(nativeCacheSize(CrimeCacheNames.CRIME_SEARCH)).isEqualTo(2);
	}

	private long nativeCacheSize(String cacheName) {
		org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
		assertThat(cache).isNotNull();
		Object nativeCache = cache.getNativeCache();
		assertThat(nativeCache).isInstanceOf(Cache.class);
		return ((Cache<?, ?>) nativeCache).estimatedSize();
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

}
