package com.example.springgraphqlmongo.graphql;

import com.example.springgraphqlmongo.ingestion.IngestionResult;
import com.example.springgraphqlmongo.security.ApiAuthorizationService;
import com.example.springgraphqlmongo.service.CrimeIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class IngestionController {

	private final CrimeIngestionService crimeIngestionService;

	private final ApiAuthorizationService apiAuthorizationService;

	@QueryMapping
	public List<String> ingestionSources() {
		apiAuthorizationService.requireRead();
		return crimeIngestionService.sourceNames();
	}

	@MutationMapping
	public List<IngestionResult> ingestCrimeData(@Argument String source, @Argument Boolean refresh) {
		apiAuthorizationService.requireIngest();
		boolean forceRefresh = Boolean.TRUE.equals(refresh);
		if (source != null && !source.isBlank()) {
			return List.of(crimeIngestionService.ingest(source, forceRefresh));
		}
		return crimeIngestionService.ingestAll(forceRefresh);
	}

}
