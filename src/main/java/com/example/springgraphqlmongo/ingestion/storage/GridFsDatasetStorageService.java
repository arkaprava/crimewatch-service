package com.example.springgraphqlmongo.ingestion.storage;

import com.example.springgraphqlmongo.domain.DatasetVersion;
import com.example.springgraphqlmongo.domain.DatasetVersionStatus;
import com.example.springgraphqlmongo.ingestion.cache.CacheManifest;
import com.example.springgraphqlmongo.ingestion.cache.DatasetTarArchive;
import com.example.springgraphqlmongo.repository.DatasetVersionRepository;
import com.mongodb.client.gridfs.model.GridFSFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Content-addressed dataset storage in MongoDB GridFS with append-only versioning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridFsDatasetStorageService {

	private final GridFsOperations gridFsOperations;

	private final DatasetVersionRepository datasetVersionRepository;

	public DatasetVersion registerFromPath(String source, String resourceKey, Path dataFile, CacheManifest manifest,
			String contentType) throws IOException {
		String sha256 = DatasetTarArchive.sha256(dataFile);
		long byteSize = byteSizeOf(dataFile);

		Optional<DatasetVersion> existing = datasetVersionRepository.findBySourceAndResourceKeyAndSha256(source,
				resourceKey, sha256);
		if (existing.isPresent()) {
			log.debug("Dataset {}:{} unchanged (sha256={}); reusing version {}", source, resourceKey, sha256,
					existing.get().getVersion());
			return existing.get();
		}

		ObjectId gridFsFileId;
		try (InputStream input = DatasetTarArchive.openCombinedInputStream(dataFile)) {
			Map<String, Object> metadata = new HashMap<>();
			metadata.put("source", source);
			metadata.put("resourceKey", resourceKey);
			metadata.put("sha256", sha256);
			metadata.put("contentType", contentType);
			gridFsFileId = gridFsOperations.store(input, resourceKey, contentType, metadata);
		}

		int nextVersion = datasetVersionRepository.findTopBySourceAndResourceKeyOrderByVersionDesc(source, resourceKey)
				.map(v -> v.getVersion() + 1)
				.orElse(1);

		datasetVersionRepository.findTopBySourceAndResourceKeyOrderByVersionDesc(source, resourceKey)
				.ifPresent(previous -> {
					previous.setStatus(DatasetVersionStatus.SUPERSEDED);
					datasetVersionRepository.save(previous);
				});

		DatasetVersion version = DatasetVersion.builder()
				.source(source)
				.resourceKey(resourceKey)
				.version(nextVersion)
				.sha256(sha256)
				.contentType(contentType)
				.byteSize(byteSize)
				.gridFsFileId(gridFsFileId)
				.manifest(manifest)
				.status(DatasetVersionStatus.STORED)
				.createdAt(Instant.now())
				.build();
		DatasetVersion saved = datasetVersionRepository.save(version);
		log.info("Stored dataset {}:{} v{} in GridFS ({} bytes, sha256={})", source, resourceKey, nextVersion,
				byteSize, sha256);
		return saved;
	}

	public Optional<DatasetVersion> findLatest(String source, String resourceKey) {
		return datasetVersionRepository.findTopBySourceAndResourceKeyOrderByVersionDesc(source, resourceKey);
	}

	public InputStream openStream(DatasetVersion version) throws IOException {
		GridFSFile file = gridFsOperations.findOne(new Query(Criteria.where("_id").is(version.getGridFsFileId())));
		if (file == null) {
			throw new IOException("GridFS file not found for version " + version.getId());
		}
		GridFsResource resource = gridFsOperations.getResource(file);
		return resource.getInputStream();
	}

	private static long byteSizeOf(Path dataFile) throws IOException {
		Optional<Path> archive = DatasetTarArchive.resolveArchive(dataFile);
		if (archive.isPresent()) {
			if (DatasetTarArchive.isMultipart(archive.get())) {
				long total = 0;
				for (Path part : DatasetTarArchive.listMultipartParts(archive.get())) {
					total += Files.size(part);
				}
				return total;
			}
			return Files.size(archive.get());
		}
		return Files.size(dataFile);
	}

}
