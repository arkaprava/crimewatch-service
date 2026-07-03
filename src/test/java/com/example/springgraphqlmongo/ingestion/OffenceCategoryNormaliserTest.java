package com.example.springgraphqlmongo.ingestion;

import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OffenceCategoryNormaliserTest {

	private final OffenceCategoryNormaliser normaliser = new OffenceCategoryNormaliser();

	@Test
	void normalisesAssaultVariants() {
		assertThat(normaliser.normalise("Serious Assault")).isEqualTo("Assault");
		assertThat(normaliser.normalise("Common Assault")).isEqualTo("Assault");
	}

	@Test
	void buildsCorrelationKey() {
		assertThat(normaliser.correlationKey("sa", "2024-25", "Motor Vehicle Theft"))
				.isEqualTo("SA|2024-25|Theft");
	}

}
