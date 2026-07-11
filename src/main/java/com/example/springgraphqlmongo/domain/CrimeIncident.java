package com.example.springgraphqlmongo.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "crime_incidents")
@CompoundIndex(name = "run_external_id_idx", def = "{'ingestionRunId': 1, 'externalId': 1}", unique = true, sparse = true)
@CompoundIndex(name = "source_external_id_lookup_idx", def = "{'source': 1, 'externalId': 1}")
public class CrimeIncident {

	@Id
	private String id;

	private RecordGranularity granularity;

	private GeocodeStatus geocodeStatus;

	private Integer offenceCount;

	@Size(max = 20)
	private String reportingPeriod;

	private SaOffenderContext offenderContext;

	/** Identifier of the data source this incident was ingested from (e.g. "qld-police-offences"). */
	@Size(max = 100)
	private String source;

	/** Stable identifier of the record within its source, used for de-duplication. */
	@Size(max = 255)
	private String externalId;

	/** Append-only ingestion run that produced this record. */
	@Indexed
	private String ingestionRunId;

	@NotBlank
	@Size(max = 255)
	private String title;

	@Size(max = 2000)
	private String description;

	@NotNull
	@Indexed(name = "crime_type_idx")
	private CrimeType crimeType;

	@NotNull
	private CrimeSeverity severity;

	@NotNull
	@Indexed(name = "status_idx")
	private CrimeStatus status;

	@NotNull
	@Valid
	private Location location;

	/** GeoJSON copy of location coordinates, indexed for near/within queries. */
	@GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE, name = "geo_coordinates_2dsphere_idx")
	@Field("geo_coordinates")
	private GeoJsonPoint geoCoordinates;

	/** When the crime occurred. */
	@NotNull
	@Indexed(name = "occurred_at_idx")
	private Instant occurredAt;

	/** When the crime was reported to the service. */
	private Instant reportedAt;

	private Instant createdAt;

	private Instant updatedAt;

}
