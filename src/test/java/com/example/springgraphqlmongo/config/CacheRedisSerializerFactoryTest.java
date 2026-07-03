package com.example.springgraphqlmongo.config;

import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeSeverity;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.domain.Location;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CacheRedisSerializerFactoryTest {

	@Test
	void roundTripsCrimeIncidentGraph() {
		RedisSerializer<Object> serializer = CacheRedisSerializerFactory
				.createValueSerializer(new ObjectMapper().findAndRegisterModules());
		GeoJsonPoint point = new GeoJsonPoint(new Point(151.2069, -33.8732));
		CrimeIncident incident = CrimeIncident.builder()
				.id("incident-1")
				.source("test-source")
				.externalId("ext-1")
				.title("Phone snatched")
				.crimeType(CrimeType.THEFT)
				.severity(CrimeSeverity.MEDIUM)
				.status(CrimeStatus.REPORTED)
				.location(Location.builder()
						.city("Sydney")
						.state("NSW")
						.country("Australia")
						.coordinates(point)
						.build())
				.geoCoordinates(point)
				.occurredAt(Instant.parse("2026-06-01T10:00:00Z"))
				.createdAt(Instant.parse("2026-06-01T11:00:00Z"))
				.build();

		byte[] payload = serializer.serialize(List.of(incident));
		Object restored = serializer.deserialize(payload);

		assertThat(restored).isInstanceOf(List.class);
		assertThat((List<?>) restored).hasSize(1);
		assertThat(((List<?>) restored).getFirst()).isInstanceOf(CrimeIncident.class);
		CrimeIncident cached = (CrimeIncident) ((List<?>) restored).getFirst();
		assertThat(cached.getId()).isEqualTo("incident-1");
		assertThat(cached.getTitle()).isEqualTo("Phone snatched");
		assertThat(cached.getLocation().getCity()).isEqualTo("Sydney");
		assertThat(cached.getGeoCoordinates().getX()).isEqualTo(151.2069);
		assertThat(cached.getGeoCoordinates().getY()).isEqualTo(-33.8732);
		assertThat(cached.getOccurredAt()).isEqualTo(Instant.parse("2026-06-01T10:00:00Z"));
	}

}
