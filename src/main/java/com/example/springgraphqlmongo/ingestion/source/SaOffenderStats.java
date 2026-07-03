package com.example.springgraphqlmongo.ingestion.source;

import lombok.Builder;

@Builder
public record SaOffenderStats(
		String state,
		String reportingPeriod,
		String principalOffence,
		int offenderCount) {
}
