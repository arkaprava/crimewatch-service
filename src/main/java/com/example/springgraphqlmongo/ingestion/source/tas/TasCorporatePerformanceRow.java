package com.example.springgraphqlmongo.ingestion.source.tas;

import lombok.Builder;

@Builder
public record TasCorporatePerformanceRow(
		String reportPeriod,
		String geographyLevel,
		String geographyName,
		String offenceSection,
		String offenceType,
		int count) {
}
