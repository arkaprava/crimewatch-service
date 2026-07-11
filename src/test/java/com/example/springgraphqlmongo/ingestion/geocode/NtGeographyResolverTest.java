package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class NtGeographyResolverTest {

	private NtGeographyResolver resolver;

	@BeforeEach
	void setUp() {
		IngestionProperties properties = new IngestionProperties();
		properties.getNt().setGeographyFile("data/nt/nt-police-geography.geojson");
		resolver = new NtGeographyResolver(properties, new ObjectMapper());
		ReflectionTestUtils.invokeMethod(resolver, "loadGeography");
	}

	@Test
	void resolvesReportingRegions() {
		NtGeographyResolver.GeographyResolution darwin = resolver.resolve("Darwin", null);

		assertThat(darwin.canonicalName()).isEqualTo("Darwin");
		assertThat(darwin.granularity()).isEqualTo(RecordGranularity.DISTRICT_AGGREGATE);
		assertThat(darwin.geocodeStatus()).isEqualTo(GeocodeStatus.APPROXIMATE);
		assertThat(darwin.latitude()).isNotNull();
		assertThat(darwin.longitude()).isNotNull();
	}

	@Test
	void prefersSa2OverReportingRegion() {
		NtGeographyResolver.GeographyResolution barkly = resolver.resolve("NT Balance", "Barkly");

		assertThat(barkly.canonicalName()).isEqualTo("Barkly");
		assertThat(barkly.suburbId()).isEqualTo("NT:SA2:BARKLY");
	}

}
