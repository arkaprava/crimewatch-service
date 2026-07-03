package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.ingestion.geocode.SuburbCacheLoader;
import com.example.springgraphqlmongo.repository.AustralianSuburbRepository;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.support.GraphQlSecurityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class WaCrimeIngestionIntegrationTest {

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
	private CrimeIncidentRepository crimeIncidentRepository;

	@Autowired
	private AustralianSuburbRepository suburbRepository;

	@Autowired
	private SuburbCacheLoader suburbCacheLoader;

	@BeforeEach
	void setUp() {
		crimeIncidentRepository.deleteAll();
		suburbRepository.deleteAll();
		suburbCacheLoader.loadFromCacheFile();
	}

	private HttpGraphQlTester readGraphQlTester() {
		return GraphQlSecurityTestSupport.withReadKey(graphQlTester);
	}

	private HttpGraphQlTester ingestGraphQlTester() {
		return GraphQlSecurityTestSupport.withIngestKey(graphQlTester);
	}

	@Test
	void ingestsWaAggregateRecords() {
		ingestGraphQlTester().document("""
				mutation {
				  ingestCrimeData(source: "wa-police-crime-statistics") {
				    source
				    fetched
				    inserted
				    failed
				    error
				  }
				}
				""")
				.execute()
				.path("ingestCrimeData[0].inserted")
				.entity(Integer.class)
				.satisfies(inserted -> org.assertj.core.api.Assertions.assertThat(inserted).isGreaterThan(0))
				.path("ingestCrimeData[0].failed")
				.entity(Integer.class)
				.isEqualTo(0);
	}

	@Test
	void crimesNearPerthReturnsWaRecords() {
		ingestGraphQlTester().document("""
				mutation { ingestCrimeData(source: "wa-police-crime-statistics") { inserted } }
				""")
				.execute();

		readGraphQlTester().document("""
				query {
				  crimesNearLocation(latitude: -31.9505, longitude: 115.8605, radiusKm: 15.0, state: "WA") {
				    title
				    granularity
				    offenceCount
				    reportingPeriod
				    location { city state }
				    source
				  }
				}
				""")
				.execute()
				.path("crimesNearLocation")
				.entityList(Object.class)
				.hasSizeGreaterThan(0)
				.path("crimesNearLocation[0].location.state")
				.entity(String.class)
				.isEqualTo("WA")
				.path("crimesNearLocation[0].source")
				.entity(String.class)
				.isEqualTo("wa-police-crime-statistics");
	}

	@Test
	void crimeIncidentsFiltersByWaSource() {
		ingestGraphQlTester().document("""
				mutation { ingestCrimeData(source: "wa-police-crime-statistics") { inserted } }
				""")
				.execute();

		readGraphQlTester().document("""
				query {
				  crimeIncidents(state: "WA", source: "wa-police-crime-statistics") {
				    title
				    granularity
				    offenceCount
				    source
				  }
				}
				""")
				.execute()
				.path("crimeIncidents")
				.entityList(Object.class)
				.hasSizeGreaterThan(0);
	}

}
