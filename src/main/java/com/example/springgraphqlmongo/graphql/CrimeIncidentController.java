package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.domain.Location;
import com.example.springgraphqlmongo.graphql.dto.Coordinates;
import com.example.springgraphqlmongo.graphql.dto.SaOffenderContextDto;
import com.example.springgraphqlmongo.graphql.dto.SuburbBoundaryDto;
import com.example.springgraphqlmongo.service.CrimeIncidentService;
import com.example.springgraphqlmongo.security.ApiAuthorizationService;
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

	private final ApiAuthorizationService apiAuthorizationService;

	@QueryMapping
	public CrimeIncident crimeIncident(@Argument String id) {
		apiAuthorizationService.requireRead();
		return crimeIncidentService.getById(id);
	}

	@QueryMapping
	public List<CrimeIncident> crimeIncidents(@Argument String city, @Argument String state, @Argument String source,
			@Argument CrimeType crimeType, @Argument CrimeStatus status, @Argument Integer limit,
			@Argument Integer offset) {
		apiAuthorizationService.requireRead();
		return crimeIncidentService.search(city, state, source, crimeType, status, limit, offset);
	}

	@QueryMapping
	public List<CrimeIncident> crimesNearLocation(@Argument double latitude, @Argument double longitude,
			@Argument double radiusKm, @Argument String state) {
		apiAuthorizationService.requireRead();
		return crimeIncidentService.findNear(latitude, longitude, radiusKm, state);
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

	@SchemaMapping
	public SuburbBoundaryDto suburbBoundary(CrimeIncident incident) {
		return SuburbBoundaryDto.from(incident);
	}

	@SchemaMapping
	public SaOffenderContextDto offenderContext(CrimeIncident incident) {
		return SaOffenderContextDto.from(incident.getOffenderContext());
	}

}
