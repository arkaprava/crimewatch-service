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
import com.example.springgraphqlmongo.ingestion.source.tas.TasCorporatePerformancePdfParser;
import com.example.springgraphqlmongo.ingestion.source.tas.TasCorporatePerformanceRow;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
public class TasCorporatePerformanceDataSource implements CrimeDataSource {

	private static final DateTimeFormatter REPORT_PERIOD = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

	private final IngestionProperties.Source config;

	private final TasDatasetCacheService cacheService;

	private final TasGeographyResolver geographyResolver;

	private final OffenceCategoryNormaliser offenceCategoryNormaliser;

	private volatile boolean refresh;

	public TasCorporatePerformanceDataSource(IngestionProperties.Source config, TasDatasetCacheService cacheService,
			TasGeographyResolver geographyResolver, OffenceCategoryNormaliser offenceCategoryNormaliser) {
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
		Path pdf = cacheService.resolveCorporatePerformancePdf(refresh);
		List<TasCorporatePerformanceRow> rows = TasCorporatePerformancePdfParser.parse(pdf);
		List<CrimeRecord> records = new ArrayList<>();
		ZoneId zone = ZoneId.of(config.getZoneId());
		for (TasCorporatePerformanceRow row : rows) {
			CrimeRecord record = toRecord(row, zone);
			if (record != null) {
				records.add(record);
			}
		}
		if (records.size() > config.getBatchSize() && config.getBatchSize() > 0) {
			return records.subList(0, config.getBatchSize());
		}
		log.info("Mapped {} TAS corporate performance records from {}", records.size(), pdf);
		return records;
	}

	private CrimeRecord toRecord(TasCorporatePerformanceRow row, ZoneId zone) {
		if (row.count() <= 0) {
			return null;
		}
		GeographyResolution geography = geographyResolver.resolve(row.geographyLevel(), row.geographyName());
		if (geography.geocodeStatus() == GeocodeStatus.UNRESOLVED || geography.latitude() == null) {
			return null;
		}
		String normalisedOffence = offenceCategoryNormaliser.normalise(row.offenceType());
		String externalId = SaCrimeStatisticsDataSource.slugify("tas-cpr-" + row.reportPeriod() + "-"
				+ row.geographyName() + "-" + row.offenceSection() + "-" + normalisedOffence);
		Instant occurredAt = parseReportPeriodEnd(row.reportPeriod(), zone);
		String title = normalisedOffence + " in " + geography.canonicalName();
		return CrimeRecord.builder()
				.externalId(externalId)
				.title(title)
				.description("Corporate performance aggregate: " + row.count() + " offences in " + row.reportPeriod()
						+ " (" + row.offenceSection() + ")")
				.category(row.offenceSection())
				.occurredAt(occurredAt)
				.suburb(geography.canonicalName())
				.state(config.getState())
				.latitude(geography.latitude())
				.longitude(geography.longitude())
				.offenceCount(row.count())
				.reportingPeriod(row.reportPeriod())
				.granularity(geography.granularity() != null ? geography.granularity()
						: RecordGranularity.DISTRICT_AGGREGATE)
				.geocodeStatus(geography.geocodeStatus())
				.suburbId(geography.suburbId())
				.boundary(geography.boundary())
				.build();
	}

	static Instant parseReportPeriodEnd(String reportPeriod, ZoneId zone) {
		try {
			YearMonth yearMonth = YearMonth.parse(reportPeriod, REPORT_PERIOD);
			return yearMonth.atEndOfMonth().atStartOfDay(zone).toInstant();
		}
		catch (DateTimeParseException ex) {
			return Instant.now();
		}
	}

}
