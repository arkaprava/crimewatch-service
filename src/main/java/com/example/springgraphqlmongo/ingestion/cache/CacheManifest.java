package com.example.springgraphqlmongo.ingestion.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CacheManifest {

	private String resourceName;

	private String resourceId;

	private String downloadUrl;

	/** CKAN resource hash (portal-provided, often MD5). */
	private String resourceHash;

	/** CKAN resource last_modified timestamp. */
	private Instant resourceLastModified;

	private String revisionId;

	private String etag;

	/** SHA-256 of the cached file bytes. */
	private String sha256;

	private Instant lastFetched;

}
