package com.example.springgraphqlmongo.domain;

import com.example.springgraphqlmongo.ingestion.cache.CacheManifest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "dataset_versions")
@CompoundIndex(name = "source_resource_version_idx", def = "{'source': 1, 'resourceKey': 1, 'version': -1}")
@CompoundIndex(name = "source_resource_sha256_idx", def = "{'source': 1, 'resourceKey': 1, 'sha256': 1}")
public class DatasetVersion {

	@Id
	private String id;

	/** Logical namespace, e.g. {@code sa:crime-statistics}. */
	@Indexed
	private String source;

	/** Resource identifier within the namespace, e.g. CSV filename or CKAN resource name. */
	private String resourceKey;

	private int version;

	@Indexed
	private String sha256;

	private String contentType;

	private long byteSize;

	private ObjectId gridFsFileId;

	private CacheManifest manifest;

	private DatasetVersionStatus status;

	private Instant createdAt;

}
