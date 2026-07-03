package com.example.springgraphqlmongo.ingestion.period;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ReportingPeriodResolverTest {

	private final ReportingPeriodResolver resolver = new ReportingPeriodResolver();

	private final ZoneId perth = ZoneId.of("Australia/Perth");

	@Test
	void resolvesQuarterEnd() {
		assertThat(resolver.resolveEnd("2025-Q1", perth).toString()).contains("2025-03-31");
	}

	@Test
	void resolvesFinancialYearEnd() {
		assertThat(resolver.resolveEnd("2024-25", perth).toString()).contains("2025-06-30");
	}

}
