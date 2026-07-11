package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NtGeographyResolver {

	private final IngestionProperties properties;

	private final ObjectMapper objectMapper;

	private final Map<String, GeographyLocation> locations = new HashMap<>();

	@PostConstruct
	void loadGeography() {
		Path file = Path.of(properties.getNt().getGeographyFile());
		if (!Files.exists(file)) {
			log.warn("NT geography file not found at {}", file);
			return;
		}
		try {
			JsonNode root = objectMapper.readTree(file.toFile());
			for (JsonNode feature : root.path("features")) {
				JsonNode propertiesNode = feature.path("properties");
				String name = propertiesNode.path("name").asText(null);
				String level = propertiesNode.path("level").asText("region");
				JsonNode centroid = propertiesNode.path("centroid");
				if (name == null || centroid.size() < 2) {
					continue;
				}
				double longitude = centroid.get(0).asDouble();
				double latitude = centroid.get(1).asDouble();
				GeographyLocation location = new GeographyLocation(name, level, longitude, latitude);
				locations.put(normalise(name), location);
				locations.put(normalise(level + ":" + name), location);
			}
			log.info("Loaded {} NT geography locations", locations.size());
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to load NT geography file " + file, ex);
		}
	}

	public GeographyResolution resolve(String reportingRegion, String statisticalArea2) {
		String sa2 = statisticalArea2 != null ? statisticalArea2.strip() : "";
		if (!sa2.isBlank() && !"-".equals(sa2) && !"Unknown".equalsIgnoreCase(sa2)) {
			GeographyLocation sa2Location = locations.get(normalise(sa2));
			if (sa2Location != null) {
				return toResolution(sa2Location);
			}
			log.warn("Unresolved NT SA2 '{}'; falling back to reporting region", sa2);
		}
		if (reportingRegion == null || reportingRegion.isBlank()) {
			return unresolved("Unknown");
		}
		String region = reportingRegion.strip();
		GeographyLocation regionLocation = locations.get(normalise(region));
		if (regionLocation == null) {
			log.warn("Unresolved NT reporting region '{}'", region);
			return unresolved(region);
		}
		return toResolution(regionLocation);
	}

	private GeographyResolution toResolution(GeographyLocation location) {
		RecordGranularity granularity = "sa2".equalsIgnoreCase(location.level())
				? RecordGranularity.DISTRICT_AGGREGATE
				: RecordGranularity.DISTRICT_AGGREGATE;
		String id = "NT:" + location.level().toUpperCase(Locale.ROOT) + ":"
				+ location.name().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
		return new GeographyResolution(location.name(), granularity, GeocodeStatus.APPROXIMATE, location.latitude(),
				location.longitude(), id, null);
	}

	private GeographyResolution unresolved(String name) {
		return new GeographyResolution(name, RecordGranularity.DISTRICT_AGGREGATE, GeocodeStatus.UNRESOLVED, null, null,
				null, null);
	}

	private static String normalise(String value) {
		return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").strip();
	}

	private record GeographyLocation(String name, String level, double longitude, double latitude) {
	}

	public record GeographyResolution(
			String canonicalName,
			RecordGranularity granularity,
			GeocodeStatus geocodeStatus,
			Double latitude,
			Double longitude,
			String suburbId,
			org.springframework.data.mongodb.core.geo.GeoJsonPolygon boundary) {
	}

}
