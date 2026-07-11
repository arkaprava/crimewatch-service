package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.cache.NswDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.geocode.AustralianSuburbGeocoder;
import com.example.springgraphqlmongo.ingestion.geocode.SuburbMatch;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NswBocsarStatisticsDataSourceTest {

	@Mock
	private NswDatasetCacheService cacheService;

	@Mock
	private AustralianSuburbGeocoder suburbGeocoder;

	private NswBocsarStatisticsDataSource dataSource;

	@BeforeEach
	void setUp() {
		IngestionProperties.Source config = new IngestionProperties.Source();
		config.setName("nsw-bocsar-statistics");
		config.setEnabled(true);
		config.setState("NSW");
		config.setZoneId("Australia/Sydney");
		config.getFields().setSuburb("Suburb");
		config.getFields().setCategory("Offence category");
		config.getFields().setTitle("Subcategory");

		dataSource = new NswBocsarStatisticsDataSource(config, cacheService, suburbGeocoder,
				new OffenceCategoryNormaliser());
	}

	@Test
	void parsesMonthHeader() {
		assertThat(NswBocsarStatisticsDataSource.parseMonthHeader("Jan 2024")).isEqualTo(YearMonth.of(2024, 1));
		assertThat(NswBocsarStatisticsDataSource.parseMonthHeader("Suburb")).isNull();
	}

	@Test
	void parsesFixtureCsvIntoAggregateRecords() {
		Path fixture = Path.of("data/nsw/crime-statistics/suburb-data-fixture.csv");
		when(cacheService.resolveSuburbDataFile(false)).thenReturn(fixture);
		when(suburbGeocoder.resolve(any(), eq("NSW"))).thenAnswer(invocation -> {
			String suburb = invocation.getArgument(0);
			AustralianSuburb resolved = AustralianSuburb.builder()
					.id("NSW:" + suburb.toUpperCase())
					.name(suburb)
					.state("NSW")
					.postcode("2000")
					.centroid(new GeoJsonPoint(151.0, -33.8))
					.build();
			return SuburbMatch.builder()
					.suburb(resolved)
					.status(GeocodeStatus.RESOLVED)
					.canonicalName(suburb)
					.build();
		});

		List<CrimeRecord> records = dataSource.fetchRecords();

		assertThat(records).hasSizeGreaterThanOrEqualTo(4);
		assertThat(records).anyMatch(record -> record.state().equals("NSW")
				&& record.granularity() == RecordGranularity.SUBURB_AGGREGATE
				&& record.offenceCount() != null
				&& record.offenceCount() > 0
				&& record.reportingPeriod().equals("2024-01")
				&& record.suburb().equals("Parramatta"));
		assertThat(records).noneMatch(record -> record.offenceCount() == 0);
	}

	@Test
	void assignsDistinctExternalIdsPerOffenceSubcategoryAndMonth() {
		Path fixture = Path.of("data/nsw/crime-statistics/suburb-data-fixture.csv");
		when(cacheService.resolveSuburbDataFile(false)).thenReturn(fixture);
		when(suburbGeocoder.resolve(any(), eq("NSW"))).thenAnswer(invocation -> {
			String suburb = invocation.getArgument(0);
			AustralianSuburb resolved = AustralianSuburb.builder()
					.id("NSW:" + suburb.toUpperCase())
					.name(suburb)
					.state("NSW")
					.postcode("2000")
					.centroid(new GeoJsonPoint(151.0, -33.8))
					.build();
			return SuburbMatch.builder()
					.suburb(resolved)
					.status(GeocodeStatus.RESOLVED)
					.canonicalName(suburb)
					.build();
		});

		List<CrimeRecord> records = dataSource.fetchRecords();
		List<CrimeRecord> parramattaJan2024 = records.stream()
				.filter(record -> "Parramatta".equals(record.suburb()) && "2024-01".equals(record.reportingPeriod()))
				.toList();

		assertThat(parramattaJan2024).hasSize(2);
		assertThat(parramattaJan2024.stream().map(CrimeRecord::externalId).distinct().count()).isEqualTo(2);
		assertThat(parramattaJan2024).anyMatch(record -> record.offenceCount() == 5);
		assertThat(parramattaJan2024).anyMatch(record -> record.offenceCount() == 4);
	}

}
