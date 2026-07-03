package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SaDatasetCacheServiceTest {

	@TempDir
	Path tempDir;

	private SaDatasetCacheService cacheService;

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		objectMapper.findAndRegisterModules();
		IngestionProperties properties = new IngestionProperties();
		properties.getSa().setCacheDir(tempDir.toString());
		properties.getSa().setCacheTtl(Duration.ofDays(7));
		cacheService = new SaDatasetCacheService(properties, RestClient.builder(), objectMapper);
	}

	@Test
	void cacheIsFreshWhenTtlMetadataAndChecksumMatch() throws Exception {
		Path dataFile = tempDir.resolve("crime-statistics-2024-25.csv");
		Path manifestFile = tempDir.resolve("manifest-crime-statistics-2024-25.csv.json");
		byte[] content = "Suburb,Count\nAdelaide,1\n".getBytes();
		Files.write(dataFile, content);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceName("Crime Statistics 2024-25");
		manifest.setResourceHash("abc123");
		manifest.setResourceLastModified(Instant.parse("2026-05-28T00:00:00Z"));
		manifest.setDownloadUrl("https://example.com/data.csv");
		manifest.setSha256(SaDatasetCacheService.sha256(content));
		manifest.setLastFetched(Instant.now());
		objectMapper.writeValue(manifestFile.toFile(), manifest);

		ObjectNode remote = objectMapper.createObjectNode();
		remote.put("hash", "abc123");
		remote.put("last_modified", "2026-05-28T00:00:00Z");
		remote.put("url", "https://example.com/data.csv");

		assertThat(cacheService.isCacheFresh(dataFile, manifestFile, remote)).isTrue();
	}

	@Test
	void cacheIsStaleWhenResourceHashChanges() throws Exception {
		Path dataFile = tempDir.resolve("crime-statistics-2024-25.csv");
		Path manifestFile = tempDir.resolve("manifest-crime-statistics-2024-25.csv.json");
		byte[] content = "Suburb,Count\nAdelaide,1\n".getBytes();
		Files.write(dataFile, content);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceHash("old-hash");
		manifest.setSha256(SaDatasetCacheService.sha256(content));
		manifest.setLastFetched(Instant.now());
		objectMapper.writeValue(manifestFile.toFile(), manifest);

		ObjectNode remote = objectMapper.createObjectNode();
		remote.put("hash", "new-hash");

		assertThat(cacheService.isCacheFresh(dataFile, manifestFile, remote)).isFalse();
	}

	@Test
	void cacheIsStaleWhenTtlExpired() throws Exception {
		Path dataFile = tempDir.resolve("crime-statistics-2024-25.csv");
		Path manifestFile = tempDir.resolve("manifest-crime-statistics-2024-25.csv.json");
		byte[] content = "Suburb,Count\nAdelaide,1\n".getBytes();
		Files.write(dataFile, content);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceHash("abc123");
		manifest.setSha256(SaDatasetCacheService.sha256(content));
		manifest.setLastFetched(Instant.now().minus(Duration.ofDays(8)));
		objectMapper.writeValue(manifestFile.toFile(), manifest);

		ObjectNode remote = objectMapper.createObjectNode();
		remote.put("hash", "abc123");

		assertThat(cacheService.isCacheFresh(dataFile, manifestFile, remote)).isFalse();
	}

	@Test
	void cacheIsStaleWhenLocalFileChecksumDiffers() throws Exception {
		Path dataFile = tempDir.resolve("crime-statistics-2024-25.csv");
		Path manifestFile = tempDir.resolve("manifest-crime-statistics-2024-25.csv.json");
		Files.write(dataFile, "updated content".getBytes());

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceHash("abc123");
		manifest.setSha256(SaDatasetCacheService.sha256("original content".getBytes()));
		manifest.setLastFetched(Instant.now());
		objectMapper.writeValue(manifestFile.toFile(), manifest);

		ObjectNode remote = objectMapper.createObjectNode();
		remote.put("hash", "abc123");

		assertThat(cacheService.isCacheFresh(dataFile, manifestFile, remote)).isFalse();
	}

}
