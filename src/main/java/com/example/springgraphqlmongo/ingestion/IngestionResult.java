package com.example.springgraphqlmongo.ingestion;

/**
 * Outcome of ingesting a single source.
 *
 * @param source    source name
 * @param fetched   number of records fetched from the source
 * @param inserted  number of new incidents persisted
 * @param duplicates number of records skipped because they already exist
 * @param failed    number of records that could not be mapped or saved
 * @param error     error message when the whole source failed, otherwise null
 */
public record IngestionResult(
		String source,
		int fetched,
		int inserted,
		int duplicates,
		int failed,
		String error) {

	public static IngestionResult failure(String source, String error) {
		return new IngestionResult(source, 0, 0, 0, 0, error);
	}

}
