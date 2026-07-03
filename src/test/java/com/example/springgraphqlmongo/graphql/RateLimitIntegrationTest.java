package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.support.GraphQlSecurityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
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
class RateLimitIntegrationTest {

	private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7");

	@Container
	static MongoDBContainer mongoDBContainer = new MongoDBContainer(MONGO_IMAGE);

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
		registry.add("security.rate-limit.read.requests-per-minute", () -> 2);
	}

	@Autowired
	private WebTestClient webTestClient;

	@Test
	void returnsTooManyRequestsWhenReadLimitExceeded() {
		for (int i = 0; i < 2; i++) {
			webTestClient.post()
					.uri("/graphql")
					.contentType(MediaType.APPLICATION_JSON)
					.header(GraphQlSecurityTestSupport.API_KEY_HEADER, GraphQlSecurityTestSupport.READ_API_KEY)
					.bodyValue("{\"query\":\"{ ingestionSources }\"}")
					.exchange()
					.expectStatus()
					.isOk();
		}

		webTestClient.post()
				.uri("/graphql")
				.contentType(MediaType.APPLICATION_JSON)
				.header(GraphQlSecurityTestSupport.API_KEY_HEADER, GraphQlSecurityTestSupport.READ_API_KEY)
				.bodyValue("{\"query\":\"{ ingestionSources }\"}")
				.exchange()
				.expectStatus()
				.isEqualTo(429)
				.expectHeader().exists("Retry-After")
				.expectHeader().valueEquals("X-RateLimit-Limit", "2");
	}

}
