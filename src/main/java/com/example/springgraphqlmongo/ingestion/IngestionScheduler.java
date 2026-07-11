package com.example.springgraphqlmongo.ingestion;

import com.example.springgraphqlmongo.service.CrimeIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ingestion.schedule", name = "enabled", havingValue = "true")
public class IngestionScheduler {

	private final CrimeIngestionService crimeIngestionService;

	@Scheduled(cron = "${ingestion.schedule.cron:0 0 3,15 * * *}")
	public void runScheduledIngestion() {
		log.info("Starting scheduled crime data ingestion");
		crimeIngestionService.ingestAll()
				.forEach(result -> log.info("Ingestion result: {}", result));
	}

}
