package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic adapter for CKAN-based open data portals, which back most Australian
 * government datasets (data.gov.au, data.qld.gov.au, data.nsw.gov.au,
 * data.vic.gov.au, ...). Reads rows through the {@code datastore_search} API
 * and maps columns to {@link CrimeRecord} using the configured field mapping.
 */
@Slf4j
public class CkanCrimeDataSource implements CrimeDataSource {

	private static final String DATASTORE_SEARCH = "/api/3/action/datastore_search";

	private final IngestionProperties.Source config;

	private final RestClient restClient;

	public CkanCrimeDataSource(IngestionProperties.Source config, RestClient.Builder restClientBuilder) {
		this.config = config;
		this.restClient = restClientBuilder.baseUrl(config.getBaseUrl()).build();
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
		JsonNode response;
		try {
			response = restClient.get()
					.uri(uriBuilder -> uriBuilder.path(DATASTORE_SEARCH)
							.queryParam("resource_id", config.getResourceId())
							.queryParam("limit", config.getBatchSize())
							.build())
					.retrieve()
					.body(JsonNode.class);
		}
		catch (Exception ex) {
			throw new IngestionException("Failed to call CKAN datastore for source " + name(), ex);
		}

		if (response == null || !response.path("success").asBoolean(false)) {
			throw new IngestionException("CKAN datastore returned an unsuccessful response for source " + name());
		}

		JsonNode rows = response.path("result").path("records");
		List<CrimeRecord> records = new ArrayList<>();
		for (JsonNode row : rows) {
			CrimeRecord record = mapRow(row);
			if (record != null) {
				records.add(record);
			}
		}
		log.info("Source {} returned {} records ({} mappable)", name(), rows.size(), records.size());
		return records;
	}

	private CrimeRecord mapRow(JsonNode row) {
		IngestionProperties.FieldMapping fields = config.getFields();

		String externalId = text(row, fields.getId());
		Instant occurredAt = parseDate(text(row, fields.getOccurredAt()));
		if (externalId == null || occurredAt == null) {
			log.debug("Skipping row without id or date from source {}", name());
			return null;
		}

		String title = text(row, fields.getTitle());
		String category = text(row, fields.getCategory());
		return new CrimeRecord(
				externalId,
				title != null ? title : category,
				text(row, fields.getDescription()),
				category,
				occurredAt,
				text(row, fields.getSuburb()),
				config.getState(),
				text(row, fields.getPostalCode()),
				doubleValue(row, fields.getLatitude()),
				doubleValue(row, fields.getLongitude()));
	}

	private String text(JsonNode row, String column) {
		if (column == null) {
			return null;
		}
		JsonNode value = row.path(column);
		return value.isMissingNode() || value.isNull() ? null : value.asText();
	}

	private Double doubleValue(JsonNode row, String column) {
		if (column == null) {
			return null;
		}
		JsonNode value = row.path(column);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		try {
			return Double.parseDouble(value.asText());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Instant parseDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		ZoneId zone = ZoneId.of(config.getZoneId());
		String pattern = config.getFields().getDateFormat();
		try {
			if (pattern != null) {
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
				try {
					return LocalDateTime.parse(raw, formatter).atZone(zone).toInstant();
				}
				catch (DateTimeParseException ex) {
					return LocalDate.parse(raw, formatter).atStartOfDay(zone).toInstant();
				}
			}
			try {
				return Instant.parse(raw);
			}
			catch (DateTimeParseException ex) {
				try {
					return LocalDateTime.parse(raw).atZone(zone).toInstant();
				}
				catch (DateTimeParseException ex2) {
					return LocalDate.parse(raw).atStartOfDay(zone).toInstant();
				}
			}
		}
		catch (DateTimeParseException ex) {
			log.debug("Unparseable date '{}' from source {}", raw, name());
			return null;
		}
	}

}
