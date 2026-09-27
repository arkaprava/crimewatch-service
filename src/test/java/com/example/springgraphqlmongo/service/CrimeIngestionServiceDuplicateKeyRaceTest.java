package com.example.springgraphqlmongo.service;

import com.example.springgraphqlmongo.cache.CrimeReadCacheEvictor;
import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.IngestionRun;
import com.example.springgraphqlmongo.domain.IngestionRunStatus;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeDataSourceRegistry;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.IngestionResult;
import com.example.springgraphqlmongo.ingestion.storage.IngestionContext;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.repository.IngestionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression test for the concurrent-ingestion race in isDuplicate(): it is
 * check-then-act, not atomic, so two overlapping runs can both see a record
 * as absent and both attempt to save it — the loser hits the database's
 * unique index instead. That outcome is a duplicate, not a real failure,
 * and should be counted/logged as such rather than as failed.
 */
@ExtendWith(MockitoExtension.class)
class CrimeIngestionServiceDuplicateKeyRaceTest {

	@Mock
	private CrimeDataSourceRegistry dataSourceRegistry;

	@Mock
	private CrimeIncidentRepository crimeIncidentRepository;

	@Mock
	private IngestionRunRepository ingestionRunRepository;

	@Mock
	private CrimeReadCacheEvictor crimeReadCacheEvictor;

	@Mock
	private ActiveIngestionRunService activeIngestionRunService;

	private CrimeIngestionService crimeIngestionService;

	@BeforeEach
	void setUp() {
		IngestionProperties properties = new IngestionProperties();
		properties.setEnabled(true);
		crimeIngestionService = new CrimeIngestionService(dataSourceRegistry, crimeIncidentRepository,
				ingestionRunRepository, properties, crimeReadCacheEvictor, new IngestionContext(),
				activeIngestionRunService);
	}

	@Test
	void concurrentInsertRaceLostToAnotherRunIsCountedAsDuplicateNotFailure() {
		CrimeDataSource source = stubSource("sa-police-crime-statistics");
		IngestionRun run = IngestionRun.builder()
				.id("run-losing")
				.source("sa-police-crime-statistics")
				.status(IngestionRunStatus.RUNNING)
				.startedAt(Instant.now())
				.build();

		when(dataSourceRegistry.findByName("sa-police-crime-statistics")).thenReturn(Optional.of(source));
		when(ingestionRunRepository.save(any(IngestionRun.class))).thenReturn(run);
		// no active run recorded yet (matches the real scenario this was found in) —
		// isDuplicate() falls back to a plain existsBySourceAndExternalId check, which
		// a concurrent, not-yet-committed run also sees as false
		when(activeIngestionRunService.activeRunIdForSource("sa-police-crime-statistics")).thenReturn(Optional.empty());
		when(crimeIncidentRepository.existsBySourceAndExternalIdAndIngestionRunId(eq("sa-police-crime-statistics"),
				eq("sa-01-07-2024-adelaide-common-assault"), eq("run-losing"))).thenReturn(false);
		when(crimeIncidentRepository.existsBySourceAndExternalId("sa-police-crime-statistics",
				"sa-01-07-2024-adelaide-common-assault")).thenReturn(false);
		when(crimeIncidentRepository.save(any(CrimeIncident.class)))
				.thenThrow(new DuplicateKeyException("E11000 duplicate key error collection: "
						+ "crime_info_service.crime_incidents index: source_external_id_idx"));

		IngestionResult result = crimeIngestionService.ingest("sa-police-crime-statistics", false);

		assertThat(result.duplicates()).isEqualTo(1);
		assertThat(result.failed()).isEqualTo(0);
	}

	private static CrimeDataSource stubSource(String name) {
		return new CrimeDataSource() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public boolean isEnabled() {
				return true;
			}

			@Override
			public List<CrimeRecord> fetchRecords() {
				return List.of(CrimeRecord.builder()
						.externalId("sa-01-07-2024-adelaide-common-assault")
						.title("Common Assault in Adelaide")
						.occurredAt(Instant.parse("2024-07-01T00:00:00Z"))
						.suburb("Adelaide")
						.state("SA")
						.offenceCount(4)
						.reportingPeriod("01/07/2024")
						.build());
			}
		};
	}

}
