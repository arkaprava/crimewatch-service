package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.cache.TasDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.geocode.TasGeographyResolver;
import com.example.springgraphqlmongo.ingestion.geocode.TasGeographyResolver.GeographyResolution;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import com.example.springgraphqlmongo.ingestion.source.tas.TasSupplementPdfParser;
import com.example.springgraphqlmongo.ingestion.source.tas.TasSupplementRow;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class TasCrimeStatisticsSupplementDataSource implements CrimeDataSource {

	private final IngestionProperties.Source config;

	private final TasDatasetCacheService cacheService;

	private final TasGeographyResolver geographyResolver;

	private final OffenceCategoryNormaliser offenceCategoryNormaliser;

	private volatile boolean refresh;

	public TasCrimeStatisticsSupplementDataSource(IngestionProperties.Source config,
			TasDatasetCacheService cacheService, TasGeographyResolver geographyResolver,
			OffenceCategoryNormaliser offenceCategoryNormaliser) {
		this.config = config;
		this.cacheService = cacheService;
		this.geographyResolver = geographyResolver;
		this.offenceCategoryNormaliser = offenceCategoryNormaliser;
	}

	public void setRefresh(boolean refresh) {
		this.refresh = refresh;
	}

	@Override
	public String name() {
		return config.getName();
	}

	@Override
	public boolean isEnabled() {
		return config.isEnabled();
	}

	@Override
	public List<CrimeRecord> fetchRecords() {
		Path pdf = cacheService.resolveSupplementPdf(refresh);
		List<TasSupplementRow> rows = TasSupplementPdfParser.parse(pdf);
		List<CrimeRecord> records = new ArrayList<>();
		GeographyResolution geography = geographyResolver.stateResolution();
		ZoneId zone = ZoneId.of(config.getZoneId());
		for (TasSupplementRow row : rows) {
			CrimeRecord record = toRecord(row, geography, zone);
			if (record != null) {
				records.add(record);
			}
		}
		if (records.size() > config.getBatchSize() && config.getBatchSize() > 0) {
			return records.subList(0, config.getBatchSize());
		}
		log.info("Mapped {} TAS supplement records from {}", records.size(), pdf);
		return records;
	}

	private CrimeRecord toRecord(TasSupplementRow row, GeographyResolution geography, ZoneId zone) {
		if (row.recorded() <= 0) {
			return null;
		}
		String normalisedOffence = offenceCategoryNormaliser.normalise(row.offence());
		String externalId = SaCrimeStatisticsDataSource.slugify(
				"tas-supplement-" + row.financialYear() + "-state-" + normalisedOffence);
		Instant occurredAt = financialYearEnd(row.financialYear(), zone);
		String description = "Aggregate: " + row.recorded() + " offences recorded in " + row.financialYear();
		if (row.cleared() != null) {
			description += " (" + row.cleared() + " cleared)";
		}
		return CrimeRecord.builder()
				.externalId(externalId)
				.title(normalisedOffence + " in Tasmania")
				.description(description)
				.category(row.category())
				.occurredAt(occurredAt)
				.suburb(geography.canonicalName())
				.state(config.getState())
				.latitude(geography.latitude())
				.longitude(geography.longitude())
				.offenceCount(row.recorded())
				.reportingPeriod(row.financialYear())
				.granularity(RecordGranularity.STATE_AGGREGATE)
				.geocodeStatus(geography.geocodeStatus() != null ? geography.geocodeStatus() : GeocodeStatus.RESOLVED)
				.suburbId(geography.suburbId())
				.boundary(geography.boundary())
				.build();
	}

	static Instant financialYearEnd(String financialYear, ZoneId zone) {
		int startYear = Integer.parseInt(financialYear.substring(0, 4));
		return Instant.from(java.time.ZonedDateTime.of(startYear + 1, 6, 30, 0, 0, 0, 0, zone));
	}

}
