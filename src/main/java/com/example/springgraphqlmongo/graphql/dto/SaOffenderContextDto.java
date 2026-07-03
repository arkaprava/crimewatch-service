package com.example.springgraphqlmongo.graphql.dto;

import com.example.springgraphqlmongo.domain.SaOffenderContext;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SaOffenderContextDto {

	Integer offenderCount;

	String principalOffence;

	String correlationNote;

	public static SaOffenderContextDto from(SaOffenderContext context) {
		if (context == null) {
			return null;
		}
		return SaOffenderContextDto.builder()
				.offenderCount(context.getOffenderCount())
				.principalOffence(context.getPrincipalOffence())
				.correlationNote(context.getCorrelationNote())
				.build();
	}

}
