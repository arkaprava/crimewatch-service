package com.example.springgraphqlmongo.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "source_ingestion_state")
public class SourceIngestionState {

	@Id
	private String id;

	@Indexed(unique = true)
	private String source;

	private String activeIngestionRunId;

	private Instant updatedAt;

}
