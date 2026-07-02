package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.domain.Location;
import com.example.springgraphqlmongo.graphql.dto.Coordinates;
import com.example.springgraphqlmongo.service.CrimeIncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class CrimeIncidentController {

	private final CrimeIncidentService crimeIncidentService;

	@QueryMapping
	public CrimeIncident crimeIncident(@Argument String id) {
		return crimeIncidentService.getById(id);
	}

	@QueryMapping
	public List<CrimeIncident> crimeIncidents(@Argument String city, @Argument String state,
			@Argument CrimeType crimeType, @Argument CrimeStatus status) {
		return crimeIncidentService.search(city, state, crimeType, status);
	}

	@QueryMapping
	public List<CrimeIncident> crimesNearLocation(@Argument double latitude, @Argument double longitude,
			@Argument double radiusKm) {
		return crimeIncidentService.findNear(latitude, longitude, radiusKm);
	}

	@SchemaMapping
	public Coordinates coordinates(Location location) {
		if (location.getCoordinates() == null) {
			return null;
		}
		return new Coordinates(location.getCoordinates().getY(), location.getCoordinates().getX());
	}

	@SchemaMapping
	public String occurredAt(CrimeIncident incident) {
		return incident.getOccurredAt() != null ? incident.getOccurredAt().toString() : null;
	}

	@SchemaMapping
	public String reportedAt(CrimeIncident incident) {
		return incident.getReportedAt() != null ? incident.getReportedAt().toString() : null;
	}

}
