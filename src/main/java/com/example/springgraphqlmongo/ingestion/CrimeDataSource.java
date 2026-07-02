package com.example.springgraphqlmongo.ingestion;

import java.util.List;

/**
 * Contract for an external Australian crime data source. Implementations
 * fetch raw records from a provider (open data portal, police API, CSV feed,
 * etc.) and normalise them into {@link CrimeRecord}s. Registered
 * implementations are discovered by the ingestion service automatically.
 */
public interface CrimeDataSource {

	/** Unique name of this source, stored on ingested incidents (e.g. "qld-police-offences"). */
	String name();

	/** Whether this source is currently enabled and should be polled. */
	boolean isEnabled();

	/**
	 * Fetch the next batch of records from the source. Implementations should
	 * throw {@link IngestionException} on unrecoverable errors so the
	 * ingestion service can record the failure and continue with other sources.
	 */
	List<CrimeRecord> fetchRecords();

}
