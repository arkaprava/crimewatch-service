package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import lombok.Builder;

@Builder
public record SuburbMatch(
		AustralianSuburb suburb,
		GeocodeStatus status,
		String canonicalName) {

	public static SuburbMatch unresolved(String rawName) {
		return SuburbMatch.builder()
				.status(GeocodeStatus.UNRESOLVED)
				.canonicalName(rawName)
				.build();
	}

}
