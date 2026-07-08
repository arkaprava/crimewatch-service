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

class TasDatasetCacheServiceTest {

	@TempDir
	Path tempDir;

	private TasDatasetCacheService cacheService;

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		objectMapper.findAndRegisterModules();
		IngestionProperties properties = new IngestionProperties();
		properties.getTas().setCacheDir(tempDir.toString());
		properties.getTas().setCacheTtl(Duration.ofDays(30));
		cacheService = new TasDatasetCacheService(properties, RestClient.builder(), objectMapper);
	}

	@Test
	void detectsValidPdf() {
		byte[] bytes = "%PDF-1.7 sample".getBytes();
		assertThat(TasDatasetCacheService.looksLikePdf(bytes)).isTrue();
	}

	@Test
	void rejectsHtmlDownload() {
		byte[] bytes = "<!DOCTYPE html><html><body>error</body></html>".getBytes();
		assertThat(TasDatasetCacheService.looksLikePdf(bytes)).isFalse();
	}

	@Test
	void normalisesRelativePdfUrls() {
		assertThat(TasDatasetCacheService.normaliseUrl("/uploads/sample.pdf"))
				.isEqualTo("https://www.police.tas.gov.au/uploads/sample.pdf");
	}

	@Test
	void cacheIsFreshWhenTtlAndChecksumMatch() throws Exception {
		Path dataFile = tempDir.resolve("crime-statistics").resolve("supplement.pdf");
		Path manifestFile = tempDir.resolve("crime-statistics").resolve("manifest-supplement.pdf.json");
		Files.createDirectories(dataFile.getParent());
		byte[] content = "%PDF-1.7 test".getBytes();
		Files.write(dataFile, content);

		CacheManifest manifest = new CacheManifest();
		manifest.setSha256(SaDatasetCacheService.sha256(content));
		manifest.setLastFetched(Instant.now());
		objectMapper.writeValue(manifestFile.toFile(), manifest);

		assertThat(cacheService.isCacheFresh(dataFile, manifestFile)).isTrue();
	}

}
