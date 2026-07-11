package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.example.springgraphqlmongo.ingestion.storage.DatasetVersionRegistrar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Cache-first access to SA open data files. Runtime ingestion reads local
 * copies under {@code data/sa/}; remote downloads happen only when the cache
 * is missing, stale, or a refresh is explicitly requested.
 *
 * Staleness is determined by (in order):
 * <ol>
 *   <li>Explicit {@code refresh=true}</li>
 *   <li>Missing cache file or manifest</li>
 *   <li>Expired {@code ingestion.sa.cache-ttl}</li>
 *   <li>CKAN resource metadata change ({@code hash}, {@code last_modified}, {@code revision_id}, {@code url})</li>
 *   <li>Local file SHA-256 no longer matching the manifest</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaDatasetCacheService {

	private static final String PACKAGE_SHOW = "/api/3/action/package_show";

	private final IngestionProperties properties;

	private final RestClient.Builder restClientBuilder;

	private final ObjectMapper objectMapper;

	private final DatasetVersionRegistrar datasetVersionRegistrar;

	public Path resolveCrimeStatisticsFile(String resourceName, boolean refresh) {
		return resolveCachedFile("crime-statistics", resourceName, properties.getSa().getPackageIds()
				.get("crime-statistics"), refresh);
	}

	public Path resolveOffenderFile(boolean refresh) {
		String resourceName = "Recorded Crime - Offenders";
		Path fallback = Path.of(properties.getSa().getCacheDir(), "recorded-crime-offenders",
				"recorded-crime-offenders.csv");
		try {
			Path resolved = resolveCachedFile("recorded-crime-offenders", resourceName,
					properties.getSa().getPackageIds().get("offenders"), refresh);
			if (DatasetFileValidator.isReadableDataset(resolved)) {
				return resolved;
			}
			log.warn("Invalid SA offender cache at {}; trying fallback", resolved);
		}
		catch (Exception ex) {
			log.warn("Failed to resolve SA offender cache: {}", ex.getMessage());
		}
		if (Files.exists(fallback) && DatasetFileValidator.isReadableDataset(fallback)) {
			log.info("Using SA offender fallback at {}", fallback);
			return fallback;
		}
		throw new IngestionException("No readable SA offender reference file available");
	}

	public Path resolveCrimeStatisticsFile(String resourceName) {
		return resolveCrimeStatisticsFile(resourceName, false);
	}

	public Path resolveOffenderFile() {
		return resolveOffenderFile(false);
	}

	private Path resolveCachedFile(String datasetDir, String resourceName, String packageId, boolean refresh) {
		Path cacheDir = Path.of(properties.getSa().getCacheDir(), datasetDir);
		Path dataFile = cacheDir.resolve(sanitiseFileName(resourceName));
		Path manifestFile = cacheDir.resolve("manifest-" + sanitiseFileName(resourceName) + ".json");

		if (!refresh && DatasetTarArchive.exists(dataFile)) {
			if (!DatasetFileValidator.isReadableDataset(dataFile)) {
				log.warn("Cached SA dataset {} at {} is unreadable; refreshing", resourceName, dataFile);
			}
			else {
			try {
				JsonNode remoteResource = fetchRemoteResource(packageId, resourceName);
				if (isCacheFresh(dataFile, manifestFile, remoteResource)) {
					log.debug("Using fresh cached SA dataset {} at {}", resourceName, dataFile);
					return dataFile;
				}
				log.info("SA cache stale for {}; refreshing from portal", resourceName);
			}
			catch (Exception ex) {
				log.warn("Could not verify SA cache freshness for {}; using existing file at {}", resourceName,
						dataFile, ex);
				return dataFile;
			}
			}
		}

		try {
			downloadResource(datasetDir, packageId, resourceName, dataFile, manifestFile);
			return dataFile;
		}
		catch (Exception ex) {
			if (DatasetTarArchive.exists(dataFile)) {
				log.warn("SA download failed for {}; using existing cache at {}", resourceName, dataFile, ex);
				return dataFile;
			}
			throw new IngestionException("SA dataset cache miss and download failed for " + resourceName, ex);
		}
	}

	boolean isCacheFresh(Path dataFile, Path manifestFile, JsonNode remoteResource) throws IOException {
		if (!Files.exists(manifestFile)) {
			log.debug("Cache manifest missing for {}", dataFile);
			return false;
		}

		CacheManifest manifest = objectMapper.readValue(Files.readString(manifestFile), CacheManifest.class);
		if (manifest.getLastFetched() == null) {
			return false;
		}

		Instant expiresAt = manifest.getLastFetched().plus(properties.getSa().getCacheTtl());
		if (Instant.now().isAfter(expiresAt)) {
			log.debug("Cache TTL expired for {} (last fetched {})", dataFile, manifest.getLastFetched());
			return false;
		}

		ResourceMetadata remote = ResourceMetadata.from(remoteResource);
		if (!remote.matches(manifest)) {
			log.debug("CKAN metadata changed for {} (remote hash={}, manifest hash={})", dataFile,
					remote.hash(), manifest.getResourceHash());
			return false;
		}

		if (manifest.getSha256() != null) {
			String localHash = DatasetTarArchive.sha256(dataFile);
			if (!manifest.getSha256().equalsIgnoreCase(localHash)) {
				log.debug("Local file checksum mismatch for {}", dataFile);
				return false;
			}
		}

		return true;
	}

	private JsonNode fetchRemoteResource(String packageId, String resourceName) {
		JsonNode packageNode = fetchPackage(packageId);
		return findResource(packageNode, resourceName)
				.orElseThrow(() -> new IngestionException("Resource not found in SA package: " + resourceName));
	}

	private void downloadResource(String datasetDir, String packageId, String resourceName, Path dataFile,
			Path manifestFile) throws IOException {
		JsonNode resource = fetchRemoteResource(packageId, resourceName);
		ResourceMetadata metadata = ResourceMetadata.from(resource);

		String downloadUrl = metadata.url();
		if (downloadUrl == null || downloadUrl.isBlank()) {
			throw new IngestionException("No download URL for SA resource: " + resourceName);
		}

		Files.createDirectories(dataFile.getParent());
		RestClient client = restClientBuilder.build();
		byte[] bytes = client.get()
				.uri(downloadUrl)
				.header("User-Agent", properties.getSa().getDownloadUserAgent())
				.retrieve()
				.body(byte[].class);

		if (bytes == null || bytes.length == 0) {
			throw new IngestionException("Empty download for SA resource: " + resourceName);
		}
		if (!DatasetFileValidator.looksLikeDataset(bytes)) {
			throw new IngestionException("SA download for " + resourceName + " returned non-dataset content");
		}

		DatasetTarArchive.writeCsvArchive(bytes, dataFile.getFileName().toString(), dataFile);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceName(resourceName);
		manifest.setResourceId(metadata.id());
		manifest.setDownloadUrl(downloadUrl);
		manifest.setResourceHash(metadata.hash());
		manifest.setResourceLastModified(metadata.lastModified());
		manifest.setRevisionId(metadata.revisionId());
		manifest.setSha256(DatasetTarArchive.sha256(dataFile));
		manifest.setLastFetched(Instant.now());
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
		Path storedAt = DatasetTarArchive.resolveArchive(dataFile).orElse(dataFile);
		datasetVersionRegistrar.register("sa:" + datasetDir, resourceName, dataFile, manifest);
		log.info("Cached SA resource {} as tar archive at {}", resourceName, storedAt);
	}

	private JsonNode fetchPackage(String packageId) {
		try {
			RestClient client = restClientBuilder.baseUrl(properties.getSa().getBaseUrl()).build();
			JsonNode response = client.get()
					.uri(uriBuilder -> uriBuilder.path(PACKAGE_SHOW).queryParam("id", packageId).build())
					.header("User-Agent", properties.getSa().getDownloadUserAgent())
					.retrieve()
					.body(JsonNode.class);
			if (response == null || !response.path("success").asBoolean(false)) {
				throw new IngestionException("SA package_show failed for " + packageId);
			}
			return response.path("result");
		}
		catch (Exception ex) {
			throw new IngestionException("Failed to fetch SA package metadata for " + packageId, ex);
		}
	}

	private Optional<JsonNode> findResource(JsonNode packageNode, String resourceName) {
		for (JsonNode resource : packageNode.path("resources")) {
			if (resourceName.equalsIgnoreCase(resource.path("name").asText())) {
				return Optional.of(resource);
			}
		}
		return Optional.empty();
	}

	static String sanitiseFileName(String resourceName) {
		String cleaned = resourceName.replaceAll("[^a-zA-Z0-9._-]+", "-").toLowerCase();
		if (!cleaned.endsWith(".csv") && !cleaned.endsWith(".xls") && !cleaned.endsWith(".xlsx")) {
			cleaned = cleaned + ".csv";
		}
		return cleaned;
	}

	static String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static String sha256(InputStream input) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static String sha256(Path file) throws IOException {
		try (InputStream input = Files.newInputStream(file)) {
			return sha256(input);
		}
	}

	record ResourceMetadata(String id, String url, String hash, Instant lastModified, String revisionId) {

		static ResourceMetadata from(JsonNode resource) {
			String lastModifiedRaw = resource.path("last_modified").asText(null);
			Instant lastModified = null;
			if (lastModifiedRaw != null && !lastModifiedRaw.isBlank()) {
				lastModified = parseInstant(lastModifiedRaw);
			}
			return new ResourceMetadata(
					resource.path("id").asText(null),
					resource.path("url").asText(null),
					resource.path("hash").asText(null),
					lastModified,
					resource.path("revision_id").asText(null));
		}

		boolean matches(CacheManifest manifest) {
			if (manifest.getResourceId() != null && id != null && !manifest.getResourceId().equals(id)) {
				return false;
			}
			if (manifest.getDownloadUrl() != null && url != null && !manifest.getDownloadUrl().equals(url)) {
				return false;
			}
			if (manifest.getResourceHash() != null && hash != null && !manifest.getResourceHash().equalsIgnoreCase(hash)) {
				return false;
			}
			if (manifest.getResourceLastModified() != null && lastModified != null
					&& !manifest.getResourceLastModified().equals(lastModified)) {
				return false;
			}
			if (manifest.getRevisionId() != null && revisionId != null
					&& !manifest.getRevisionId().equals(revisionId)) {
				return false;
			}
			// If portal exposes no change signals, rely on TTL + local checksum only.
			return true;
		}

	}

	private static Instant parseInstant(String raw) {
		try {
			return Instant.parse(raw);
		}
		catch (DateTimeParseException ex) {
			return LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC).toInstant();
		}
	}

}
