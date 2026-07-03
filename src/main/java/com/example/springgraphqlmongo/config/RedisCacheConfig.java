package com.example.springgraphqlmongo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
@ConditionalOnProperty(name = "cache.crime.backend", havingValue = "redis")
public class RedisCacheConfig {

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, CacheProperties properties,
			ObjectMapper objectMapper) {
		RedisSerializer<Object> valueSerializer = CacheRedisSerializerFactory.createValueSerializer(objectMapper);
		RedisCacheConfiguration defaults = baseConfiguration(valueSerializer);

		Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
				CrimeCacheNames.CRIMES_NEAR, defaults.entryTtl(properties.getNearLocationTtl()),
				CrimeCacheNames.CRIME_SEARCH, defaults.entryTtl(properties.getSearchTtl()),
				CrimeCacheNames.CRIME_BY_ID, defaults.entryTtl(properties.getByIdTtl()));

		return RedisCacheManager.builder(connectionFactory)
				.cacheDefaults(defaults)
				.withInitialCacheConfigurations(cacheConfigurations)
				.transactionAware()
				.build();
	}

	private static RedisCacheConfiguration baseConfiguration(RedisSerializer<Object> valueSerializer) {
		return RedisCacheConfiguration.defaultCacheConfig()
				.serializeKeysWith(
						RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
				.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer))
				.disableCachingNullValues();
	}

}
