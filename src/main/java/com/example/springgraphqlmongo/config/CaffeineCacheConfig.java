package com.example.springgraphqlmongo.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
@ConditionalOnProperty(name = "cache.crime.backend", havingValue = "caffeine", matchIfMissing = true)
public class CaffeineCacheConfig {

	@Bean
	public CacheManager cacheManager(CacheProperties properties) {
		List<Cache> caches = List.of(
				buildCache(CrimeCacheNames.CRIMES_NEAR, properties.getNearLocationTtl(), properties.getMaximumSize()),
				buildCache(CrimeCacheNames.CRIME_SEARCH, properties.getSearchTtl(), properties.getMaximumSize()),
				buildCache(CrimeCacheNames.CRIME_BY_ID, properties.getByIdTtl(), properties.getMaximumSize()));
		SimpleCacheManager cacheManager = new SimpleCacheManager();
		cacheManager.setCaches(caches);
		return cacheManager;
	}

	private static CaffeineCache buildCache(String name, Duration ttl, int maximumSize) {
		return new CaffeineCache(name, Caffeine.newBuilder()
				.expireAfterWrite(ttl)
				.maximumSize(maximumSize)
				.recordStats()
				.build());
	}

}
