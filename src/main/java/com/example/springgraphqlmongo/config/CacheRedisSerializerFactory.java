package com.example.springgraphqlmongo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.serializer.RedisSerializer;

public final class CacheRedisSerializerFactory {

	private CacheRedisSerializerFactory() {
	}

	public static RedisSerializer<Object> createValueSerializer(ObjectMapper objectMapper) {
		return new CrimeCacheValueSerializer(objectMapper);
	}

}
