package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.repository.AustralianSuburbRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SuburbCacheLoader {

	private final IngestionProperties properties;

	private final AustralianSuburbRepository suburbRepository;

	private final ObjectMapper objectMapper;

	@EventListener(ApplicationReadyEvent.class)
	public void loadOnStartup() {
		if (!properties.getSuburbs().isLoadOnStartup()) {
			return;
		}
		if (suburbRepository.count() > 0) {
			log.debug("Australian suburb cache already populated ({} records)", suburbRepository.count());
			return;
		}
		loadFromCacheFile();
	}

	public void loadFromCacheFile() {
		Path cacheFile = Path.of(properties.getSuburbs().getCacheFile());
		if (!Files.exists(cacheFile)) {
			log.warn("Suburb cache file not found at {}; geocoding will be limited", cacheFile);
			return;
		}
		try {
			JsonNode root = objectMapper.readTree(Files.readString(cacheFile));
			JsonNode features = root.path("features");
			if (!features.isArray()) {
				log.warn("Suburb cache file has no FeatureCollection features: {}", cacheFile);
				return;
			}
			int loaded = 0;
			for (JsonNode feature : features) {
				AustralianSuburb suburb = toSuburb(feature);
				if (suburb != null) {
					suburbRepository.save(suburb);
					loaded++;
				}
			}
			log.info("Loaded {} suburbs from {}", loaded, cacheFile);
		}
		catch (IOException ex) {
			log.error("Failed to load suburb cache from {}", cacheFile, ex);
		}
	}

	private AustralianSuburb toSuburb(JsonNode feature) {
		JsonNode properties = feature.path("properties");
		JsonNode geometry = feature.path("geometry");
		String id = properties.path("id").asText(null);
		String name = properties.path("name").asText(null);
		String state = properties.path("state").asText(null);
		if (id == null || name == null || state == null) {
			return null;
		}

		GeoJsonPolygon perimeter = parsePolygon(geometry);
		GeoJsonPoint centroid = parseCentroid(properties, perimeter);

		List<String> aliases = new ArrayList<>();
		JsonNode aliasNode = properties.path("aliases");
		if (aliasNode.isArray()) {
			for (JsonNode alias : aliasNode) {
				aliases.add(alias.asText());
			}
		}
		aliases.add(AustralianSuburbGeocoder.normalise(name));

		return AustralianSuburb.builder()
				.id(id)
				.name(name)
				.state(state.toUpperCase())
				.postcode(properties.path("postcode").asText(null))
				.aliases(aliases)
				.centroid(centroid)
				.perimeter(perimeter)
				.source(properties.path("source").asText("ABS-ASGS"))
				.cachedAt(Instant.now())
				.build();
	}

	private GeoJsonPolygon parsePolygon(JsonNode geometry) {
		if (!"Polygon".equalsIgnoreCase(geometry.path("type").asText())) {
			return null;
		}
		JsonNode coordinates = geometry.path("coordinates");
		if (!coordinates.isArray() || coordinates.isEmpty()) {
			return null;
		}
		List<Point> ring = new ArrayList<>();
		for (JsonNode point : coordinates.get(0)) {
			if (point.isArray() && point.size() >= 2) {
				ring.add(new Point(point.get(0).asDouble(), point.get(1).asDouble()));
			}
		}
		if (ring.size() < 4) {
			return null;
		}
		return new GeoJsonPolygon(ring);
	}

	private GeoJsonPoint parseCentroid(JsonNode properties, GeoJsonPolygon perimeter) {
		if (properties.has("centroid")) {
			JsonNode centroid = properties.path("centroid");
			if (centroid.isArray() && centroid.size() >= 2) {
				return new GeoJsonPoint(new Point(centroid.get(0).asDouble(), centroid.get(1).asDouble()));
			}
		}
		if (perimeter == null || perimeter.getPoints().isEmpty()) {
			return null;
		}
		double sumLng = 0;
		double sumLat = 0;
		int count = 0;
		for (Point point : perimeter.getPoints()) {
			sumLng += point.getX();
			sumLat += point.getY();
			count++;
		}
		return new GeoJsonPoint(new Point(sumLng / count, sumLat / count));
	}

}
