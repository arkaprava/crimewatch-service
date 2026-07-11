package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.example.springgraphqlmongo.ingestion.storage.DatasetVersionRegistrar;
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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaDatasetCacheService {

	private final IngestionProperties properties;

	private final RestClient.Builder restClientBuilder;

	private final ObjectMapper objectMapper;

	private final DatasetVersionRegistrar datasetVersionRegistrar;

	public Path resolveCrimeTimeseriesFile(boolean refresh) {
		IngestionProperties.WaSettings wa = properties.getWa();
		Path cacheDir = Path.of(wa.getCacheDir(), "crime-statistics");
		String filename = wa.getTimeseriesFilename();
		Path dataFile = cacheDir.resolve(filename);
		Path manifestFile = cacheDir.resolve("manifest-" + filename + ".json");

		if (!refresh && Files.exists(dataFile) && isCacheFresh(dataFile, manifestFile)) {
			log.debug("Using cached WA crime timeseries at {}", dataFile);
			return dataFile;
		}

		List<String> downloadUrls = wa.getDownloadUrls();
		for (String downloadUrl : downloadUrls) {
			try {
				downloadFile(downloadUrl, dataFile, manifestFile, filename);
				if (isReadableDataset(dataFile)) {
					return dataFile;
				}
				log.warn("Download from {} did not produce a readable dataset; trying next URL", downloadUrl);
			}
			catch (Exception ex) {
				log.warn("WA download failed from {}: {}", downloadUrl, ex.getMessage());
			}
		}

		Path csvFallback = cacheDir.resolve(wa.getCsvFallbackFilename());
		if (Files.exists(csvFallback)) {
			log.info("Using WA CSV fallback at {}", csvFallback);
			return csvFallback;
		}
		if (Files.exists(dataFile) && isReadableDataset(dataFile)) {
			log.warn("WA downloads failed; using existing cache at {}", dataFile);
			return dataFile;
		}
		throw new IngestionException("WA dataset cache miss and all downloads failed for " + filename);
	}

	public Path resolveCrimeTimeseriesFile() {
		return resolveCrimeTimeseriesFile(false);
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
			Instant expiresAt = manifest.getLastFetched().plus(properties.getWa().getCacheTtl());
			if (Instant.now().isAfter(expiresAt)) {
				return false;
			}
			if (manifest.getSha256() != null) {
				String localHash = SaDatasetCacheService.sha256(dataFile);
				return manifest.getSha256().equalsIgnoreCase(localHash);
			}
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

	private void downloadFile(String downloadUrl, Path dataFile, Path manifestFile, String resourceName)
			throws IOException {
		RestClient client = restClientBuilder.build();
		byte[] bytes = client.get()
				.uri(downloadUrl)
				.header(HttpHeaders.USER_AGENT, properties.getWa().getDownloadUserAgent())
				.retrieve()
				.body(byte[].class);
		if (bytes == null || bytes.length == 0) {
			throw new IngestionException("Empty WA download from " + downloadUrl);
		}
		if (!DatasetFileValidator.looksLikeDataset(bytes)) {
			throw new IngestionException("WA download from " + downloadUrl + " returned non-dataset content");
		}
		Files.createDirectories(dataFile.getParent());
		Files.write(dataFile, bytes);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceName(resourceName);
		manifest.setDownloadUrl(downloadUrl);
		manifest.setSha256(SaDatasetCacheService.sha256(bytes));
		manifest.setLastFetched(Instant.now());
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
		datasetVersionRegistrar.register("wa:crime-statistics", resourceName, dataFile, manifest);
		log.info("Cached WA resource {} ({} bytes) at {}", resourceName, bytes.length, dataFile);
	}

	static boolean isReadableDataset(Path file) {
		return DatasetFileValidator.isReadableDataset(file);
	}

}
