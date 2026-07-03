package com.example.springgraphqlmongo.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "australian_suburbs")
@CompoundIndex(name = "state_name_idx", def = "{'state': 1, 'name': 1}", unique = true)
public class AustralianSuburb {

	@Id
	private String id;

	@Indexed
	private String name;

	@Builder.Default
	private List<String> aliases = new ArrayList<>();

	@Indexed
	private String state;

	private String postcode;

	@GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE, name = "suburb_centroid_2dsphere_idx")
	private GeoJsonPoint centroid;

	@GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE, name = "suburb_perimeter_2dsphere_idx")
	private GeoJsonPolygon perimeter;

	private String source;

	private Instant cachedAt;

}
