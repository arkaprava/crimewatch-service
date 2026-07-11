package com.example.springgraphqlmongo.ingestion.storage;

import com.example.springgraphqlmongo.domain.DatasetVersion;
import com.example.springgraphqlmongo.ingestion.cache.CacheManifest;
import com.example.springgraphqlmongo.ingestion.cache.DatasetTarArchive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Registers downloaded datasets in GridFS and links them to the current ingestion run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetVersionRegistrar {

	private final GridFsDatasetStorageService gridFsDatasetStorageService;

	private final IngestionContext ingestionContext;

	public DatasetVersion register(String source, String resourceKey, Path dataFile, CacheManifest manifest) {
		try {
			String contentType = contentTypeFor(dataFile);
			DatasetVersion version = gridFsDatasetStorageService.registerFromPath(source, resourceKey, dataFile,
					manifest, contentType);
			ingestionContext.registerDatasetVersion(version.getId());
			return version;
		}
		catch (IOException ex) {
			log.warn("Failed to register dataset {}:{} in GridFS: {}", source, resourceKey, ex.getMessage());
			return null;
		}
	}

	private static String contentTypeFor(Path dataFile) throws IOException {
		Optional<Path> archive = DatasetTarArchive.resolveArchive(dataFile);
		if (archive.isPresent()) {
			String name = archive.get().getFileName().toString().toLowerCase();
			if (name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".gz")) {
				return "application/gzip";
			}
			return "application/x-tar";
		}
		String name = dataFile.getFileName().toString().toLowerCase();
		if (name.endsWith(".csv")) {
			return "text/csv";
		}
		if (name.endsWith(".pdf")) {
			return "application/pdf";
		}
		if (name.endsWith(".xlsx")) {
			return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
		}
		return Files.probeContentType(dataFile);
	}

}
