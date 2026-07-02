package com.example.springgraphqlmongo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for Australian crime data ingestion. Sources backed by CKAN
 * open data portals (data.gov.au, data.qld.gov.au, data.nsw.gov.au, ...) are
 * fully configuration-driven; custom providers can be added by implementing
 * {@code CrimeDataSource} as a Spring bean.
 */
@Data
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

	private boolean enabled = true;

	private Schedule schedule = new Schedule();

	private List<Source> sources = new ArrayList<>();

	@Data
	public static class Schedule {

		private boolean enabled = false;

		/** Default: daily at 03:00. */
		private String cron = "0 0 3 * * *";

	}

	@Data
	public static class Source {

		private String name;

		private boolean enabled = false;

		/** Portal root, e.g. https://www.data.qld.gov.au */
		private String baseUrl;

		/** CKAN datastore resource id of the dataset. */
		private String resourceId;

		/** State/territory the dataset covers, e.g. QLD, NSW, VIC. */
		private String state;

		/** Max records fetched per run. */
		private int batchSize = 500;

		/** Time zone used to interpret dates without offset. */
		private String zoneId = "Australia/Sydney";

		private FieldMapping fields = new FieldMapping();

	}

	/** Maps normalised attributes to the dataset's column names. */
	@Data
	public static class FieldMapping {

		private String id = "_id";

		private String title;

		private String description;

		private String category;

		private String occurredAt;

		/** Optional java.time pattern; ISO-8601 is attempted when absent. */
		private String dateFormat;

		private String suburb;

		private String postalCode;

		private String latitude;

		private String longitude;

	}

}
