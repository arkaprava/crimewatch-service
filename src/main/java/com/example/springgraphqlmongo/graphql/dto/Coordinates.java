package com.example.springgraphqlmongo.graphql.dto;

/** GraphQL-facing view of a GeoJSON point. */
public record Coordinates(double latitude, double longitude) {
}
