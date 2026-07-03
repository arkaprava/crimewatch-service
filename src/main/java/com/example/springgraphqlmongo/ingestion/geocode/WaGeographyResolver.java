package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WaGeographyResolver {

	private static final GeoJsonPoint WA_STATE_CENTROID = new GeoJsonPoint(new Point(115.8605, -31.9505));

	private final AustralianSuburbGeocoder suburbGeocoder;

	private final WaDistrictGeocoder districtGeocoder;

	public GeographyResolution resolve(String geographyLevel, String location, String state) {
		String level = geographyLevel != null ? geographyLevel.trim().toLowerCase() : "locality";
		if (level.contains("state") || level.contains("western australia")) {
			return new GeographyResolution(location, RecordGranularity.STATE_AGGREGATE, GeocodeStatus.APPROXIMATE,
					WA_STATE_CENTROID.getY(), WA_STATE_CENTROID.getX(), "WA:STATE", null);
		}
		if (level.contains("district")) {
			SuburbMatch match = districtGeocoder.resolve(location);
			return fromMatch(match, RecordGranularity.DISTRICT_AGGREGATE);
		}
		SuburbMatch match = suburbGeocoder.resolve(location, state);
		return fromMatch(match, RecordGranularity.SUBURB_AGGREGATE);
	}

	private GeographyResolution fromMatch(SuburbMatch match, RecordGranularity granularity) {
		Double latitude = null;
		Double longitude = null;
		String suburbId = null;
		if (match.suburb() != null && match.suburb().getCentroid() != null) {
			GeoJsonPoint centroid = match.suburb().getCentroid();
			latitude = centroid.getY();
			longitude = centroid.getX();
			suburbId = match.suburb().getId();
		}
		return new GeographyResolution(
				match.canonicalName() != null ? match.canonicalName() : "Unknown",
				granularity,
				match.status() != null ? match.status() : GeocodeStatus.UNRESOLVED,
				latitude,
				longitude,
				suburbId,
				match.suburb() != null ? match.suburb().getPerimeter() : null);
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
