package com.example.springgraphqlmongo.service;

import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.exception.ResourceNotFoundException;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Read-side queries over ingested crime incidents, including geospatial lookups. */
@Service
@RequiredArgsConstructor
public class CrimeIncidentService {

	private final CrimeIncidentRepository crimeIncidentRepository;

	private final MongoTemplate mongoTemplate;

	public CrimeIncident getById(String id) {
		return crimeIncidentRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Crime incident not found with id: " + id));
	}

	/** Filtered search; all criteria are optional and combined with AND. */
	public List<CrimeIncident> search(String city, String state, CrimeType crimeType, CrimeStatus status) {
		List<Criteria> criteria = new ArrayList<>();
		if (city != null && !city.isBlank()) {
			criteria.add(Criteria.where("location.city").regex("^" + Pattern.quote(city) + "$", "i"));
		}
		if (state != null && !state.isBlank()) {
			criteria.add(Criteria.where("location.state").regex("^" + Pattern.quote(state) + "$", "i"));
		}
		if (crimeType != null) {
			criteria.add(Criteria.where("crimeType").is(crimeType));
		}
		if (status != null) {
			criteria.add(Criteria.where("status").is(status));
		}

		Query query = criteria.isEmpty() ? new Query()
				: new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
		query.with(Sort.by(Sort.Direction.DESC, "occurredAt"));
		return mongoTemplate.find(query, CrimeIncident.class);
	}

	/**
	 * Incidents within {@code radiusKm} of the given point, nearest first.
	 * Uses the 2dsphere index on {@code geo_coordinates}; incidents without
	 * coordinates are never returned.
	 */
	public List<CrimeIncident> findNear(double latitude, double longitude, double radiusKm) {
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new IllegalArgumentException("Invalid coordinates: " + latitude + ", " + longitude);
		}
		if (radiusKm <= 0) {
			throw new IllegalArgumentException("radiusKm must be positive");
		}
		Criteria geoCriteria = Criteria.where("geo_coordinates")
				.nearSphere(new GeoJsonPoint(longitude, latitude))
				.maxDistance(radiusKm * 1000);
		return mongoTemplate.find(new Query(geoCriteria), CrimeIncident.class);
	}

}
