package com.example.springgraphqlmongo.config;

import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeDataSourceRegistry;
import com.example.springgraphqlmongo.ingestion.source.CkanCrimeDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

	/**
	 * Combines configuration-driven CKAN sources with any custom
	 * {@link CrimeDataSource} beans contributed elsewhere in the application.
	 */
	@Bean
	public CrimeDataSourceRegistry crimeDataSourceRegistry(IngestionProperties properties,
			RestClient.Builder restClientBuilder, ObjectProvider<CrimeDataSource> customSources) {
		List<CrimeDataSource> sources = new ArrayList<>();
		properties.getSources()
				.forEach(config -> sources.add(new CkanCrimeDataSource(config, restClientBuilder)));
		customSources.forEach(sources::add);
		return new CrimeDataSourceRegistry(sources);
	}

}
