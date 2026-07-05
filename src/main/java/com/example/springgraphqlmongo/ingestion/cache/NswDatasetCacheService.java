package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class NswDatasetCacheService {

	private final IngestionProperties properties;

	private final RestClient.Builder restClientBuilder;

	private final ObjectMapper objectMapper;

	public Path resolveSuburbDataFile(boolean refresh) {
		IngestionProperties.NswSettings nsw = properties.getNsw();
		Path cacheDir = Path.of(nsw.getCacheDir(), "crime-statistics");
		String filename = nsw.getSuburbDataCsvFilename();
		Path dataFile = cacheDir.resolve(filename);
		Path manifestFile = cacheDir.resolve("manifest-" + filename + ".json");

		if (!refresh && Files.exists(dataFile) && isCacheFresh(dataFile, manifestFile)) {
			log.debug("Using cached NSW suburb crime data at {}", dataFile);
			return dataFile;
		}

		try {
			downloadAndExtract(nsw.getSuburbDataZipUrl(), dataFile, manifestFile, filename);
			if (isReadableDataset(dataFile)) {
				return dataFile;
			}
		}
		catch (Exception ex) {
			log.warn("NSW suburb data download failed from {}: {}", nsw.getSuburbDataZipUrl(), ex.getMessage());
		}

		Path csvFallback = cacheDir.resolve(nsw.getCsvFallbackFilename());
		if (Files.exists(csvFallback)) {
			log.info("Using NSW CSV fallback at {}", csvFallback);
			return csvFallback;
		}
		if (Files.exists(dataFile) && isReadableDataset(dataFile)) {
			log.warn("NSW download failed; using existing cache at {}", dataFile);
			return dataFile;
		}
		throw new IngestionException("NSW dataset cache miss and download failed for " + filename);
	}

	public Path resolveSuburbDataFile() {
		return resolveSuburbDataFile(false);
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
			Instant expiresAt = manifest.getLastFetched().plus(properties.getNsw().getCacheTtl());
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

	private void downloadAndExtract(String downloadUrl, Path dataFile, Path manifestFile, String resourceName)
			throws IOException {
		RestClient client = restClientBuilder.build();
		byte[] bytes = client.get()
				.uri(downloadUrl)
				.header(HttpHeaders.USER_AGENT, properties.getNsw().getDownloadUserAgent())
				.retrieve()
				.body(byte[].class);
		if (bytes == null || bytes.length == 0) {
			throw new IngestionException("Empty NSW download from " + downloadUrl);
		}
		if (!looksLikeZip(bytes)) {
			throw new IngestionException("NSW download from " + downloadUrl + " is not a ZIP archive");
		}

		Files.createDirectories(dataFile.getParent());
		extractFirstCsv(bytes, dataFile);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceName(resourceName);
		manifest.setDownloadUrl(downloadUrl);
		manifest.setSha256(SaDatasetCacheService.sha256(Files.readAllBytes(dataFile)));
		manifest.setLastFetched(Instant.now());
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
		log.info("Cached NSW resource {} ({} bytes) at {}", resourceName, Files.size(dataFile), dataFile);
	}

	static void extractFirstCsv(byte[] zipBytes, Path targetCsv) throws IOException {
		try (ZipInputStream zipInputStream = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".csv")) {
					continue;
				}
				Files.copy(zipInputStream, targetCsv, StandardCopyOption.REPLACE_EXISTING);
				return;
			}
		}
		throw new IngestionException("No CSV file found in NSW suburb data ZIP");
	}

	static boolean looksLikeZip(byte[] bytes) {
		return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K';
	}

	static boolean looksLikeDataset(byte[] bytes) {
		if (bytes.length < 4) {
			return false;
		}
		String prefix = new String(bytes, 0, Math.min(bytes.length, 300));
		return prefix.contains("Suburb") && prefix.contains("Offence");
	}

	static boolean isReadableDataset(Path file) {
		try {
			byte[] prefix = Files.readAllBytes(file);
			return prefix.length > 0 && looksLikeDataset(prefix);
		}
		catch (IOException ex) {
			return false;
		}
	}

}
