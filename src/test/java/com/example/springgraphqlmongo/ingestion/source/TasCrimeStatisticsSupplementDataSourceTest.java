package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.cache.TasDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.geocode.TasGeographyResolver;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TasCrimeStatisticsSupplementDataSourceTest {

	@Mock
	private TasDatasetCacheService cacheService;

	private TasGeographyResolver geographyResolver;

	private TasCrimeStatisticsSupplementDataSource dataSource;

	@BeforeEach
	void setUp() throws Exception {
		IngestionProperties properties = new IngestionProperties();
		properties.getTas().setGeographyFile("data/tas/tas-police-geography.geojson");
		geographyResolver = new TasGeographyResolver(properties, new com.fasterxml.jackson.databind.ObjectMapper());
		ReflectionTestUtils.invokeMethod(geographyResolver, "loadGeography");

		IngestionProperties.Source config = new IngestionProperties.Source();
		config.setName("tas-dpfem-crime-statistics");
		config.setEnabled(true);
		config.setState("TAS");
		config.setZoneId("Australia/Hobart");
		config.setBatchSize(5000);

		Path pdf = new ClassPathResource("tas/crime-statistics-supplement-sample.pdf").getFile().toPath();
		when(cacheService.resolveSupplementPdf(false)).thenReturn(pdf);

		dataSource = new TasCrimeStatisticsSupplementDataSource(config, cacheService, geographyResolver,
				new OffenceCategoryNormaliser());
	}

	@Test
	void mapsSupplementRowsToStateAggregateRecords() {
		List<CrimeRecord> records = dataSource.fetchRecords();
		assertThat(records).isNotEmpty();
		assertThat(records).allMatch(record -> record.granularity() == RecordGranularity.STATE_AGGREGATE);
		assertThat(records).allMatch(record -> "TAS".equals(record.state()));
		assertThat(records).allMatch(record -> record.offenceCount() != null && record.offenceCount() > 0);
	}

	@Test
	void financialYearEndIsJuneThirtieth() {
		var instant = TasCrimeStatisticsSupplementDataSource.financialYearEnd("2024-25",
				ZoneId.of("Australia/Hobart"));
		assertThat(instant.atZone(ZoneId.of("Australia/Hobart")).toLocalDate().toString()).isEqualTo("2025-06-30");
	}

}
