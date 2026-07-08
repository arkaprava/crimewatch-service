package com.example.springgraphqlmongo.ingestion;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.repository.CrimeIncidentRepository;
import com.example.springgraphqlmongo.service.CrimeIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrimeIngestionStartupLoader {

	private final IngestionProperties properties;

	private final CrimeDataSourceRegistry dataSourceRegistry;

	private final CrimeIncidentRepository crimeIncidentRepository;

	private final CrimeIngestionService crimeIngestionService;

	@Async
	@EventListener(ApplicationReadyEvent.class)
	@Order(Ordered.LOWEST_PRECEDENCE)
	public void ingestMissingSourcesOnStartup() {
		if (!properties.isEnabled() || !properties.isLoadOnStartup()) {
			return;
		}
		for (CrimeDataSource source : dataSourceRegistry.getAll()) {
			if (!source.isEnabled()) {
				continue;
			}
			long existing = crimeIncidentRepository.countBySource(source.name());
			if (existing > 0) {
				log.debug("Skipping startup ingestion for {}; {} records already present", source.name(), existing);
				continue;
			}
			log.info("No records for enabled source {}; running startup ingestion", source.name());
			IngestionResult result = crimeIngestionService.ingest(source.name());
			if (result.error() != null) {
				log.warn("Startup ingestion failed for {}: {}", source.name(), result.error());
			}
			else {
				log.info("Startup ingestion for {}: fetched={} inserted={} duplicates={} failed={}",
						source.name(), result.fetched(), result.inserted(), result.duplicates(), result.failed());
			}
		}
	}

}
