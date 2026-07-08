package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
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
public class TasGeographyResolver {

	private static final GeoJsonPoint TAS_STATE_CENTROID = new GeoJsonPoint(new Point(146.75, -42.0));

	private final IngestionProperties properties;

	private final ObjectMapper objectMapper;

	private final Map<String, GeographyLocation> locations = new HashMap<>();

	@PostConstruct
	void loadGeography() {
		Path file = Path.of(properties.getTas().getGeographyFile());
		if (!Files.exists(file)) {
			log.warn("TAS geography file not found at {}", file);
			return;
		}
		try {
			JsonNode root = objectMapper.readTree(file.toFile());
			for (JsonNode feature : root.path("features")) {
				JsonNode propertiesNode = feature.path("properties");
				String name = propertiesNode.path("name").asText(null);
				String level = propertiesNode.path("level").asText("division");
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
			log.info("Loaded {} TAS geography locations", locations.size());
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to load TAS geography file " + file, ex);
		}
	}

	public GeographyResolution resolve(String geographyLevel, String geographyName) {
		if (geographyName == null || geographyName.isBlank()) {
			return unresolved("Unknown");
		}
		String normalisedName = geographyName.strip();
		if ("STATE".equalsIgnoreCase(normalisedName) || "TASMANIA".equalsIgnoreCase(normalisedName)) {
			return stateResolution();
		}
		GeographyLocation location = locations.get(normalise(normalisedName));
		if (location == null && geographyLevel != null) {
			location = locations.get(normalise(geographyLevel + ":" + normalisedName));
		}
		if (location == null) {
			log.warn("Unresolved TAS geography '{}' ({})", geographyName, geographyLevel);
			return unresolved(normalisedName);
		}
		RecordGranularity granularity = "state".equalsIgnoreCase(location.level()) ? RecordGranularity.STATE_AGGREGATE
				: "district".equalsIgnoreCase(location.level()) ? RecordGranularity.DISTRICT_AGGREGATE
						: RecordGranularity.DISTRICT_AGGREGATE;
		GeocodeStatus status = "state".equalsIgnoreCase(location.level()) ? GeocodeStatus.RESOLVED
				: GeocodeStatus.APPROXIMATE;
		String id = "TAS:" + location.level().toUpperCase(Locale.ROOT) + ":"
				+ location.name().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
		GeoJsonPoint point = new GeoJsonPoint(new Point(location.longitude(), location.latitude()));
		AustralianSuburb synthetic = AustralianSuburb.builder()
				.id(id)
				.name(location.name())
				.state("TAS")
				.centroid(point)
				.build();
		return new GeographyResolution(location.name(), granularity, status, location.latitude(), location.longitude(),
				id, synthetic.getPerimeter());
	}

	public GeographyResolution stateResolution() {
		return new GeographyResolution("Tasmania", RecordGranularity.STATE_AGGREGATE, GeocodeStatus.RESOLVED,
				TAS_STATE_CENTROID.getY(), TAS_STATE_CENTROID.getX(), "TAS:STATE", null);
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
