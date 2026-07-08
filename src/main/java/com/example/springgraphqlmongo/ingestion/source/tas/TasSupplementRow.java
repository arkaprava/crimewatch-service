package com.example.springgraphqlmongo.ingestion.source.tas;

import lombok.Builder;

@Builder
public record TasSupplementRow(
		String financialYear,
		String category,
		String offence,
		int recorded,
		Integer cleared) {
}
