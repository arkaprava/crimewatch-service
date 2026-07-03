package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.support.GraphQlSecurityTestSupport;
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
class IngestionGraphQlIntegrationTest {

	private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7");

	@Container
	static MongoDBContainer mongoDBContainer = new MongoDBContainer(MONGO_IMAGE);

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
	}

	@Autowired
	private HttpGraphQlTester graphQlTester;

	@Test
	void ingestionSourcesReturnsConfiguredSources() {
		GraphQlSecurityTestSupport.withReadKey(graphQlTester)
				.document("""
						query {
						  ingestionSources
						}
						""")
				.execute()
				.path("ingestionSources")
				.entityList(String.class)
				.contains("sa-police-crime-statistics", "wa-police-crime-statistics");
	}

	@Test
	void ingestingUnknownSourceReturnsGraphQlError() {
		GraphQlSecurityTestSupport.withIngestKey(graphQlTester)
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

}
