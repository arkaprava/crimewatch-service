package com.example.springgraphqlmongo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * JSON Redis value serializer for cached {@code CrimeIncident} graphs, including
 * {@code java.time} fields and Spring Data geo types.
 */
public final class CacheRedisSerializerFactory {

	private CacheRedisSerializerFactory() {
	}

	public static RedisSerializer<Object> createValueSerializer(ObjectMapper objectMapper) {
		ObjectMapper cacheMapper = objectMapper.copy();
		return new GenericJackson2JsonRedisSerializer(cacheMapper);
	}

}
