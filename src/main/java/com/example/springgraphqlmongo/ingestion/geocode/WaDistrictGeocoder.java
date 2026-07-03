package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
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
public class WaDistrictGeocoder {

	private final IngestionProperties properties;

	private final ObjectMapper objectMapper;

	private final Map<String, DistrictLocation> districts = new HashMap<>();

	@PostConstruct
	void loadDistricts() {
		Path file = Path.of(properties.getWa().getDistrictsFile());
		if (!Files.exists(file)) {
			log.warn("WA district geocoder file not found at {}", file);
			return;
		}
		try {
			JsonNode root = objectMapper.readTree(file.toFile());
			for (JsonNode feature : root.path("features")) {
				JsonNode propertiesNode = feature.path("properties");
				String name = propertiesNode.path("name").asText(null);
				JsonNode centroid = propertiesNode.path("centroid");
				if (name == null || centroid.size() < 2) {
					continue;
				}
				double longitude = centroid.get(0).asDouble();
				double latitude = centroid.get(1).asDouble();
				DistrictLocation location = new DistrictLocation(name, longitude, latitude);
				districts.put(normalise(name), location);
				districts.put(normalise(stripDistrictSuffix(name)), location);
			}
			log.info("Loaded {} WA police district centroids", districts.size());
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to load WA district geocoder file " + file, ex);
		}
	}

	public SuburbMatch resolve(String rawDistrict) {
		if (rawDistrict == null || rawDistrict.isBlank()) {
			return SuburbMatch.unresolved("Unknown");
		}
		DistrictLocation district = districts.get(normalise(rawDistrict));
		if (district == null) {
			district = districts.get(normalise(stripDistrictSuffix(rawDistrict)));
		}
		if (district == null) {
			log.warn("Unresolved WA district '{}'", rawDistrict);
			return SuburbMatch.unresolved(rawDistrict);
		}
		GeoJsonPoint point = new GeoJsonPoint(new Point(district.longitude(), district.latitude()));
		AustralianSuburb synthetic = AustralianSuburb.builder()
				.id("WA:DISTRICT:" + district.name().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-"))
				.name(district.name())
				.state("WA")
				.centroid(point)
				.source("WA-POLICE-DISTRICT")
				.build();
		return SuburbMatch.builder()
				.suburb(synthetic)
				.status(GeocodeStatus.APPROXIMATE)
				.canonicalName(district.name())
				.build();
	}

	private static String stripDistrictSuffix(String value) {
		return value.replaceAll("(?i)\\s+district$", "").trim();
	}

	static String normalise(String value) {
		return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}

	record DistrictLocation(String name, double longitude, double latitude) {
	}

}
