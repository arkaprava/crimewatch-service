package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.ingestion.storage.DatasetVersionRegistrar;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NswDatasetCacheServiceTest {

	@TempDir
	Path tempDir;

	private NswDatasetCacheService cacheService;

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		objectMapper.findAndRegisterModules();
		IngestionProperties properties = new IngestionProperties();
		properties.getNsw().setCacheDir(tempDir.toString());
		properties.getNsw().setCacheTtl(Duration.ofDays(90));
		properties.getNsw().setSuburbDataCsvFilename("suburb-data.csv");
		properties.getNsw().setCsvFallbackFilename("suburb-data-fixture.csv");
		cacheService = new NswDatasetCacheService(properties, RestClient.builder(), objectMapper,
				mock(DatasetVersionRegistrar.class));
	}

	@Test
	void archivesExtractedCsvFromZip() throws Exception {
		byte[] zipBytes = zipWithCsv("SuburbData.csv",
				"\"Suburb\",\"Offence category\",\"Subcategory\",\"Jan 2024\"\n\"Parramatta\",\"Assault\",\"Murder *\",\"1\"\n");
		Path datasetPath = tempDir.resolve("crime-statistics").resolve("suburb-data.csv");
		Files.createDirectories(datasetPath.getParent());
		DatasetTarArchive.writeCsvArchive(NswDatasetCacheService.readFirstCsvBytes(zipBytes),
				datasetPath.getFileName().toString(), datasetPath);
		try (var reader = DatasetTarArchive.openCsvReader(datasetPath)) {
			assertThat(reader.readLine()).contains("Suburb");
		}
	}

	@Test
	void detectsValidSuburbDataset() {
		byte[] bytes = "\"Suburb\",\"Offence category\",\"Subcategory\",\"Jan 2024\"\n".getBytes(StandardCharsets.UTF_8);
		assertThat(NswDatasetCacheService.looksLikeDataset(bytes)).isTrue();
	}

	@Test
	void cacheIsFreshWhenTtlAndChecksumMatch() throws Exception {
		Path dataFile = tempDir.resolve("crime-statistics").resolve("suburb-data.csv");
		Path manifestFile = tempDir.resolve("crime-statistics").resolve("manifest-suburb-data.csv.json");
		Files.createDirectories(dataFile.getParent());
		byte[] content = "\"Suburb\",\"Offence category\",\"Subcategory\",\"Jan 2024\"\n".getBytes();
		Files.write(dataFile, content);

		CacheManifest manifest = new CacheManifest();
		manifest.setSha256(SaDatasetCacheService.sha256(content));
		manifest.setLastFetched(Instant.now());
		objectMapper.writeValue(manifestFile.toFile(), manifest);

		assertThat(cacheService.isCacheFresh(dataFile, manifestFile)).isTrue();
	}

	private static byte[] zipWithCsv(String entryName, String csvContent) throws Exception {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
			zipOutputStream.putNextEntry(new ZipEntry(entryName));
			zipOutputStream.write(csvContent.getBytes(StandardCharsets.UTF_8));
			zipOutputStream.closeEntry();
		}
		return outputStream.toByteArray();
	}

}
