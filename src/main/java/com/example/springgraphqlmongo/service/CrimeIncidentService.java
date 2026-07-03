package com.example.springgraphqlmongo.service;

import com.example.springgraphqlmongo.config.CrimeCacheNames;
import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.exception.ResourceNotFoundException;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CrimeIncidentService {

	private final CrimeIncidentRepository crimeIncidentRepository;

	private final MongoTemplate mongoTemplate;

	@Cacheable(cacheNames = CrimeCacheNames.CRIME_BY_ID, keyGenerator = "crimeCacheKeyGenerator")
	public CrimeIncident getById(String id) {
		return crimeIncidentRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Crime incident not found with id: " + id));
	}

	@Cacheable(cacheNames = CrimeCacheNames.CRIME_SEARCH, keyGenerator = "crimeCacheKeyGenerator")
	public List<CrimeIncident> search(String city, String state, String source, CrimeType crimeType,
			CrimeStatus status, Integer limit, Integer offset) {
		int effectiveLimit = CrimeSearchPagination.normalizeLimit(limit);
		int effectiveOffset = CrimeSearchPagination.normalizeOffset(offset);

		List<Criteria> criteria = new ArrayList<>();
		if (city != null && !city.isBlank()) {
			criteria.add(Criteria.where("location.city").regex("^" + Pattern.quote(city) + "$", "i"));
		}
		if (state != null && !state.isBlank()) {
			criteria.add(Criteria.where("location.state").regex("^" + Pattern.quote(state) + "$", "i"));
		}
		if (source != null && !source.isBlank()) {
			criteria.add(Criteria.where("source").regex("^" + Pattern.quote(source) + "$", "i"));
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
		query.skip(effectiveOffset).limit(effectiveLimit);
		return mongoTemplate.find(query, CrimeIncident.class);
	}

	public List<CrimeIncident> findNear(double latitude, double longitude, double radiusKm) {
		return findNear(latitude, longitude, radiusKm, null);
	}

	/**
	 * Incidents within {@code radiusKm} of the given point, nearest first.
	 * Uses suburb perimeter/centroid matching for aggregate SA records and
	 * point proximity for incident-level records.
	 */
	@Cacheable(cacheNames = CrimeCacheNames.CRIMES_NEAR, keyGenerator = "crimeCacheKeyGenerator", sync = true)
	public List<CrimeIncident> findNear(double latitude, double longitude, double radiusKm, String state) {
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new IllegalArgumentException("Invalid coordinates: " + latitude + ", " + longitude);
		}
		if (radiusKm <= 0) {
			throw new IllegalArgumentException("radiusKm must be positive");
		}

		GeoJsonPoint searchPoint = new GeoJsonPoint(new Point(longitude, latitude));
		double radiusMeters = radiusKm * 1000;
		Map<String, CrimeIncident> results = new LinkedHashMap<>();

		List<String> suburbIds = findMatchingSuburbIds(searchPoint, radiusMeters, state);
		if (!suburbIds.isEmpty()) {
			Query suburbIncidentQuery = new Query(Criteria.where("location.suburbId").in(suburbIds));
			applyStateFilter(suburbIncidentQuery, state);
			mongoTemplate.find(suburbIncidentQuery, CrimeIncident.class)
					.forEach(incident -> results.put(incident.getId(), incident));
		}

		Query geoQuery = new Query(Criteria.where("geo_coordinates")
				.nearSphere(searchPoint)
				.maxDistance(radiusMeters));
		applyStateFilter(geoQuery, state);
		mongoTemplate.find(geoQuery, CrimeIncident.class)
				.forEach(incident -> results.putIfAbsent(incident.getId(), incident));

		return new ArrayList<>(results.values());
	}

	private List<String> findMatchingSuburbIds(GeoJsonPoint searchPoint, double radiusMeters, String state) {
		Map<String, AustralianSuburb> matches = new LinkedHashMap<>();

		Query centroidQuery = new Query(Criteria.where("centroid")
				.nearSphere(searchPoint)
				.maxDistance(radiusMeters));
		if (state != null && !state.isBlank()) {
			centroidQuery.addCriteria(Criteria.where("state").regex("^" + Pattern.quote(state) + "$", "i"));
		}
		mongoTemplate.find(centroidQuery, AustralianSuburb.class)
				.forEach(suburb -> matches.put(suburb.getId(), suburb));

		Query perimeterQuery = new Query(Criteria.where("perimeter").intersects(searchPoint));
		if (state != null && !state.isBlank()) {
			perimeterQuery.addCriteria(Criteria.where("state").regex("^" + Pattern.quote(state) + "$", "i"));
		}
		mongoTemplate.find(perimeterQuery, AustralianSuburb.class)
				.forEach(suburb -> matches.put(suburb.getId(), suburb));

		return matches.keySet().stream().toList();
	}

	private void applyStateFilter(Query query, String state) {
		if (state != null && !state.isBlank()) {
			query.addCriteria(Criteria.where("location.state").regex("^" + Pattern.quote(state) + "$", "i"));
		}
	}

}
