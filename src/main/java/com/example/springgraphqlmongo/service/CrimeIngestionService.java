package com.example.springgraphqlmongo.service;

import com.example.springgraphqlmongo.cache.CrimeReadCacheEvictor;
import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeSeverity;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.domain.IngestionRun;
import com.example.springgraphqlmongo.domain.IngestionRunStatus;
import com.example.springgraphqlmongo.domain.Location;
import com.example.springgraphqlmongo.domain.RecordGranularity;
import com.example.springgraphqlmongo.exception.ResourceNotFoundException;
import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeDataSourceRegistry;
import com.example.springgraphqlmongo.ingestion.CrimeRecord;
import com.example.springgraphqlmongo.ingestion.IngestionResult;
import com.example.springgraphqlmongo.ingestion.source.NtCrimeStatisticsDataSource;
import com.example.springgraphqlmongo.ingestion.source.NswBocsarStatisticsDataSource;
import com.example.springgraphqlmongo.ingestion.source.SaCrimeStatisticsDataSource;
import com.example.springgraphqlmongo.ingestion.source.TasCorporatePerformanceDataSource;
import com.example.springgraphqlmongo.ingestion.source.TasCrimeStatisticsSupplementDataSource;
import com.example.springgraphqlmongo.ingestion.source.WaCrimeStatisticsDataSource;
import com.example.springgraphqlmongo.ingestion.storage.IngestionContext;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.repository.IngestionRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrimeIngestionService {

	private static final Map<String, CrimeType> CATEGORY_KEYWORDS = Map.ofEntries(
			Map.entry("theft", CrimeType.THEFT),
			Map.entry("steal", CrimeType.THEFT),
			Map.entry("shoplift", CrimeType.THEFT),
			Map.entry("burglary", CrimeType.BURGLARY),
			Map.entry("break", CrimeType.BURGLARY),
			Map.entry("robbery", CrimeType.ROBBERY),
			Map.entry("assault", CrimeType.ASSAULT),
			Map.entry("homicide", CrimeType.HOMICIDE),
			Map.entry("murder", CrimeType.HOMICIDE),
			Map.entry("kidnap", CrimeType.KIDNAPPING),
			Map.entry("abduct", CrimeType.KIDNAPPING),
			Map.entry("vandal", CrimeType.VANDALISM),
			Map.entry("property damage", CrimeType.VANDALISM),
			Map.entry("graffiti", CrimeType.VANDALISM),
			Map.entry("fraud", CrimeType.FRAUD),
			Map.entry("deception", CrimeType.FRAUD),
			Map.entry("cyber", CrimeType.CYBERCRIME),
			Map.entry("drug", CrimeType.DRUG_OFFENSE),
			Map.entry("arson", CrimeType.ARSON),
			Map.entry("fire setting", CrimeType.ARSON));

	private static final Map<CrimeType, CrimeSeverity> DEFAULT_SEVERITY = Map.of(
			CrimeType.HOMICIDE, CrimeSeverity.CRITICAL,
			CrimeType.KIDNAPPING, CrimeSeverity.CRITICAL,
			CrimeType.ROBBERY, CrimeSeverity.HIGH,
			CrimeType.ASSAULT, CrimeSeverity.HIGH,
			CrimeType.ARSON, CrimeSeverity.HIGH,
			CrimeType.BURGLARY, CrimeSeverity.MEDIUM,
			CrimeType.DRUG_OFFENSE, CrimeSeverity.MEDIUM,
			CrimeType.FRAUD, CrimeSeverity.MEDIUM,
			CrimeType.CYBERCRIME, CrimeSeverity.MEDIUM);

	private final CrimeDataSourceRegistry dataSourceRegistry;

	private final CrimeIncidentRepository crimeIncidentRepository;

	private final IngestionRunRepository ingestionRunRepository;

	private final IngestionProperties ingestionProperties;

	private final CrimeReadCacheEvictor crimeReadCacheEvictor;

	private final IngestionContext ingestionContext;

	private final ActiveIngestionRunService activeIngestionRunService;

	public List<IngestionResult> ingestAll() {
		return ingestAll(false);
	}

	public List<IngestionResult> ingestAll(boolean refresh) {
		if (!ingestionProperties.isEnabled()) {
			log.info("Ingestion is disabled; skipping run");
			return List.of();
		}
		List<IngestionResult> results = new ArrayList<>();
		for (CrimeDataSource source : dataSourceRegistry.getAll()) {
			if (!source.isEnabled()) {
				log.debug("Skipping disabled source {}", source.name());
				continue;
			}
			results.add(ingestSource(source, refresh));
		}
		return results;
	}

	public IngestionResult ingest(String sourceName) {
		return ingest(sourceName, false);
	}

	public IngestionResult ingest(String sourceName, boolean refresh) {
		CrimeDataSource source = dataSourceRegistry.findByName(sourceName)
				.orElseThrow(() -> new ResourceNotFoundException("Unknown ingestion source: " + sourceName));
		return ingestSource(source, refresh);
	}

	public List<String> sourceNames() {
		return dataSourceRegistry.getAll().stream().map(CrimeDataSource::name).toList();
	}

	private IngestionResult ingestSource(CrimeDataSource source, boolean refresh) {
		propagateRefresh(source, refresh);

		IngestionRun run = IngestionRun.builder()
				.source(source.name())
				.status(IngestionRunStatus.RUNNING)
				.refreshRequested(refresh)
				.startedAt(Instant.now())
				.build();
		run = ingestionRunRepository.save(run);
		ingestionContext.begin(run.getId());

		log.info("Ingesting crime data from source {} (run={}, refresh={})", source.name(), run.getId(), refresh);
		if (refresh) {
			log.info("Append-only reingest for {}; existing records will be retained", source.name());
		}

		List<CrimeRecord> records;
		try {
			records = source.fetchRecords();
		}
		catch (Exception ex) {
			log.error("Source {} failed", source.name(), ex);
			failRun(run, ex.getMessage());
			ingestionContext.end();
			return IngestionResult.failure(source.name(), ex.getMessage());
		}

		int inserted = 0;
		int duplicates = 0;
		int failed = 0;
		for (CrimeRecord record : records) {
			try {
				if (isDuplicate(source.name(), record.externalId(), run.getId(), refresh)) {
					duplicates++;
					continue;
				}
				crimeIncidentRepository.save(toIncident(source.name(), record, run.getId()));
				inserted++;
			}
			catch (Exception ex) {
				failed++;
				log.warn("Failed to persist record {} from source {}: {}", record.externalId(), source.name(),
						ex.getMessage());
			}
		}

		run.setDatasetVersionIds(ingestionContext.datasetVersionIds());
		run.setFetched(records.size());
		run.setInserted(inserted);
		run.setDuplicates(duplicates);
		run.setFailed(failed);
		run.setStatus(IngestionRunStatus.COMPLETED);
		run.setCompletedAt(Instant.now());
		ingestionRunRepository.save(run);

		if (inserted > 0 || refresh) {
			activeIngestionRunService.activateRun(source.name(), run.getId());
		}

		ingestionContext.end();

		log.info("Source {} run {}: fetched={} inserted={} duplicates={} failed={}", source.name(), run.getId(),
				records.size(), inserted, duplicates, failed);
		if (inserted > 0) {
			crimeReadCacheEvictor.evictAll();
		}
		return new IngestionResult(source.name(), records.size(), inserted, duplicates, failed, null);
	}

	private boolean isDuplicate(String sourceName, String externalId, String runId, boolean refresh) {
		if (crimeIncidentRepository.existsBySourceAndExternalIdAndIngestionRunId(sourceName, externalId, runId)) {
			return true;
		}
		if (refresh) {
			return false;
		}
		Optional<String> activeRunId = activeIngestionRunService.activeRunIdForSource(sourceName);
		if (activeRunId.isPresent()) {
			return crimeIncidentRepository.existsBySourceAndExternalIdAndIngestionRunId(sourceName, externalId,
					activeRunId.get());
		}
		return crimeIncidentRepository.existsBySourceAndExternalId(sourceName, externalId);
	}

	private void failRun(IngestionRun run, String error) {
		run.setStatus(IngestionRunStatus.FAILED);
		run.setError(error);
		run.setCompletedAt(Instant.now());
		ingestionRunRepository.save(run);
	}

	private static void propagateRefresh(CrimeDataSource source, boolean refresh) {
		if (source instanceof SaCrimeStatisticsDataSource saSource) {
			saSource.setRefresh(refresh);
		}
		if (source instanceof WaCrimeStatisticsDataSource waSource) {
			waSource.setRefresh(refresh);
		}
		if (source instanceof NswBocsarStatisticsDataSource nswSource) {
			nswSource.setRefresh(refresh);
		}
		if (source instanceof TasCrimeStatisticsSupplementDataSource tasSupplementSource) {
			tasSupplementSource.setRefresh(refresh);
		}
		if (source instanceof TasCorporatePerformanceDataSource tasCprSource) {
			tasCprSource.setRefresh(refresh);
		}
		if (source instanceof NtCrimeStatisticsDataSource ntSource) {
			ntSource.setRefresh(refresh);
		}
	}

	private CrimeIncident toIncident(String sourceName, CrimeRecord record, String ingestionRunId) {
		CrimeType crimeType = classify(record);
		GeoJsonPoint point = toPoint(record);
		Instant now = Instant.now();
		RecordGranularity granularity = record.granularity() != null ? record.granularity()
				: RecordGranularity.INCIDENT;
		GeocodeStatus geocodeStatus = record.geocodeStatus() != null ? record.geocodeStatus()
				: (point != null ? GeocodeStatus.RESOLVED : GeocodeStatus.UNRESOLVED);

		return CrimeIncident.builder()
				.source(sourceName)
				.externalId(record.externalId())
				.ingestionRunId(ingestionRunId)
				.title(record.title() != null ? record.title() : "Unclassified offence")
				.description(record.description())
				.crimeType(crimeType)
				.severity(DEFAULT_SEVERITY.getOrDefault(crimeType, CrimeSeverity.LOW))
				.status(CrimeStatus.REPORTED)
				.granularity(granularity)
				.geocodeStatus(geocodeStatus)
				.offenceCount(record.offenceCount())
				.reportingPeriod(record.reportingPeriod())
				.offenderContext(record.offenderContext())
				.location(Location.builder()
						.city(record.suburb() != null ? record.suburb() : "Unknown")
						.state(record.state())
						.country("Australia")
						.postalCode(record.postalCode())
						.suburbId(record.suburbId())
						.coordinates(point)
						.boundary(record.boundary())
						.build())
				.geoCoordinates(point)
				.occurredAt(record.occurredAt())
				.reportedAt(now)
				.createdAt(now)
				.updatedAt(now)
				.build();
	}

	private CrimeType classify(CrimeRecord record) {
		String text = ((record.category() != null ? record.category() : "") + " "
				+ (record.title() != null ? record.title() : "")).toLowerCase(Locale.ROOT);
		return CATEGORY_KEYWORDS.entrySet().stream()
				.filter(entry -> text.contains(entry.getKey()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(CrimeType.OTHER);
	}

	private GeoJsonPoint toPoint(CrimeRecord record) {
		if (record.latitude() == null || record.longitude() == null) {
			return null;
		}
		return new GeoJsonPoint(new Point(record.longitude(), record.latitude()));
	}

}
