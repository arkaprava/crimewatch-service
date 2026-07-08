package com.example.springgraphqlmongo.config;

import com.example.springgraphqlmongo.ingestion.CrimeDataSource;
import com.example.springgraphqlmongo.ingestion.CrimeDataSourceRegistry;
import com.example.springgraphqlmongo.ingestion.cache.NswDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.cache.SaDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.cache.TasDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.cache.WaDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.geocode.AustralianSuburbGeocoder;
import com.example.springgraphqlmongo.ingestion.geocode.TasGeographyResolver;
import com.example.springgraphqlmongo.ingestion.geocode.WaGeographyResolver;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import com.example.springgraphqlmongo.ingestion.period.ReportingPeriodResolver;
import com.example.springgraphqlmongo.ingestion.source.CkanCrimeDataSource;
import com.example.springgraphqlmongo.ingestion.source.NswBocsarStatisticsDataSource;
import com.example.springgraphqlmongo.ingestion.source.SaCrimeStatisticsDataSource;
import com.example.springgraphqlmongo.ingestion.source.SaOffenderReferenceLoader;
import com.example.springgraphqlmongo.ingestion.source.TasCorporatePerformanceDataSource;
import com.example.springgraphqlmongo.ingestion.source.TasCrimeStatisticsSupplementDataSource;
import com.example.springgraphqlmongo.ingestion.source.WaCrimeStatisticsDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfig {

	@Bean
	public CrimeDataSourceRegistry crimeDataSourceRegistry(IngestionProperties properties,
			RestClient.Builder restClientBuilder, ObjectProvider<CrimeDataSource> customSources,
			SaDatasetCacheService cacheService, SaOffenderReferenceLoader offenderReferenceLoader,
			WaDatasetCacheService waCacheService, NswDatasetCacheService nswCacheService,
			TasDatasetCacheService tasCacheService, AustralianSuburbGeocoder suburbGeocoder,
			WaGeographyResolver waGeographyResolver, TasGeographyResolver tasGeographyResolver,
			OffenceCategoryNormaliser offenceCategoryNormaliser, ReportingPeriodResolver reportingPeriodResolver) {
		List<CrimeDataSource> sources = new ArrayList<>();
		properties.getSources().forEach(config -> {
			String type = config.getType() != null ? config.getType() : "ckan";
			if ("sa-crime-statistics".equalsIgnoreCase(type)) {
				sources.add(new SaCrimeStatisticsDataSource(config, properties, cacheService,
						offenderReferenceLoader, suburbGeocoder, offenceCategoryNormaliser));
			}
			else if ("wa-crime-statistics".equalsIgnoreCase(type)) {
				sources.add(new WaCrimeStatisticsDataSource(config, waCacheService, waGeographyResolver,
						offenceCategoryNormaliser, reportingPeriodResolver));
			}
			else if ("nsw-bocsar-statistics".equalsIgnoreCase(type)) {
				sources.add(new NswBocsarStatisticsDataSource(config, nswCacheService, suburbGeocoder,
						offenceCategoryNormaliser));
			}
			else if ("tas-crime-statistics-supplement".equalsIgnoreCase(type)) {
				sources.add(new TasCrimeStatisticsSupplementDataSource(config, tasCacheService, tasGeographyResolver,
						offenceCategoryNormaliser));
			}
			else if ("tas-corporate-performance".equalsIgnoreCase(type)) {
				sources.add(new TasCorporatePerformanceDataSource(config, tasCacheService, tasGeographyResolver,
						offenceCategoryNormaliser));
			}
			else {
				sources.add(new CkanCrimeDataSource(config, restClientBuilder));
			}
		});
		customSources.forEach(sources::add);
		return new CrimeDataSourceRegistry(sources);
	}

}
