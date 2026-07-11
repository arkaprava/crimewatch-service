package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.cache.NtDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.cache.NtDatasetCacheService.NtSeries;
import com.example.springgraphqlmongo.ingestion.geocode.NtGeographyResolver;
import com.example.springgraphqlmongo.ingestion.geocode.NtGeographyResolver.GeographyResolution;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import com.example.springgraphqlmongo.ingestion.period.ReportingPeriodResolver;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class NtCrimeStatisticsDataSource implements CrimeDataSource {

	private final IngestionProperties.Source config;

	private final IngestionProperties properties;

	private final NtDatasetCacheService cacheService;

	private final NtGeographyResolver geographyResolver;

	private final OffenceCategoryNormaliser offenceCategoryNormaliser;

	private final ReportingPeriodResolver reportingPeriodResolver;

	private volatile boolean refresh;

	public NtCrimeStatisticsDataSource(IngestionProperties.Source config, IngestionProperties properties,
			NtDatasetCacheService cacheService, NtGeographyResolver geographyResolver,
			OffenceCategoryNormaliser offenceCategoryNormaliser, ReportingPeriodResolver reportingPeriodResolver) {
		this.config = config;
		this.properties = properties;
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
		NtSeries series = resolveSeries();
		Path file = cacheService.resolveTimeseriesFile(series, refresh);
		YearMonth cutover = YearMonth.parse(properties.getNt().getSerproCutoverMonth());
		List<CrimeRecord> records = new ArrayList<>();
		TabularFileReader.readRows(file, config.getSheetNames(), row -> {
			CrimeRecord record = toRecord(row, series, cutover);
			if (record != null) {
				records.add(record);
			}
		});
		if (records.size() > config.getBatchSize() && config.getBatchSize() > 0) {
			return records.subList(0, config.getBatchSize());
		}
		log.info("Parsed {} NT {} aggregate records from {}", records.size(), series, file);
		return records;
	}

	private CrimeRecord toRecord(Map<String, String> row, NtSeries series, YearMonth cutover) {
		IngestionProperties.FieldMapping fields = config.getFields();
		String yearRaw = column(row, fields.getReportingPeriodYear(), "Year");
		String monthRaw = column(row, fields.getReportingPeriodMonth(), "Month number");
		String category = column(row, fields.getCategory(), "Offence category");
		String offence = column(row, fields.getTitle(), "Offence type");
		String region = column(row, fields.getSuburb(), "Reporting Region");
		String sa2 = column(row, fields.getGeographyLevel(), "Statistical Area 2");
		String countRaw = column(row, fields.getOffenceCount(), "Number of offences");
		String alcohol = column(row, fields.getAlcoholInvolvement(), "Alcohol involvement");
		String dv = column(row, fields.getDvInvolvement(), "DV involvement");
		if (yearRaw == null || monthRaw == null || offence == null || region == null || countRaw == null) {
			return null;
		}

		int year;
		int month;
		try {
			year = Integer.parseInt(yearRaw.replace(",", "").trim());
			month = Integer.parseInt(monthRaw.replace(",", "").trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
		if (month < 1 || month > 12) {
			return null;
		}

		YearMonth rowMonth = YearMonth.of(year, month);
		if (series == NtSeries.SERPRO && rowMonth.isBefore(cutover)) {
			return null;
		}
		if (series == NtSeries.PROMIS && !rowMonth.isBefore(cutover)) {
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

		String period = rowMonth.toString();
		GeographyResolution geography = geographyResolver.resolve(region, sa2);
		String normalisedOffence = offenceCategoryNormaliser.normalise(offence);
		String alcoholKey = normaliseContext(alcohol);
		String dvKey = normaliseContext(dv);
		String seriesKey = series == NtSeries.SERPRO ? "serpro" : "promis";
		String externalId = SaCrimeStatisticsDataSource.slugify("nt-" + seriesKey + "-" + period + "-"
				+ geography.canonicalName() + "-" + normalisedOffence + "-" + alcoholKey + "-" + dvKey);

		StringBuilder description = new StringBuilder("Aggregate: ")
				.append(offenceCount)
				.append(" offences in ")
				.append(period)
				.append(" (")
				.append(seriesKey.toUpperCase(Locale.ROOT))
				.append(" series");
		if (sa2 != null && !sa2.isBlank() && !"-".equals(sa2)) {
			description.append(", SA2 ").append(sa2);
		}
		description.append(")");
		if (alcohol != null && !"-".equals(alcohol)) {
			description.append("; alcohol=").append(alcohol);
		}
		if (dv != null && !"-".equals(dv)) {
			description.append("; DV=").append(dv);
		}

		return CrimeRecord.builder()
				.externalId(externalId)
				.title(normalisedOffence + " in " + geography.canonicalName())
				.description(description.toString())
				.category(category != null ? category : offence)
				.occurredAt(reportingPeriodResolver.resolveEnd(period, ZoneId.of(config.getZoneId())))
				.suburb(geography.canonicalName())
				.state(config.getState())
				.latitude(geography.latitude())
				.longitude(geography.longitude())
				.offenceCount(offenceCount)
				.reportingPeriod(period)
				.granularity(geography.granularity() != null ? geography.granularity()
						: RecordGranularity.DISTRICT_AGGREGATE)
				.geocodeStatus(geography.geocodeStatus() != null ? geography.geocodeStatus()
						: GeocodeStatus.UNRESOLVED)
				.suburbId(geography.suburbId())
				.boundary(geography.boundary())
				.build();
	}

	private NtSeries resolveSeries() {
		String series = config.getSeries();
		if (series != null && "promis".equalsIgnoreCase(series.trim())) {
			return NtSeries.PROMIS;
		}
		return NtSeries.SERPRO;
	}

	private static String column(Map<String, String> row, String configured, String fallback) {
		String value = TabularFileReader.columnValue(row, configured);
		if (value == null && fallback != null) {
			value = TabularFileReader.columnValue(row, fallback);
		}
		return value;
	}

	private static String normaliseContext(String value) {
		if (value == null || value.isBlank() || "-".equals(value)) {
			return "na";
		}
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
	}

}
