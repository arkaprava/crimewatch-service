package com.example.springgraphqlmongo.ingestion;

import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.domain.SaOffenderContext;
import lombok.Builder;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;

import java.time.Instant;

/**
 * Source-agnostic representation of a crime record produced by a
 * {@link CrimeDataSource}. The ingestion service maps this onto the
 * {@code CrimeIncident} document, classifying type and severity.
 */
@Builder
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
		Double longitude,
		Integer offenceCount,
		String reportingPeriod,
		RecordGranularity granularity,
		GeocodeStatus geocodeStatus,
		String suburbId,
		GeoJsonPolygon boundary,
		SaOffenderContext offenderContext) {

	public CrimeRecord {
		if (granularity == null) {
			granularity = RecordGranularity.INCIDENT;
		}
	}

}
