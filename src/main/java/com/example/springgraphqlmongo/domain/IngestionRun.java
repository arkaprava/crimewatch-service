package com.example.springgraphqlmongo.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ingestion_runs")
@CompoundIndex(name = "source_started_at_idx", def = "{'source': 1, 'startedAt': -1}")
public class IngestionRun {

	@Id
	private String id;

	@Indexed
	private String source;

	@Builder.Default
	private List<String> datasetVersionIds = new ArrayList<>();

	private IngestionRunStatus status;

	private boolean refreshRequested;

	private Instant startedAt;

	private Instant completedAt;

	private int fetched;

	private int inserted;

	private int duplicates;

	private int failed;

	private String error;

}
