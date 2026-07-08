package com.example.springgraphqlmongo.ingestion.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SaOffenderReferenceLoaderCsvTest {

	@Test
	void parseCsvLineHandlesQuotedFieldsWithoutRegexOverflow() {
		assertThat(SaOffenderReferenceLoader.parseCsvLine("a,b,c")).containsExactly("a", "b", "c");
		assertThat(SaOffenderReferenceLoader.parseCsvLine("\"a,b\",c")).containsExactly("a,b", "c");
		assertDoesNotThrow(() -> SaOffenderReferenceLoader.parseCsvLine("x,".repeat(50_000) + "y"));
	}

}
