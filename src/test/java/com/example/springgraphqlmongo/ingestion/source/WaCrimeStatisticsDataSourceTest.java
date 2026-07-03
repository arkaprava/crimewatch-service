package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.cache.WaDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.geocode.WaGeographyResolver;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import com.example.springgraphqlmongo.ingestion.period.ReportingPeriodResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaCrimeStatisticsDataSourceTest {

	@Mock
	private WaDatasetCacheService cacheService;

	@Mock
	private WaGeographyResolver geographyResolver;

	private WaCrimeStatisticsDataSource dataSource;

	@BeforeEach
	void setUp() {
		IngestionProperties.Source config = new IngestionProperties.Source();
		config.setName("wa-police-crime-statistics");
		config.setEnabled(true);
		config.setState("WA");
		config.setZoneId("Australia/Perth");
		config.getFields().setGeographyLevel("Geography Level");
		config.getFields().setSuburb("Location");
		config.getFields().setTitle("Offence Description");
		config.getFields().setCategory("Offence Group");
		config.getFields().setOffenceCount("Count");
		config.getFields().setReportingPeriod("Period");

		dataSource = new WaCrimeStatisticsDataSource(config, cacheService, geographyResolver,
				new OffenceCategoryNormaliser(), new ReportingPeriodResolver());
	}

	@Test
	void parsesFixtureCsvIntoAggregateRecords() {
		Path fixture = Path.of("data/wa/crime-statistics/crime-timeseries.csv");
		when(cacheService.resolveCrimeTimeseriesFile(false)).thenReturn(fixture);
		when(geographyResolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.eq("WA")))
				.thenAnswer(invocation -> {
					String level = invocation.getArgument(0);
					String location = invocation.getArgument(1);
					if ("District".equalsIgnoreCase(level)) {
						return new WaGeographyResolver.GeographyResolution(location, RecordGranularity.DISTRICT_AGGREGATE,
								com.example.springgraphqlmongo.domain.GeocodeStatus.APPROXIMATE, -32.056, 115.755,
								"WA:DISTRICT:FREMANTLE", null);
					}
					return new WaGeographyResolver.GeographyResolution(location, RecordGranularity.SUBURB_AGGREGATE,
							com.example.springgraphqlmongo.domain.GeocodeStatus.RESOLVED, -31.9505, 115.8605,
							"WA:PERTH", null);
				});

		List<CrimeRecord> records = dataSource.fetchRecords();

		assertThat(records).isNotEmpty();
		assertThat(records).anyMatch(record -> record.state().equals("WA")
				&& record.offenceCount() != null
				&& record.offenceCount() > 0
				&& record.reportingPeriod().equals("2025-Q1"));
	}

}
