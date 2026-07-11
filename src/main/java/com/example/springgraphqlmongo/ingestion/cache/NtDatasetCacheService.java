package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.example.springgraphqlmongo.ingestion.storage.DatasetVersionRegistrar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NtDatasetCacheService {

	private static final String PACKAGE_SEARCH = "/api/3/action/package_search";

	private static final String PACKAGE_SHOW = "/api/3/action/package_show";

	public enum NtSeries {
		SERPRO, PROMIS
	}

	private final IngestionProperties properties;

	private final RestClient.Builder restClientBuilder;

	private final ObjectMapper objectMapper;

	private final DatasetVersionRegistrar datasetVersionRegistrar;

	public Path resolveTimeseriesFile(NtSeries series, boolean refresh) {
		IngestionProperties.NtSettings nt = properties.getNt();
		String logicalFilename = series == NtSeries.SERPRO ? nt.getSerpro().getLogicalFilename()
				: nt.getPromis().getLogicalFilename();
		String fallbackFilename = series == NtSeries.SERPRO ? nt.getSerpro().getCsvFallbackFilename()
				: nt.getPromis().getCsvFallbackFilename();
		Path cacheDir = Path.of(nt.getCacheDir(), "crime-statistics");
		Path dataFile = cacheDir.resolve(logicalFilename);
		Path manifestFile = cacheDir.resolve("manifest-" + logicalFilename + ".json");

		if (!refresh && DatasetTarArchive.exists(dataFile) && isCacheFresh(dataFile, manifestFile)) {
			log.debug("Using cached NT {} dataset at {}", series, dataFile);
			return dataFile;
		}

		try {
			if (series == NtSeries.SERPRO) {
				downloadLatestSerpro(dataFile, manifestFile, logicalFilename);
			}
			else {
				downloadPinnedPromis(dataFile, manifestFile, logicalFilename);
			}
			if (DatasetFileValidator.isReadableDataset(dataFile)) {
				return dataFile;
			}
		}
		catch (Exception ex) {
			log.warn("NT {} download failed: {}", series, ex.getMessage());
		}

		Path fallback = cacheDir.resolve(fallbackFilename);
		if (Files.exists(fallback) && DatasetFileValidator.isReadableDataset(fallback)) {
			log.info("Using NT {} CSV fallback at {}", series, fallback);
			return fallback;
		}
		if (DatasetTarArchive.exists(dataFile) && DatasetFileValidator.isReadableDataset(dataFile)) {
			log.warn("NT {} download failed; using existing cache at {}", series, dataFile);
			return dataFile;
		}
		throw new IngestionException("NT " + series + " dataset cache miss and download failed for " + logicalFilename);
	}

	public Path resolveTimeseriesFile(NtSeries series) {
		return resolveTimeseriesFile(series, false);
	}

	boolean isCacheFresh(Path dataFile, Path manifestFile) {
		if (!Files.exists(manifestFile)) {
			return false;
		}
		try {
			CacheManifest manifest = objectMapper.readValue(Files.readString(manifestFile), CacheManifest.class);
			if (manifest.getLastFetched() == null) {
				return false;
			}
			Instant expiresAt = manifest.getLastFetched().plus(properties.getNt().getCacheTtl());
			if (Instant.now().isAfter(expiresAt)) {
				return false;
			}
			if (manifest.getSha256() != null) {
				return manifest.getSha256().equalsIgnoreCase(DatasetTarArchive.sha256(dataFile));
			}
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

	private void downloadLatestSerpro(Path dataFile, Path manifestFile, String logicalFilename) throws IOException {
		JsonNode resource = discoverLatestSerproResource()
				.orElseThrow(() -> new IngestionException("No current NT crime statistics package found on CKAN"));
		downloadResource(resource, dataFile, manifestFile, logicalFilename);
	}

	private void downloadPinnedPromis(Path dataFile, Path manifestFile, String logicalFilename) throws IOException {
		JsonNode packageNode = fetchPackage(properties.getNt().getPromis().getPackageName());
		JsonNode resource = findCsvResource(packageNode)
				.orElseThrow(() -> new IngestionException("No CSV resource in NT PROMIS package "
						+ properties.getNt().getPromis().getPackageName()));
		downloadResource(resource, dataFile, manifestFile, logicalFilename);
	}

	private Optional<JsonNode> discoverLatestSerproResource() {
		RestClient client = restClient(properties.getNt().getBaseUrl());
		JsonNode response = client.get()
				.uri(uriBuilder -> uriBuilder.path(PACKAGE_SEARCH)
						.queryParam("q", properties.getNt().getSerpro().getPackageSearchQuery())
						.queryParam("sort", "metadata_modified desc")
						.queryParam("rows", 10)
						.build())
				.header(HttpHeaders.USER_AGENT, properties.getNt().getDownloadUserAgent())
				.retrieve()
				.body(JsonNode.class);
		if (response == null || !response.path("success").asBoolean(false)) {
			return Optional.empty();
		}
		for (JsonNode result : response.path("result").path("results")) {
			Optional<JsonNode> csv = findCsvResource(result);
			if (csv.isPresent()) {
				return csv;
			}
		}
		return Optional.empty();
	}

	private JsonNode fetchPackage(String packageIdOrName) {
		RestClient client = restClient(properties.getNt().getBaseUrl());
		JsonNode response = client.get()
				.uri(uriBuilder -> uriBuilder.path(PACKAGE_SHOW).queryParam("id", packageIdOrName).build())
				.header(HttpHeaders.USER_AGENT, properties.getNt().getDownloadUserAgent())
				.retrieve()
				.body(JsonNode.class);
		if (response == null || !response.path("success").asBoolean(false)) {
			throw new IngestionException("NT package_show failed for " + packageIdOrName);
		}
		return response.path("result");
	}

	private Optional<JsonNode> findCsvResource(JsonNode packageNode) {
		for (JsonNode resource : packageNode.path("resources")) {
			String format = resource.path("format").asText("").toUpperCase(Locale.ROOT);
			String name = resource.path("name").asText("").toLowerCase(Locale.ROOT);
			if ("CSV".equals(format) || name.endsWith(".csv") || name.contains("crime_statistics")) {
				return Optional.of(resource);
			}
		}
		return Optional.empty();
	}

	private void downloadResource(JsonNode resource, Path dataFile, Path manifestFile, String logicalFilename)
			throws IOException {
		String downloadUrl = resource.path("url").asText(null);
		if (downloadUrl == null || downloadUrl.isBlank()) {
			throw new IngestionException("No download URL for NT resource " + resource.path("name").asText());
		}
		RestClient client = restClientBuilder.build();
		byte[] bytes = client.get()
				.uri(downloadUrl)
				.header(HttpHeaders.USER_AGENT, properties.getNt().getDownloadUserAgent())
				.retrieve()
				.body(byte[].class);
		if (bytes == null || bytes.length == 0) {
			throw new IngestionException("Empty NT download from " + downloadUrl);
		}
		if (!DatasetFileValidator.looksLikeDataset(bytes)) {
			throw new IngestionException("NT download from " + downloadUrl + " returned non-dataset content");
		}
		Files.createDirectories(dataFile.getParent());
		DatasetTarArchive.writeCsvArchive(bytes, logicalFilename, dataFile);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceName(logicalFilename);
		manifest.setResourceId(resource.path("id").asText(null));
		manifest.setDownloadUrl(downloadUrl);
		manifest.setResourceHash(resource.path("hash").asText(null));
		manifest.setSha256(DatasetTarArchive.sha256(dataFile));
		manifest.setLastFetched(Instant.now());
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
		Path storedAt = DatasetTarArchive.resolveArchive(dataFile).orElse(dataFile);
		datasetVersionRegistrar.register("nt:crime-statistics", logicalFilename, dataFile, manifest);
		log.info("Cached NT resource {} ({} bytes) at {}", logicalFilename, bytes.length, storedAt);
	}

	private RestClient restClient(String baseUrl) {
		return restClientBuilder.baseUrl(baseUrl).build();
	}

}
