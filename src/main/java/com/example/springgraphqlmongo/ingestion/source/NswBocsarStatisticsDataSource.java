package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.example.springgraphqlmongo.ingestion.cache.NswDatasetCacheService;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class NswBocsarStatisticsDataSource implements CrimeDataSource {

	private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

	private final IngestionProperties.Source config;

	private final NswDatasetCacheService cacheService;

	private final AustralianSuburbGeocoder suburbGeocoder;

	private final OffenceCategoryNormaliser offenceCategoryNormaliser;

	private volatile boolean refresh;

	public NswBocsarStatisticsDataSource(IngestionProperties.Source config, NswDatasetCacheService cacheService,
			AustralianSuburbGeocoder suburbGeocoder, OffenceCategoryNormaliser offenceCategoryNormaliser) {
		this.config = config;
		this.cacheService = cacheService;
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
		Path file = cacheService.resolveSuburbDataFile(refresh);
		List<CrimeRecord> records = parseWideFormatCsv(file);
		if (records.size() > config.getBatchSize() && config.getBatchSize() > 0) {
			return records.subList(0, config.getBatchSize());
		}
		return records;
	}

	private List<CrimeRecord> parseWideFormatCsv(Path file) {
		IngestionProperties.FieldMapping fields = config.getFields();
		List<CrimeRecord> records = new ArrayList<>();
		ZoneId zone = ZoneId.of(config.getZoneId());
		int batchLimit = config.getBatchSize() > 0 ? config.getBatchSize() : Integer.MAX_VALUE;

		try (BufferedReader reader = Files.newBufferedReader(file)) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				return records;
			}
			String[] headers = SaOffenderReferenceLoader.parseCsvLine(headerLine);
			Map<String, Integer> columns = mapColumns(headers);
			List<MonthColumn> monthColumns = monthColumns(headers);

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] values = SaOffenderReferenceLoader.parseCsvLine(line);
				String suburb = columnValue(columns, values, fields.getSuburb());
				String category = columnValue(columns, values, fields.getCategory());
				String offence = columnValue(columns, values, fields.getTitle());
				if (suburb == null || offence == null) {
					continue;
				}

				SuburbMatch suburbMatch = suburbGeocoder.resolve(suburb, config.getState());
				String canonicalSuburb = suburbMatch.canonicalName() != null ? suburbMatch.canonicalName() : suburb;
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

				String normalisedOffence = offenceCategoryNormaliser.normalise(offence);
				for (MonthColumn monthColumn : monthColumns) {
					if (records.size() >= batchLimit) {
						log.info("Reached NSW batch limit of {} records", batchLimit);
						return records;
					}
					String countRaw = valueAt(values, monthColumn.index());
					if (countRaw == null || countRaw.isBlank()) {
						continue;
					}
					int offenceCount;
					try {
						offenceCount = (int) Math.round(Double.parseDouble(countRaw.replace(",", "")));
					}
					catch (NumberFormatException ex) {
						continue;
					}
					if (offenceCount <= 0) {
						continue;
					}

					String reportingPeriod = monthColumn.yearMonth().toString();
					String externalId = SaCrimeStatisticsDataSource.slugify(
							"nsw-" + reportingPeriod + "-" + canonicalSuburb + "-" + normalisedOffence);
					Instant occurredAt = monthColumn.yearMonth().atEndOfMonth().atTime(12, 0).atZone(zone).toInstant();

					records.add(CrimeRecord.builder()
							.externalId(externalId)
							.title(normalisedOffence + " in " + canonicalSuburb)
							.description("Aggregate: " + offenceCount + " offences reported in "
									+ monthColumn.label()
									+ (category != null ? " (" + category + ")" : ""))
							.category(category != null ? category : offence)
							.occurredAt(occurredAt)
							.suburb(canonicalSuburb)
							.state(config.getState())
							.postalCode(suburbMatch.suburb() != null ? suburbMatch.suburb().getPostcode() : null)
							.latitude(latitude)
							.longitude(longitude)
							.offenceCount(offenceCount)
							.reportingPeriod(reportingPeriod)
							.granularity(RecordGranularity.SUBURB_AGGREGATE)
							.geocodeStatus(geocodeStatus)
							.suburbId(suburbId)
							.boundary(boundary)
							.build());
				}
			}
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to parse NSW BOCSAR suburb data file " + file, ex);
		}
		log.info("Parsed {} NSW aggregate records from {}", records.size(), file);
		return records;
	}

	private List<MonthColumn> monthColumns(String[] headers) {
		List<MonthColumn> monthColumns = new ArrayList<>();
		for (int i = 0; i < headers.length; i++) {
			YearMonth yearMonth = parseMonthHeader(headers[i]);
			if (yearMonth != null) {
				monthColumns.add(new MonthColumn(i, yearMonth, headers[i].trim()));
			}
		}
		return monthColumns;
	}

	static YearMonth parseMonthHeader(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		try {
			return YearMonth.parse(header.trim().replace("\"", ""), MONTH_YEAR);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private String columnValue(Map<String, Integer> columns, String[] values, String column) {
		if (column == null) {
			return null;
		}
		Integer index = columns.get(normaliseHeader(column));
		return valueAt(values, index);
	}

	private String valueAt(String[] values, Integer index) {
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

	private record MonthColumn(int index, YearMonth yearMonth, String label) {
	}

}
