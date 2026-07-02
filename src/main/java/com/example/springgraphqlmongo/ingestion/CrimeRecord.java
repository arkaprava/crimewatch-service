package com.example.springgraphqlmongo.ingestion;

import java.time.Instant;

/**
 * Source-agnostic representation of a crime record produced by a
 * {@link CrimeDataSource}. The ingestion service maps this onto the
 * {@code CrimeIncident} document, classifying type and severity.
 *
 * @param externalId  stable identifier of the record within its source
 * @param title       short summary, e.g. offence name
 * @param description free-text detail, may be null
 * @param category    raw offence category as published by the source
 * @param occurredAt  when the offence occurred
 * @param suburb      suburb / locality, may be null
 * @param state       Australian state or territory code, e.g. "NSW"
 * @param postalCode  postcode, may be null
 * @param latitude    WGS84 latitude, may be null
 * @param longitude   WGS84 longitude, may be null
 */
public record CrimeRecord(
		String externalId,
		String title,
		String description,
		String category,
		Instant occurredAt,
		String suburb,
		String state,
		String postalCode,
		Double latitude,
		Double longitude) {
}
