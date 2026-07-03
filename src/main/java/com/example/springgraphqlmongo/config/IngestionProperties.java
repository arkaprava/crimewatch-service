package com.example.springgraphqlmongo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for Australian crime data ingestion. Sources backed by CKAN
 * open data portals are configuration-driven; SA sources use a cache-first
 * file download strategy via {@code sa-crime-statistics}.
 */
@Data
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

	private boolean enabled = true;

	private Schedule schedule = new Schedule();

	private SaSettings sa = new SaSettings();

	private WaSettings wa = new WaSettings();

	private SuburbSettings suburbs = new SuburbSettings();

	private List<Source> sources = new ArrayList<>();

	@Data
	public static class Schedule {

		private boolean enabled = false;

		/** Default: daily at 03:00. */
		private String cron = "0 0 3 * * *";

	}

	@Data
	public static class SaSettings {

		private String cacheDir = "data/sa";

		private Duration cacheTtl = Duration.ofDays(7);

		private String downloadUserAgent = "crime-info-service/1.0";

		private String baseUrl = "https://data.sa.gov.au/data";

		private boolean refreshOnIngest = false;

		private Map<String, String> packageIds = new HashMap<>(Map.of(
				"crime-statistics", "860126f7-eeb5-4fbc-be44-069aa0467d11",
				"offenders", "8afe3e7c-9ed5-4e30-83a6-e8cc60e11f02"));

	}

	@Data
	public static class WaSettings {

		private String cacheDir = "data/wa";

		private Duration cacheTtl = Duration.ofDays(7);

		private String downloadUserAgent = "crime-info-service/1.0";

		private String timeseriesFilename = "WA-Police-Force-Crime-Timeseries.xlsx";

		private String csvFallbackFilename = "crime-timeseries.csv";

		private String districtsFile = "data/wa/wa-police-districts.geojson";

		private List<String> downloadUrls = new ArrayList<>(List.of(
				"https://www.police.wa.gov.au/-/media/Files/Police/Crime/Crime-Statistics/WA-Police-Force-Crime-Timeseries.xlsx",
				"https://www.police.wa.gov.au/~/media/Files/Police/Crime/Crime-Statistics/WA-Police-Force-Crime-Timeseries.xlsx"));

	}

	@Data
	public static class SuburbSettings {

		private String cacheFile = "data/suburbs/australian-suburbs.geojson";

		private Duration cacheTtl = Duration.ofDays(365);

		private double fuzzyMatchThreshold = 0.85;

		private boolean loadOnStartup = true;

	}

	@Data
	public static class Source {

		private String name;

		private boolean enabled = false;

		/** ckan | sa-crime-statistics | wa-crime-statistics */
		private String type = "ckan";

		/** Portal root, e.g. https://www.data.qld.gov.au */
		private String baseUrl;

		/** CKAN datastore resource id of the dataset. */
		private String resourceId;

		/** CKAN package id for SA datasets. */
		private String packageId;

		/** SA resource names to ingest, e.g. "Crime Statistics 2024-25". */
		private List<String> resourceNames = new ArrayList<>();

		/** WA spreadsheet sheet names to ingest; empty means all sheets. */
		private List<String> sheetNames = new ArrayList<>();

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

		private String geographyLevel;

		private String postalCode;

		private String latitude;

		private String longitude;

		private String offenceCount;

		private String reportingPeriod;

	}

}
