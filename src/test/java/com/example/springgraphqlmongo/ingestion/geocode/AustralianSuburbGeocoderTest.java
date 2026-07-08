package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.repository.AustralianSuburbRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AustralianSuburbGeocoderTest {

	@Mock
	private AustralianSuburbRepository suburbRepository;

	private AustralianSuburbGeocoder geocoder;

	@BeforeEach
	void setUp() {
		IngestionProperties properties = new IngestionProperties();
		properties.getSuburbs().setFuzzyMatchThreshold(0.85);
		geocoder = new AustralianSuburbGeocoder(suburbRepository, properties);
	}

	@Test
	void resolvesExactSuburbName() {
		AustralianSuburb adelaide = suburb("SA:ADELAIDE", "Adelaide", "SA");
		when(suburbRepository.findByStateAndNameIgnoreCase("SA", "ADELAIDE")).thenReturn(Optional.of(adelaide));

		SuburbMatch match = geocoder.resolve("Adelaide", "SA");

		assertThat(match.status()).isEqualTo(GeocodeStatus.RESOLVED);
		assertThat(match.suburb().getId()).isEqualTo("SA:ADELAIDE");
	}

	@Test
	void fuzzyMatchesMisspelledSuburb() {
		AustralianSuburb glenelg = suburb("SA:GLENELG", "Glenelg", "SA");
		when(suburbRepository.findByStateAndNameIgnoreCase("SA", "GLENLEG")).thenReturn(Optional.empty());
		when(suburbRepository.findByStateAndAliasesContainingIgnoreCase("SA", "GLENLEG")).thenReturn(List.of());
		when(suburbRepository.findByState("SA")).thenReturn(List.of(glenelg));

		SuburbMatch match = geocoder.resolve("Glenleg", "SA");

		assertThat(match.status()).isEqualTo(GeocodeStatus.APPROXIMATE);
		assertThat(match.canonicalName()).isEqualTo("Glenelg");
	}

	@Test
	void returnsUnresolvedWhenNoMatch() {
		when(suburbRepository.findByStateAndNameIgnoreCase("SA", "UNKNOWN")).thenReturn(Optional.empty());
		when(suburbRepository.findByStateAndAliasesContainingIgnoreCase("SA", "UNKNOWN")).thenReturn(List.of());
		when(suburbRepository.findByState("SA")).thenReturn(List.of());

		SuburbMatch match = geocoder.resolve("Unknown", "SA");

		assertThat(match.status()).isEqualTo(GeocodeStatus.UNRESOLVED);
	}

	@Test
	void normalisesStateSuffixWithoutRegexOverflow() {
		assertThat(AustralianSuburbGeocoder.normalise("Glenelg (SA)")).isEqualTo("GLENELG");
		assertThat(AustralianSuburbGeocoder.normalise("Fremantle WA")).isEqualTo("FREMANTLE");
		org.junit.jupiter.api.Assertions.assertDoesNotThrow(
				() -> AustralianSuburbGeocoder.normalise("(".repeat(10000) + "Suburb (WA)"));
	}

	private AustralianSuburb suburb(String id, String name, String state) {
		List<Point> ring = List.of(
				new Point(138.58, -34.94),
				new Point(138.62, -34.94),
				new Point(138.62, -34.91),
				new Point(138.58, -34.91),
				new Point(138.58, -34.94));
		return AustralianSuburb.builder()
				.id(id)
				.name(name)
				.state(state)
				.centroid(new GeoJsonPoint(new Point(138.6007, -34.9285)))
				.perimeter(new GeoJsonPolygon(ring))
				.build();
	}

}
