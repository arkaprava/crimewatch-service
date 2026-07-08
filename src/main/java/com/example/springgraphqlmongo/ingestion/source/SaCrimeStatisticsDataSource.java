package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.domain.SaOffenderContext;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.example.springgraphqlmongo.ingestion.cache.DatasetTarArchive;
import com.example.springgraphqlmongo.ingestion.cache.SaDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.geocode.AustralianSuburbGeocoder;
import com.example.springgraphqlmongo.ingestion.geocode.SuburbMatch;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SaCrimeStatisticsDataSource implements CrimeDataSource {

	private static final Pattern FINANCIAL_YEAR = Pattern.compile("(\\d{4})-(\\d{2,4})");

	private final IngestionProperties.Source config;

	private final IngestionProperties properties;

	private final SaDatasetCacheService cacheService;

	private final SaOffenderReferenceLoader offenderReferenceLoader;

	private final AustralianSuburbGeocoder suburbGeocoder;

	private final OffenceCategoryNormaliser offenceCategoryNormaliser;

	private volatile boolean refresh;

	public SaCrimeStatisticsDataSource(IngestionProperties.Source config, IngestionProperties properties,
			SaDatasetCacheService cacheService, SaOffenderReferenceLoader offenderReferenceLoader,
			AustralianSuburbGeocoder suburbGeocoder, OffenceCategoryNormaliser offenceCategoryNormaliser) {
		this.config = config;
		this.properties = properties;
		this.cacheService = cacheService;
		this.offenderReferenceLoader = offenderReferenceLoader;
		this.suburbGeocoder = suburbGeocoder;
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
		Map<String, SaOffenderStats> offenderStats = offenderReferenceLoader.loadReference(refresh);
		List<CrimeRecord> records = new ArrayList<>();
		List<String> resources = config.getResourceNames();
		if (resources == null || resources.isEmpty()) {
			throw new IngestionException("No SA crime statistics resources configured for " + name());
		}
		for (String resourceName : resources) {
			Path file = cacheService.resolveCrimeStatisticsFile(resourceName, refresh);
			records.addAll(parseCrimeStatistics(file, offenderStats));
		}
		if (records.size() > config.getBatchSize() && config.getBatchSize() > 0) {
			return records.subList(0, config.getBatchSize());
		}
		return records;
	}

	private List<CrimeRecord> parseCrimeStatistics(Path file, Map<String, SaOffenderStats> offenderStats) {
		IngestionProperties.FieldMapping fields = config.getFields();
		List<CrimeRecord> records = new ArrayList<>();
		try (BufferedReader reader = DatasetTarArchive.openCsvReader(file)) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				return records;
			}
			Map<String, Integer> columns = mapColumns(SaOffenderReferenceLoader.parseCsvLine(headerLine));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] values = SaOffenderReferenceLoader.parseCsvLine(line);
				CrimeRecord record = toRecord(fields, columns, values, offenderStats);
				if (record != null) {
					records.add(record);
				}
			}
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to parse SA crime statistics file " + file, ex);
		}
		log.info("Parsed {} SA aggregate records from {}", records.size(), file);
		return records;
	}

	private CrimeRecord toRecord(IngestionProperties.FieldMapping fields, Map<String, Integer> columns,
			String[] values, Map<String, SaOffenderStats> offenderStats) {
		String suburb = firstColumn(columns, values, fields.getSuburb(), "Suburb - Incident", "Suburb");
		String offence = firstColumn(columns, values, fields.getTitle(), "Offence Level 3 Description",
				"Offence Description");
		String category = firstColumn(columns, values, fields.getCategory(), "Offence Level 1 Description",
				"Offence Division");
		String period = firstColumn(columns, values, fields.getReportingPeriod(), "Financial Year", "Reported Date");
		String countRaw = firstColumn(columns, values, fields.getOffenceCount(), "Offence count", "Count");
		if (suburb == null || offence == null || period == null || countRaw == null) {
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

		Instant occurredAt = resolveOccurredAt(period, fields.getDateFormat());
		if (occurredAt == null) {
			return null;
		}

		String normalisedOffence = offenceCategoryNormaliser.normalise(offence);
		String externalId = slugify("sa-" + period + "-" + suburb + "-" + normalisedOffence);
		SuburbMatch suburbMatch = suburbGeocoder.resolve(suburb, config.getState());

		Double latitude = null;
		Double longitude = null;
		String suburbId = null;
		var boundary = suburbMatch.suburb() != null ? suburbMatch.suburb().getPerimeter() : null;
		GeocodeStatus geocodeStatus = suburbMatch.status();
		if (suburbMatch.suburb() != null && suburbMatch.suburb().getCentroid() != null) {
			GeoJsonPoint centroid = suburbMatch.suburb().getCentroid();
			latitude = centroid.getY();
			longitude = centroid.getX();
			suburbId = suburbMatch.suburb().getId();
		}

		SaOffenderStats offender = offenderStats.get(offenceCategoryNormaliser.correlationKey(config.getState(),
				period, offence));
		SaOffenderContext offenderContext = null;
		if (offender != null) {
			offenderContext = SaOffenderContext.builder()
					.offenderCount(offender.offenderCount())
					.principalOffence(offender.principalOffence())
					.correlationNote("Matched at SA state level for " + period)
					.build();
		}

		String canonicalSuburb = suburbMatch.canonicalName() != null ? suburbMatch.canonicalName() : suburb;
		return CrimeRecord.builder()
				.externalId(externalId)
				.title(normalisedOffence + " in " + canonicalSuburb)
				.description("Aggregate: " + offenceCount + " offences reported in " + period)
				.category(category != null ? category : offence)
				.occurredAt(occurredAt)
				.suburb(canonicalSuburb)
				.state(config.getState())
				.postalCode(suburbMatch.suburb() != null ? suburbMatch.suburb().getPostcode() : null)
				.latitude(latitude)
				.longitude(longitude)
				.offenceCount(offenceCount)
				.reportingPeriod(period)
				.granularity(RecordGranularity.SUBURB_AGGREGATE)
				.geocodeStatus(geocodeStatus)
				.suburbId(suburbId)
				.boundary(boundary)
				.offenderContext(offenderContext)
				.build();
	}

	private Instant financialYearEnd(String period) {
		Matcher matcher = FINANCIAL_YEAR.matcher(period);
		if (!matcher.matches()) {
			return null;
		}
		int endYear = Integer.parseInt(matcher.group(2));
		if (endYear < 100) {
			endYear += 2000;
		}
		ZoneId zone = ZoneId.of(config.getZoneId());
		return ZonedDateTime.of(endYear, 6, 30, 12, 0, 0, 0, zone).toInstant();
	}

	private Instant resolveOccurredAt(String period, String dateFormat) {
		Instant financialYear = financialYearEnd(period);
		if (financialYear != null) {
			return financialYear;
		}
		ZoneId zone = ZoneId.of(config.getZoneId());
		try {
			if (dateFormat != null && !dateFormat.isBlank()) {
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);
				try {
					return LocalDate.parse(period, formatter).atStartOfDay(zone).toInstant();
				}
				catch (DateTimeParseException ex) {
					return LocalDateTime.parse(period, formatter).atZone(zone).toInstant();
				}
			}
			return LocalDate.parse(period, DateTimeFormatter.ofPattern("dd/MM/yyyy")).atStartOfDay(zone).toInstant();
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private String firstColumn(Map<String, Integer> columns, String[] values, String... candidates) {
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			String value = columnValue(columns, values, candidate);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private String columnValue(Map<String, Integer> columns, String[] values, String column) {
		if (column == null) {
			return null;
		}
		Integer index = columns.get(normaliseHeader(column));
		if (index == null || index >= values.length) {
			return null;
		}
		String value = values[index];
		return value == null ? null : value.replace("\"", "").trim();
	}

	private Map<String, Integer> mapColumns(String[] headers) {
		Map<String, Integer> columns = new HashMap<>();
		for (int i = 0; i < headers.length; i++) {
			columns.put(normaliseHeader(headers[i]), i);
		}
		return columns;
	}

	private String normaliseHeader(String header) {
		return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	static String slugify(String value) {
		String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
		int start = 0;
		int end = slug.length();
		while (start < end && slug.charAt(start) == '-') {
			start++;
		}
		while (end > start && slug.charAt(end - 1) == '-') {
			end--;
		}
		return slug.substring(start, end);
	}

}
