package com.example.springgraphqlmongo.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

/**
 * Embedded location details for a crime incident. Coordinates are stored as a
 * GeoJSON point (longitude, latitude) to support MongoDB geospatial queries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Location {

	@Size(max = 255)
	private String address;

	@NotBlank
	@Size(max = 100)
	private String city;

	@Size(max = 100)
	private String state;

	@NotBlank
	@Size(max = 100)
	private String country;

	@Size(max = 20)
	private String postalCode;

	/** Reference to {@link AustralianSuburb#getId()}. */
	@Size(max = 100)
	private String suburbId;

	private GeoJsonPoint coordinates;

	/** Denormalised suburb boundary for spatial queries without a join. */
	private org.springframework.data.mongodb.core.geo.GeoJsonPolygon boundary;

}
