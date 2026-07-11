package com.example.springgraphqlmongo.service;

import com.example.springgraphqlmongo.cache.CrimeReadCacheEvictor;
import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.IngestionRun;
import com.example.springgraphqlmongo.domain.IngestionRunStatus;
import com.example.springgraphqlmongo.domain.SourceIngestionState;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeDataSourceRegistry;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.IngestionResult;
import com.example.springgraphqlmongo.ingestion.storage.IngestionContext;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.repository.IngestionRunRepository;
import com.example.springgraphqlmongo.repository.SourceIngestionStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrimeIngestionServiceRefreshTest {

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

	private IngestionContext ingestionContext;

	private CrimeIngestionService crimeIngestionService;

	@BeforeEach
	void setUp() {
		IngestionProperties properties = new IngestionProperties();
		properties.setEnabled(true);
		ingestionContext = new IngestionContext();
		crimeIngestionService = new CrimeIngestionService(dataSourceRegistry, crimeIncidentRepository,
				ingestionRunRepository, properties, crimeReadCacheEvictor, ingestionContext,
				activeIngestionRunService);
	}

	@Test
	void refreshUsesAppendOnlyReingestWithoutDeletingExistingRecords() {
		CrimeDataSource source = stubSource("sa-police-crime-statistics");
		IngestionRun run = IngestionRun.builder()
				.id("run-1")
				.source("sa-police-crime-statistics")
				.status(IngestionRunStatus.RUNNING)
				.startedAt(Instant.now())
				.build();

		when(dataSourceRegistry.findByName("sa-police-crime-statistics")).thenReturn(Optional.of(source));
		when(ingestionRunRepository.save(any(IngestionRun.class))).thenReturn(run);
		when(crimeIncidentRepository.existsBySourceAndExternalIdAndIngestionRunId("sa-police-crime-statistics",
				"sa-01-07-2024-adelaide-common-assault", "run-1")).thenReturn(false);
		when(crimeIncidentRepository.save(any(CrimeIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));

		IngestionResult result = crimeIngestionService.ingest("sa-police-crime-statistics", true);

		verify(crimeIncidentRepository, never()).deleteBySource(any());
		verify(activeIngestionRunService).activateRun("sa-police-crime-statistics", "run-1");

		ArgumentCaptor<CrimeIncident> saved = ArgumentCaptor.forClass(CrimeIncident.class);
		verify(crimeIncidentRepository).save(saved.capture());
		assertThat(saved.getValue().getIngestionRunId()).isEqualTo("run-1");
		assertThat(result.inserted()).isEqualTo(1);
	}

	@Test
	void nonRefreshSkipsRecordsAlreadyPresentInActiveRun() {
		CrimeDataSource source = stubSource("nsw-bocsar-statistics");
		IngestionRun run = IngestionRun.builder()
				.id("run-2")
				.source("nsw-bocsar-statistics")
				.status(IngestionRunStatus.RUNNING)
				.startedAt(Instant.now())
				.build();

		when(dataSourceRegistry.findByName("nsw-bocsar-statistics")).thenReturn(Optional.of(source));
		when(ingestionRunRepository.save(any(IngestionRun.class))).thenReturn(run);
		when(crimeIncidentRepository.existsBySourceAndExternalIdAndIngestionRunId(eq("nsw-bocsar-statistics"),
				eq("sa-01-07-2024-adelaide-common-assault"), eq("run-2"))).thenReturn(false);
		when(activeIngestionRunService.activeRunIdForSource("nsw-bocsar-statistics")).thenReturn(Optional.of("run-old"));
		when(crimeIncidentRepository.existsBySourceAndExternalIdAndIngestionRunId(eq("nsw-bocsar-statistics"),
				eq("sa-01-07-2024-adelaide-common-assault"), eq("run-old"))).thenReturn(true);

		IngestionResult result = crimeIngestionService.ingest("nsw-bocsar-statistics", false);

		verify(crimeIncidentRepository, never()).deleteBySource(any());
		verify(crimeIncidentRepository, never()).save(any());
		assertThat(result.duplicates()).isEqualTo(1);
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
