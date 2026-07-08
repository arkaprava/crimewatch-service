package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WaDatasetCacheServiceTest {

	@TempDir
	Path tempDir;

	private WaDatasetCacheService cacheService;

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		objectMapper.findAndRegisterModules();
		IngestionProperties properties = new IngestionProperties();
		properties.getWa().setCacheDir(tempDir.toString());
		properties.getWa().setCacheTtl(Duration.ofDays(7));
		properties.getWa().setCsvFallbackFilename("crime-timeseries.csv");
		cacheService = new WaDatasetCacheService(properties, RestClient.builder(), objectMapper);
	}

	@Test
	void detectsValidCsvDataset() {
		byte[] bytes = "Geography Level,Location,Count\nDistrict,Fremantle,1\n".getBytes();
		assertThat(DatasetFileValidator.looksLikeDataset(bytes)).isTrue();
	}

	@Test
	void rejectsHtmlDownload() {
		byte[] bytes = "<!DOCTYPE html><html><body>error</body></html>".getBytes();
		assertThat(DatasetFileValidator.looksLikeDataset(bytes)).isFalse();
	}

	@Test
	void cacheIsFreshWhenTtlAndChecksumMatch() throws Exception {
		Path dataFile = tempDir.resolve("crime-statistics").resolve("crime-timeseries.csv");
		Path manifestFile = tempDir.resolve("crime-statistics").resolve("manifest-crime-timeseries.csv.json");
		Files.createDirectories(dataFile.getParent());
		byte[] content = "Geography Level,Location,Count\nDistrict,Fremantle,1\n".getBytes();
		Files.write(dataFile, content);

		CacheManifest manifest = new CacheManifest();
		manifest.setSha256(SaDatasetCacheService.sha256(content));
		manifest.setLastFetched(Instant.now());
		objectMapper.writeValue(manifestFile.toFile(), manifest);

		assertThat(cacheService.isCacheFresh(dataFile, manifestFile)).isTrue();
	}

}
