package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.example.springgraphqlmongo.ingestion.cache.WaDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.geocode.WaGeographyResolver;
import com.example.springgraphqlmongo.ingestion.geocode.WaGeographyResolver.GeographyResolution;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import com.example.springgraphqlmongo.ingestion.period.ReportingPeriodResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class WaCrimeStatisticsDataSource implements CrimeDataSource {

	private final IngestionProperties.Source config;

	private final WaDatasetCacheService cacheService;

	private final WaGeographyResolver geographyResolver;

	private final OffenceCategoryNormaliser offenceCategoryNormaliser;

	private final ReportingPeriodResolver reportingPeriodResolver;

	private volatile boolean refresh;

	public WaCrimeStatisticsDataSource(IngestionProperties.Source config, WaDatasetCacheService cacheService,
			WaGeographyResolver geographyResolver, OffenceCategoryNormaliser offenceCategoryNormaliser,
			ReportingPeriodResolver reportingPeriodResolver) {
		this.config = config;
		this.cacheService = cacheService;
		this.geographyResolver = geographyResolver;
		this.offenceCategoryNormaliser = offenceCategoryNormaliser;
		this.reportingPeriodResolver = reportingPeriodResolver;
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
		Path file = cacheService.resolveCrimeTimeseriesFile(refresh);
		List<CrimeRecord> records = new ArrayList<>();
		TabularFileReader.readRows(file, config.getSheetNames(), row -> {
			CrimeRecord record = toRecord(row);
			if (record != null) {
				records.add(record);
			}
		});
		if (records.size() > config.getBatchSize() && config.getBatchSize() > 0) {
			return records.subList(0, config.getBatchSize());
		}
		log.info("Parsed {} WA aggregate records from {}", records.size(), file);
		return records;
	}

	private CrimeRecord toRecord(Map<String, String> row) {
		IngestionProperties.FieldMapping fields = config.getFields();
		String geographyLevel = TabularFileReader.columnValue(row, fields.getGeographyLevel());
		String location = TabularFileReader.columnValue(row, fields.getSuburb());
		String offence = TabularFileReader.columnValue(row, fields.getTitle());
		String category = TabularFileReader.columnValue(row, fields.getCategory());
		String period = TabularFileReader.columnValue(row, fields.getReportingPeriod());
		String countRaw = TabularFileReader.columnValue(row, fields.getOffenceCount());
		if (location == null || offence == null || period == null || countRaw == null) {
			return null;
		}

		int offenceCount;
		try {
			offenceCount = (int) Math.round(Double.parseDouble(countRaw.replace(",", "")));
		}
		catch (NumberFormatException ex) {
			return null;
		}
		if (offenceCount <= 0) {
			return null;
		}

		GeographyResolution geography = geographyResolver.resolve(geographyLevel, location, config.getState());
		String normalisedOffence = offenceCategoryNormaliser.normalise(offence);
		String externalId = SaCrimeStatisticsDataSource.slugify(
				"wa-" + period + "-" + geography.canonicalName() + "-" + normalisedOffence);

		return CrimeRecord.builder()
				.externalId(externalId)
				.title(normalisedOffence + " in " + geography.canonicalName())
				.description("Aggregate: " + offenceCount + " offences reported in " + period
						+ (geographyLevel != null ? " (" + geographyLevel + ")" : ""))
				.category(category != null ? category : offence)
				.occurredAt(reportingPeriodResolver.resolveEnd(period, ZoneId.of(config.getZoneId())))
				.suburb(geography.canonicalName())
				.state(config.getState())
				.latitude(geography.latitude())
				.longitude(geography.longitude())
				.offenceCount(offenceCount)
				.reportingPeriod(period)
				.granularity(geography.granularity() != null ? geography.granularity()
						: RecordGranularity.SUBURB_AGGREGATE)
				.geocodeStatus(geography.geocodeStatus() != null ? geography.geocodeStatus()
						: GeocodeStatus.UNRESOLVED)
				.suburbId(geography.suburbId())
				.boundary(geography.boundary())
				.build();
	}

}
