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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TasDatasetCacheService {

	private static final Pattern SUPPLEMENT_LINK = Pattern.compile(
			"href=\"([^\"]*(?:Crime-Statistics-Supplement|DPFEM-Crime-Statistics-Supplement)[^\"]*\\.pdf)\"",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern CPR_LINK = Pattern.compile(
			"href=\"([^\"]*Corporate-Performance-Report[^\"]*\\.pdf)\"",
			Pattern.CASE_INSENSITIVE);

	private final IngestionProperties properties;

	private final RestClient.Builder restClientBuilder;

	private final ObjectMapper objectMapper;

	public Path resolveSupplementPdf(boolean refresh) {
		IngestionProperties.TasSettings tas = properties.getTas();
		String url = resolveSupplementUrl();
		String filename = filenameFromUrl(url, "crime-statistics-supplement.pdf");
		Path cacheDir = Path.of(tas.getCacheDir(), "crime-statistics");
		return resolvePdf(refresh, cacheDir, filename, url, "TAS crime statistics supplement");
	}

	public Path resolveSupplementPdf() {
		return resolveSupplementPdf(false);
	}

	public Path resolveCorporatePerformancePdf(boolean refresh) {
		IngestionProperties.TasSettings tas = properties.getTas();
		String url = resolveCorporatePerformanceUrl();
		String filename = filenameFromUrl(url, "corporate-performance-report.pdf");
		Path cacheDir = Path.of(tas.getCacheDir(), "corporate-performance");
		return resolvePdf(refresh, cacheDir, filename, url, "TAS corporate performance report");
	}

	public Path resolveCorporatePerformancePdf() {
		return resolveCorporatePerformancePdf(false);
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
			Instant expiresAt = manifest.getLastFetched().plus(properties.getTas().getCacheTtl());
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

	private Path resolvePdf(boolean refresh, Path cacheDir, String filename, String downloadUrl, String label) {
		Path dataFile = cacheDir.resolve(filename);
		Path manifestFile = cacheDir.resolve("manifest-" + filename + ".json");

		if (!refresh && Files.exists(dataFile) && isCacheFresh(dataFile, manifestFile)) {
			log.debug("Using cached {} at {}", label, dataFile);
			return dataFile;
		}

		try {
			downloadPdf(downloadUrl, dataFile, manifestFile, filename);
			if (isReadablePdf(dataFile)) {
				return dataFile;
			}
		}
		catch (Exception ex) {
			log.warn("{} download failed from {}: {}", label, downloadUrl, ex.getMessage());
		}

		if (Files.exists(dataFile) && isReadablePdf(dataFile)) {
			log.warn("{} download failed; using existing cache at {}", label, dataFile);
			return dataFile;
		}
		throw new IngestionException(label + " cache miss and download failed for " + filename);
	}

	private void downloadPdf(String downloadUrl, Path dataFile, Path manifestFile, String resourceName)
			throws IOException {
		RestClient client = restClientBuilder.build();
		byte[] bytes = client.get()
				.uri(downloadUrl)
				.header(HttpHeaders.USER_AGENT, properties.getTas().getDownloadUserAgent())
				.retrieve()
				.body(byte[].class);
		if (bytes == null || bytes.length == 0) {
			throw new IngestionException("Empty TAS download from " + downloadUrl);
		}
		if (!looksLikePdf(bytes)) {
			throw new IngestionException("TAS download from " + downloadUrl + " is not a PDF");
		}

		Files.createDirectories(dataFile.getParent());
		Files.write(dataFile, bytes);

		CacheManifest manifest = new CacheManifest();
		manifest.setResourceName(resourceName);
		manifest.setDownloadUrl(downloadUrl);
		manifest.setSha256(SaDatasetCacheService.sha256(bytes));
		manifest.setLastFetched(Instant.now());
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
		log.info("Cached TAS resource {} ({} bytes) at {}", resourceName, bytes.length, dataFile);
	}

	private String resolveSupplementUrl() {
		IngestionProperties.TasSettings tas = properties.getTas();
		if (tas.getSupplementPdfUrl() != null && !tas.getSupplementPdfUrl().isBlank()) {
			return tas.getSupplementPdfUrl();
		}
		return discoverPdfUrl(SUPPLEMENT_LINK, "crime statistics supplement");
	}

	private String resolveCorporatePerformanceUrl() {
		IngestionProperties.TasSettings tas = properties.getTas();
		if (tas.getCorporatePerformancePdfUrl() != null && !tas.getCorporatePerformancePdfUrl().isBlank()) {
			return tas.getCorporatePerformancePdfUrl();
		}
		return discoverPdfUrl(CPR_LINK, "corporate performance report");
	}

	private String discoverPdfUrl(Pattern linkPattern, String label) {
		RestClient client = restClientBuilder.build();
		String html = client.get()
				.uri(properties.getTas().getPerformancePageUrl())
				.header(HttpHeaders.USER_AGENT, properties.getTas().getDownloadUserAgent())
				.retrieve()
				.body(String.class);
		if (html == null || html.isBlank()) {
			throw new IngestionException("Unable to discover TAS " + label + ": empty performance page");
		}

		List<String> matches = new ArrayList<>();
		Matcher matcher = linkPattern.matcher(html);
		while (matcher.find()) {
			matches.add(normaliseUrl(matcher.group(1)));
		}
		if (matches.isEmpty()) {
			throw new IngestionException("Unable to discover TAS " + label + " PDF on performance page");
		}
		return matches.stream().max(Comparator.naturalOrder()).orElse(matches.getFirst());
	}

	static String normaliseUrl(String href) {
		if (href.startsWith("http://") || href.startsWith("https://")) {
			return href;
		}
		if (href.startsWith("/")) {
			return "https://www.police.tas.gov.au" + href;
		}
		return "https://www.police.tas.gov.au/" + href;
	}

	static String filenameFromUrl(String url, String fallback) {
		try {
			String path = URI.create(url).getPath();
			int slash = path.lastIndexOf('/');
			if (slash >= 0 && slash < path.length() - 1) {
				return path.substring(slash + 1).toLowerCase();
			}
		}
		catch (Exception ignored) {
			// fall through
		}
		return fallback;
	}

	static boolean looksLikePdf(byte[] bytes) {
		return bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
	}

	static boolean isReadablePdf(Path file) {
		try {
			byte[] prefix = Files.readAllBytes(file);
			return prefix.length > 0 && looksLikePdf(prefix);
		}
		catch (IOException ex) {
			return false;
		}
	}

}
