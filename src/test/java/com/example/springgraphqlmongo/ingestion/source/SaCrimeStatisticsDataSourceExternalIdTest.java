package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.cache.SaDatasetCacheService;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SaCrimeStatisticsDataSourceExternalIdTest {

	@Mock
	private IngestionProperties properties;

	@Mock
	private SaDatasetCacheService cacheService;

	@Mock
	private SaOffenderReferenceLoader offenderReferenceLoader;

	@Mock
	private AustralianSuburbGeocoder suburbGeocoder;

	private SaCrimeStatisticsDataSource dataSource;

	@BeforeEach
	void setUp() {
		IngestionProperties.Source config = new IngestionProperties.Source();
		config.setName("sa-police-crime-statistics");
		config.setEnabled(true);
		config.setState("SA");
		config.setZoneId("Australia/Adelaide");
		config.setResourceNames(List.of("Crime Statistics 2024-25"));
		config.getFields().setSuburb("Suburb");
		config.getFields().setTitle("Offence Description");
		config.getFields().setCategory("Offence Division");
		config.getFields().setOffenceCount("Count");
		config.getFields().setReportingPeriod("Financial Year");
		config.getFields().setDateFormat("dd/MM/yyyy");

		when(offenderReferenceLoader.loadReference(false)).thenReturn(java.util.Map.of());
		when(suburbGeocoder.resolve(any(), eq("SA"))).thenAnswer(invocation -> {
			String suburb = invocation.getArgument(0);
			AustralianSuburb resolved = AustralianSuburb.builder()
					.id("SA:" + suburb.toUpperCase())
					.name(suburb)
					.state("SA")
					.postcode("5000")
					.centroid(new GeoJsonPoint(138.6, -34.9))
					.build();
			return SuburbMatch.builder()
					.suburb(resolved)
					.status(GeocodeStatus.RESOLVED)
					.canonicalName(suburb)
					.build();
		});

		dataSource = new SaCrimeStatisticsDataSource(config, properties, cacheService, offenderReferenceLoader,
				suburbGeocoder, new OffenceCategoryNormaliser());
	}

	@Test
	void assignsDistinctExternalIdsPerOffenceSubcategoryOnSameDay() {
		Path fixture = Path.of("src/test/resources/sa/adelaide-0107-2024-slice.csv");
		when(cacheService.resolveCrimeStatisticsFile("Crime Statistics 2024-25", false)).thenReturn(fixture);

		List<CrimeRecord> records = dataSource.fetchRecords();

		assertThat(records).hasSize(3);
		Set<String> externalIds = records.stream().map(CrimeRecord::externalId).collect(Collectors.toSet());
		assertThat(externalIds).hasSize(3);
		assertThat(records).anyMatch(record -> record.offenceCount() == 4
				&& record.externalId().contains("common-assault"));
		assertThat(records).anyMatch(record -> record.offenceCount() == 2
				&& record.externalId().contains("other-theft"));
		assertThat(records).anyMatch(record -> record.offenceCount() == 4
				&& record.externalId().contains("theft-from-shop"));
	}

}
