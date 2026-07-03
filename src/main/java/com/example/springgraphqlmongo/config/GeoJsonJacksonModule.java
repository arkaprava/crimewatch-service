package com.example.springgraphqlmongo.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import java.io.IOException;

final class GeoJsonJacksonModule extends SimpleModule {

	GeoJsonJacksonModule() {
		addSerializer(GeoJsonPoint.class, new GeoJsonPointSerializer());
		addDeserializer(GeoJsonPoint.class, new GeoJsonPointDeserializer());
	}

	private static final class GeoJsonPointSerializer extends JsonSerializer<GeoJsonPoint> {

		@Override
		public void serialize(GeoJsonPoint value, JsonGenerator generator, SerializerProvider serializers)
				throws IOException {
			generator.writeStartObject();
			generator.writeNumberField("x", value.getX());
			generator.writeNumberField("y", value.getY());
			generator.writeEndObject();
		}

	}

	private static final class GeoJsonPointDeserializer extends JsonDeserializer<GeoJsonPoint> {

		@Override
		public GeoJsonPoint deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			JsonNode node = parser.getCodec().readTree(parser);
			return new GeoJsonPoint(node.get("x").asDouble(), node.get("y").asDouble());
		}

	}

}
