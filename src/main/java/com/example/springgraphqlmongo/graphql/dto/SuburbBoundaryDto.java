package com.example.springgraphqlmongo.graphql.dto;

import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.Location;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;

import java.util.ArrayList;
import java.util.List;

@Value
@Builder
public class SuburbBoundaryDto {

	String suburbId;

	String name;

	String state;

	Coordinates centroid;

	List<List<Coordinates>> perimeter;

	public static SuburbBoundaryDto from(CrimeIncident incident) {
		if (incident == null || incident.getLocation() == null) {
			return null;
		}
		Location location = incident.getLocation();
		Coordinates centroid = null;
		if (location.getCoordinates() != null) {
			centroid = new Coordinates(location.getCoordinates().getY(), location.getCoordinates().getX());
		}
		return SuburbBoundaryDto.builder()
				.suburbId(location.getSuburbId())
				.name(location.getCity())
				.state(location.getState())
				.centroid(centroid)
				.perimeter(toPerimeter(location.getBoundary()))
				.build();
	}

	public static SuburbBoundaryDto from(AustralianSuburb suburb) {
		if (suburb == null) {
			return null;
		}
		Coordinates centroid = null;
		if (suburb.getCentroid() != null) {
			centroid = new Coordinates(suburb.getCentroid().getY(), suburb.getCentroid().getX());
		}
		return SuburbBoundaryDto.builder()
				.suburbId(suburb.getId())
				.name(suburb.getName())
				.state(suburb.getState())
				.centroid(centroid)
				.perimeter(toPerimeter(suburb.getPerimeter()))
				.build();
	}

	private static List<List<Coordinates>> toPerimeter(GeoJsonPolygon polygon) {
		if (polygon == null || polygon.getPoints().isEmpty()) {
			return List.of();
		}
		List<Coordinates> ring = new ArrayList<>();
		for (Point point : polygon.getPoints()) {
			ring.add(new Coordinates(point.getY(), point.getX()));
		}
		return List.of(ring);
	}

}
