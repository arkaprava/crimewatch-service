package com.example.springgraphqlmongo.ingestion.storage;

import com.example.springgraphqlmongo.domain.DatasetVersion;
import com.example.springgraphqlmongo.domain.DatasetVersionStatus;
import com.example.springgraphqlmongo.ingestion.cache.CacheManifest;
import com.example.springgraphqlmongo.ingestion.cache.DatasetTarArchive;
import com.example.springgraphqlmongo.repository.DatasetVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
@Testcontainers(disabledWithoutDocker = true)
@Import(GridFsDatasetStorageService.class)
class GridFsDatasetStorageServiceTest {

	@Container
	static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

	@DynamicPropertySource
	static void mongoProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
	}

	@Autowired
	private GridFsDatasetStorageService storageService;

	@Autowired
	private DatasetVersionRepository datasetVersionRepository;

	@BeforeEach
	void cleanVersions() {
		datasetVersionRepository.deleteAll();
	}

	@Test
	void storesNewVersionInGridFsAndReusesUnchangedContent() throws Exception {
		Path tempFile = Files.createTempFile("sample", ".csv");
		DatasetTarArchive.writeCsvArchive("suburb,count\nAdelaide,1\n".getBytes(), "sample.csv", tempFile);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceName("sample.csv");
		manifest.setLastFetched(Instant.now());

		DatasetVersion first = storageService.registerFromPath("sa:crime-statistics", "sample.csv", tempFile,
				manifest, "application/gzip");
		DatasetVersion second = storageService.registerFromPath("sa:crime-statistics", "sample.csv", tempFile,
				manifest, "application/gzip");

		assertThat(first.getId()).isEqualTo(second.getId());
		assertThat(first.getVersion()).isEqualTo(1);
		assertThat(first.getGridFsFileId()).isNotNull();
		assertThat(datasetVersionRepository.count()).isEqualTo(1);
	}

	@Test
	void appendOnlyVersionIncrementsWhenContentChanges() throws Exception {
		Path tempFile = Files.createTempFile("sample", ".csv");
		DatasetTarArchive.writeCsvArchive("suburb,count\nAdelaide,1\n".getBytes(), "sample.csv", tempFile);
		CacheManifest manifest = new CacheManifest();
		manifest.setResourceName("sample.csv");
		manifest.setLastFetched(Instant.now());

		DatasetVersion first = storageService.registerFromPath("sa:crime-statistics", "sample.csv", tempFile,
				manifest, "application/gzip");

		DatasetTarArchive.writeCsvArchive("suburb,count\nAdelaide,2\n".getBytes(), "sample.csv", tempFile);
		DatasetVersion second = storageService.registerFromPath("sa:crime-statistics", "sample.csv", tempFile,
				manifest, "application/gzip");

		assertThat(first.getVersion()).isEqualTo(1);
		assertThat(second.getVersion()).isEqualTo(2);
		assertThat(second.getSha256()).isNotEqualTo(first.getSha256());

		DatasetVersion superseded = datasetVersionRepository.findById(first.getId()).orElseThrow();
		assertThat(superseded.getStatus()).isEqualTo(DatasetVersionStatus.SUPERSEDED);
		assertThat(second.getStatus()).isEqualTo(DatasetVersionStatus.STORED);
	}

}
