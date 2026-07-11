package com.example.springgraphqlmongo.service;

import com.example.springgraphqlmongo.domain.SourceIngestionState;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.repository.SourceIngestionStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves which ingestion run is active per source and builds query filters
 * so reads only return the latest append-only snapshot.
 */
@Service
@RequiredArgsConstructor
public class ActiveIngestionRunService {

	private final SourceIngestionStateRepository sourceIngestionStateRepository;

	private final CrimeIncidentRepository crimeIncidentRepository;

	public Optional<String> activeRunIdForSource(String source) {
		return sourceIngestionStateRepository.findBySource(source).map(SourceIngestionState::getActiveIngestionRunId);
	}

	public void activateRun(String source, String ingestionRunId) {
		SourceIngestionState state = sourceIngestionStateRepository.findBySource(source)
				.orElse(SourceIngestionState.builder().source(source).build());
		state.setActiveIngestionRunId(ingestionRunId);
		state.setUpdatedAt(Instant.now());
		sourceIngestionStateRepository.save(state);
	}

	public boolean hasActiveData(String source) {
		Optional<String> activeRunId = activeRunIdForSource(source);
		if (activeRunId.isPresent()) {
			return crimeIncidentRepository.existsBySourceAndIngestionRunId(source, activeRunId.get());
		}
		return crimeIncidentRepository.countBySource(source) > 0;
	}

	public Criteria visibilityCriteria(String source) {
		Optional<String> activeRunId = activeRunIdForSource(source);
		if (activeRunId.isPresent()) {
			return Criteria.where("source").is(source).and("ingestionRunId").is(activeRunId.get());
		}
		return new Criteria().andOperator(
				Criteria.where("source").is(source),
				new Criteria().orOperator(
						Criteria.where("ingestionRunId").exists(false),
						Criteria.where("ingestionRunId").is(null)));
	}

	public Criteria visibilityCriteriaForSources(List<String> sources) {
		if (sources == null || sources.isEmpty()) {
			return legacyOnlyCriteria();
		}
		List<Criteria> perSource = sources.stream().map(this::visibilityCriteria).toList();
		return new Criteria().orOperator(perSource.toArray(new Criteria[0]));
	}

	public Criteria legacyOnlyCriteria() {
		return new Criteria().orOperator(
				Criteria.where("ingestionRunId").exists(false),
				Criteria.where("ingestionRunId").is(null));
	}

	public Criteria allVisibleCriteria() {
		List<Criteria> clauses = new ArrayList<>();
		clauses.add(legacyOnlyCriteria());
		for (SourceIngestionState state : sourceIngestionStateRepository.findAll()) {
			if (state.getActiveIngestionRunId() != null) {
				clauses.add(Criteria.where("source").is(state.getSource())
						.and("ingestionRunId").is(state.getActiveIngestionRunId()));
			}
		}
		return new Criteria().orOperator(clauses.toArray(new Criteria[0]));
	}

}
