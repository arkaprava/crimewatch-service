package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.repository.AustralianSuburbRepository;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("startup-ingest-test")
class CrimeIngestionStartupLoaderIntegrationTest {

	private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7");

	@Container
	static MongoDBContainer mongoDBContainer = new MongoDBContainer(MONGO_IMAGE);

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
	}

	@Autowired
	private CrimeIncidentRepository crimeIncidentRepository;

	@Autowired
	private AustralianSuburbRepository suburbRepository;

	@Test
	void loadsSaWaAndNswDataOnStartupWhenCollectionsAreEmpty() throws InterruptedException {
		assertThat(suburbRepository.count()).isGreaterThan(0);
		assertEventuallyAllSourcesIngested();
		assertThat(crimeIncidentRepository.countBySource("sa-police-crime-statistics")).isGreaterThan(0);
		assertThat(crimeIncidentRepository.countBySource("wa-police-crime-statistics")).isGreaterThan(0);
		assertThat(crimeIncidentRepository.countBySource("nsw-bocsar-statistics")).isGreaterThan(0);
	}

	private void assertEventuallyAllSourcesIngested() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 600_000;
		while (System.currentTimeMillis() < deadline) {
			if (crimeIncidentRepository.countBySource("sa-police-crime-statistics") > 0
					&& crimeIncidentRepository.countBySource("wa-police-crime-statistics") > 0
					&& crimeIncidentRepository.countBySource("nsw-bocsar-statistics") > 0) {
				return;
			}
			Thread.sleep(1_000);
		}
		assertThat(crimeIncidentRepository.countBySource("sa-police-crime-statistics")).isGreaterThan(0);
		assertThat(crimeIncidentRepository.countBySource("wa-police-crime-statistics")).isGreaterThan(0);
		assertThat(crimeIncidentRepository.countBySource("nsw-bocsar-statistics")).isGreaterThan(0);
	}

}
