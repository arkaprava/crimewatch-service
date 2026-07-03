package com.example.springgraphqlmongo.config;

import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.IOException;
import java.util.List;

/**
 * Serializes cached crime read results as JSON. Values are always either a single
 * {@link CrimeIncident} or a {@code List} of them.
 */
public final class CrimeCacheValueSerializer implements RedisSerializer<Object> {

	private static final TypeReference<List<CrimeIncident>> INCIDENT_LIST = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;

	public CrimeCacheValueSerializer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper.copy().findAndRegisterModules().registerModule(new GeoJsonJacksonModule());
	}

	@Override
	public byte[] serialize(Object value) {
		if (value == null) {
			return new byte[0];
		}
		try {
			return objectMapper.writeValueAsBytes(value);
		}
		catch (IOException ex) {
			throw new SerializationException("Could not serialize cache value", ex);
		}
	}

	@Override
	public Object deserialize(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(bytes);
			if (root.isArray()) {
				return objectMapper.convertValue(root, INCIDENT_LIST);
			}
			return objectMapper.treeToValue(root, CrimeIncident.class);
		}
		catch (IOException ex) {
			throw new SerializationException("Could not deserialize cache value", ex);
		}
	}

}
