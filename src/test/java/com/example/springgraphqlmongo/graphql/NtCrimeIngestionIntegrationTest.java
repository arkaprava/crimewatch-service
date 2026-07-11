package com.example.springgraphqlmongo.graphql;

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
class NtCrimeIngestionIntegrationTest {

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

	@BeforeEach
	void setUp() {
		crimeIncidentRepository.deleteAll();
	}

	private HttpGraphQlTester readGraphQlTester() {
		return GraphQlSecurityTestSupport.withReadKey(graphQlTester);
	}

	private HttpGraphQlTester ingestGraphQlTester() {
		return GraphQlSecurityTestSupport.withIngestKey(graphQlTester);
	}

	@Test
	void ingestsNtSerproAggregateRecords() {
		ingestGraphQlTester().document("""
				mutation {
				  ingestCrimeData(source: "nt-police-crime-statistics") {
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
	void ingestsNtPromisHistoricalRecords() {
		ingestGraphQlTester().document("""
				mutation {
				  ingestCrimeData(source: "nt-police-crime-statistics-historical") {
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
	void crimesNearDarwinReturnsNtRecords() {
		ingestGraphQlTester().document("""
				mutation { ingestCrimeData(source: "nt-police-crime-statistics") { inserted } }
				""")
				.execute();

		readGraphQlTester().document("""
				query {
				  crimesNearLocation(latitude: -12.463, longitude: 130.841, radiusKm: 50.0, state: "NT") {
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
				.isEqualTo("NT")
				.path("crimesNearLocation[0].source")
				.entity(String.class)
				.isEqualTo("nt-police-crime-statistics");
	}

}
