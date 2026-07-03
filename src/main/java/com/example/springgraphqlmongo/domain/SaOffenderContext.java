package com.example.springgraphqlmongo.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaOffenderContext {

	private Integer offenderCount;

	private String principalOffence;

	private String correlationNote;

}
