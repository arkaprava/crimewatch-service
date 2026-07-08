package com.example.springgraphqlmongo.ingestion.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SaCrimeStatisticsDataSourceSlugifyTest {

	@Test
	void slugifyHandlesLongValuesWithoutStackOverflow() {
		String longValue = "sa-2024-25-" + "a".repeat(50_000) + "-assault";
		assertDoesNotThrow(() -> SaCrimeStatisticsDataSource.slugify(longValue));
		assertThat(SaCrimeStatisticsDataSource.slugify("  Adelaide Assault!! "))
				.isEqualTo("adelaide-assault");
	}

}
