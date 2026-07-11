package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.cache.NtDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.cache.NtDatasetCacheService.NtSeries;
import com.example.springgraphqlmongo.ingestion.geocode.NtGeographyResolver;
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
class NtCrimeStatisticsDataSourceTest {

	@Mock
	private NtDatasetCacheService cacheService;

	@Mock
	private NtGeographyResolver geographyResolver;

	private IngestionProperties properties;

	private NtCrimeStatisticsDataSource serproSource;

	private NtCrimeStatisticsDataSource promisSource;

	@BeforeEach
	void setUp() {
		properties = new IngestionProperties();
		properties.getNt().setSerproCutoverMonth("2023-12");

		IngestionProperties.Source serproConfig = baseConfig("nt-police-crime-statistics", "serpro");
		IngestionProperties.Source promisConfig = baseConfig("nt-police-crime-statistics-historical", "promis");

		OffenceCategoryNormaliser normaliser = new OffenceCategoryNormaliser();
		ReportingPeriodResolver periodResolver = new ReportingPeriodResolver();
		serproSource = new NtCrimeStatisticsDataSource(serproConfig, properties, cacheService, geographyResolver,
				normaliser, periodResolver);
		promisSource = new NtCrimeStatisticsDataSource(promisConfig, properties, cacheService, geographyResolver,
				normaliser, periodResolver);

		when(geographyResolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenAnswer(invocation -> {
					String region = invocation.getArgument(0);
					String sa2 = invocation.getArgument(1);
					String name = sa2 != null && !sa2.isBlank() ? sa2 : region;
					return new NtGeographyResolver.GeographyResolution(name, RecordGranularity.DISTRICT_AGGREGATE,
							com.example.springgraphqlmongo.domain.GeocodeStatus.APPROXIMATE, -12.463, 130.841,
							"NT:REGION:" + name.toUpperCase().replace(' ', '-'), null);
				});
	}

	@Test
	void parsesSerproFixtureRowsAfterCutover() {
		Path fixture = Path.of("data/nt/crime-statistics/nt-crime-statistics-serpro-fixture.csv");
		when(cacheService.resolveTimeseriesFile(NtSeries.SERPRO, false)).thenReturn(fixture);

		List<CrimeRecord> records = serproSource.fetchRecords();

		assertThat(records).hasSize(4);
		assertThat(records).allMatch(record -> record.state().equals("NT"));
		assertThat(records).allMatch(record -> record.externalId().startsWith("nt-serpro-"));
		assertThat(records).anyMatch(record -> record.reportingPeriod().equals("2023-12"));
		assertThat(records).noneMatch(record -> record.reportingPeriod().equals("2022-06"));
	}

	@Test
	void parsesPromisFixtureRowsBeforeCutover() {
		Path fixture = Path.of("data/nt/crime-statistics/nt-crime-statistics-promis-fixture.csv");
		when(cacheService.resolveTimeseriesFile(NtSeries.PROMIS, false)).thenReturn(fixture);

		List<CrimeRecord> records = promisSource.fetchRecords();

		assertThat(records).hasSize(4);
		assertThat(records).allMatch(record -> record.externalId().startsWith("nt-promis-"));
		assertThat(records).anyMatch(record -> record.reportingPeriod().equals("2022-06"));
		assertThat(records).noneMatch(record -> record.reportingPeriod().equals("2023-12"));
	}

	private IngestionProperties.Source baseConfig(String name, String series) {
		IngestionProperties.Source config = new IngestionProperties.Source();
		config.setName(name);
		config.setEnabled(true);
		config.setState("NT");
		config.setZoneId("Australia/Darwin");
		config.setSeries(series);
		config.getFields().setReportingPeriodYear("Year");
		config.getFields().setReportingPeriodMonth("Month number");
		config.getFields().setCategory("Offence category");
		config.getFields().setTitle("Offence type ");
		config.getFields().setSuburb("Reporting Region");
		config.getFields().setGeographyLevel("Statistical Area 2");
		config.getFields().setOffenceCount("Number of offences");
		config.getFields().setAlcoholInvolvement("Alcohol involvement");
		config.getFields().setDvInvolvement("DV involvement");
		return config;
	}

}
