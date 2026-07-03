package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.support.GraphQlSecurityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureHttpGraphQlTester
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class GraphQlSecurityIntegrationTest {

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
	private WebTestClient webTestClient;

	@Test
	void graphqlWithoutApiKeyReturnsUnauthorized() {
		webTestClient.post()
				.uri("/graphql")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"query\":\"{ ingestionSources }\"}")
				.exchange()
				.expectStatus()
				.isUnauthorized();
	}

	@Test
	void readApiKeyCanExecuteQueries() {
		GraphQlSecurityTestSupport.withReadKey(graphQlTester)
				.document("""
						query {
						  ingestionSources
						}
						""")
				.execute()
				.errors()
				.verify();
	}

	@Test
	void readApiKeyCannotExecuteIngestMutation() {
		GraphQlSecurityTestSupport.withReadKey(graphQlTester)
				.document("""
						mutation {
						  ingestCrimeData(source: "sa-police-crime-statistics") {
						    source
						  }
						}
						""")
				.execute()
				.errors()
				.expect(error -> error.getMessage().contains("Access is denied"))
				.verify();
	}

	@Test
	void ingestApiKeyCanExecuteIngestMutation() {
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
