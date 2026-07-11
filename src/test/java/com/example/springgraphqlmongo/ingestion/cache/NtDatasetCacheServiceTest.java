package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.ingestion.storage.DatasetVersionRegistrar;
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
import static org.mockito.Mockito.mock;

class NtDatasetCacheServiceTest {

	@TempDir
	Path tempDir;

	private NtDatasetCacheService cacheService;

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		objectMapper.findAndRegisterModules();
		IngestionProperties properties = new IngestionProperties();
		properties.getNt().setCacheDir(tempDir.toString());
		properties.getNt().setBaseUrl("http://127.0.0.1:1");
		properties.getNt().setCacheTtl(Duration.ofDays(7));
		properties.getNt().getSerpro().setLogicalFilename("nt-crime-statistics-serpro.csv");
		properties.getNt().getSerpro().setCsvFallbackFilename("nt-crime-statistics-serpro-fixture.csv");
		properties.getNt().getPromis().setLogicalFilename("nt-crime-statistics-promis.csv");
		properties.getNt().getPromis().setCsvFallbackFilename("nt-crime-statistics-promis-fixture.csv");
		cacheService = new NtDatasetCacheService(properties, RestClient.builder(), objectMapper,
				mock(DatasetVersionRegistrar.class));
	}

	@Test
	void usesSerproFallbackWhenCacheMissing() throws Exception {
		Path fallbackSource = Path.of("data/nt/crime-statistics/nt-crime-statistics-serpro-fixture.csv");
		Path cacheDir = tempDir.resolve("crime-statistics");
		Files.createDirectories(cacheDir);
		Files.copy(fallbackSource, cacheDir.resolve("nt-crime-statistics-serpro-fixture.csv"));

		Path resolved = cacheService.resolveTimeseriesFile(NtDatasetCacheService.NtSeries.SERPRO);

		assertThat(resolved.getFileName().toString()).isEqualTo("nt-crime-statistics-serpro-fixture.csv");
	}

	@Test
	void usesPromisFallbackWhenCacheMissing() throws Exception {
		Path fallbackSource = Path.of("data/nt/crime-statistics/nt-crime-statistics-promis-fixture.csv");
		Path cacheDir = tempDir.resolve("crime-statistics");
		Files.createDirectories(cacheDir);
		Files.copy(fallbackSource, cacheDir.resolve("nt-crime-statistics-promis-fixture.csv"));

		Path resolved = cacheService.resolveTimeseriesFile(NtDatasetCacheService.NtSeries.PROMIS);

		assertThat(resolved.getFileName().toString()).isEqualTo("nt-crime-statistics-promis-fixture.csv");
	}

	@Test
	void cacheIsFreshWhenTtlAndChecksumMatch() throws Exception {
		Path dataFile = tempDir.resolve("crime-statistics").resolve("nt-crime-statistics-serpro.csv");
		Path manifestFile = tempDir.resolve("crime-statistics").resolve("manifest-nt-crime-statistics-serpro.csv.json");
		Files.createDirectories(dataFile.getParent());
		byte[] content = Files.readAllBytes(Path.of("data/nt/crime-statistics/nt-crime-statistics-serpro-fixture.csv"));
		Files.write(dataFile, content);

		CacheManifest manifest = new CacheManifest();
		manifest.setSha256(DatasetTarArchive.sha256(dataFile));
		manifest.setLastFetched(Instant.now());
		objectMapper.writeValue(manifestFile.toFile(), manifest);

		assertThat(cacheService.isCacheFresh(dataFile, manifestFile)).isTrue();
	}

}
