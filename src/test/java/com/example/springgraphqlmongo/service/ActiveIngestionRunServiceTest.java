package com.example.springgraphqlmongo.service;

import com.example.springgraphqlmongo.domain.SourceIngestionState;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.repository.SourceIngestionStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the concurrent-activation race in activateRun(): two
 * overlapping runs for the same source can both find no existing
 * source_ingestion_state document and both attempt to create one, with the
 * unique index on `source` rejecting the loser's insert. That should be
 * absorbed as a fallback update rather than bubbling up and aborting
 * ingestion.
 */
@ExtendWith(MockitoExtension.class)
class ActiveIngestionRunServiceTest {

	@Mock
	private SourceIngestionStateRepository sourceIngestionStateRepository;

	@Mock
	private CrimeIncidentRepository crimeIncidentRepository;

	@Mock
	private MongoTemplate mongoTemplate;

	private ActiveIngestionRunService activeIngestionRunService;

	@BeforeEach
	void setUp() {
		activeIngestionRunService = new ActiveIngestionRunService(sourceIngestionStateRepository,
				crimeIncidentRepository, mongoTemplate);
	}

	@Test
	void activateRunUpsertsWithoutFallbackWhenNoRaceOccurs() {
		activeIngestionRunService.activateRun("sa-police-crime-statistics", "run-1");

		verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(SourceIngestionState.class));
		verify(mongoTemplate, times(0)).updateFirst(any(Query.class), any(Update.class), eq(SourceIngestionState.class));
	}

	@Test
	void activateRunFallsBackToUpdateWhenConcurrentUpsertLosesTheRace() {
		when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(SourceIngestionState.class)))
				.thenThrow(new DuplicateKeyException("E11000 duplicate key error collection: "
						+ "crime_info_service.source_ingestion_state index: source dup key: "
						+ "{ source: \"sa-police-crime-statistics\" }"));

		activeIngestionRunService.activateRun("sa-police-crime-statistics", "run-1");

		verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(SourceIngestionState.class));
	}

}
