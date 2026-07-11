package com.example.springgraphqlmongo.ingestion.storage;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local holder for dataset version IDs registered during an ingestion run.
 */
@Component
public class IngestionContext {

	private final ThreadLocal<List<String>> datasetVersionIds = ThreadLocal.withInitial(ArrayList::new);

	private final ThreadLocal<String> ingestionRunId = new ThreadLocal<>();

	public void begin(String runId) {
		ingestionRunId.set(runId);
		datasetVersionIds.get().clear();
	}

	public void end() {
		datasetVersionIds.remove();
		ingestionRunId.remove();
	}

	public String currentRunId() {
		return ingestionRunId.get();
	}

	public void registerDatasetVersion(String versionId) {
		List<String> ids = datasetVersionIds.get();
		if (!ids.contains(versionId)) {
			ids.add(versionId);
		}
	}

	public List<String> datasetVersionIds() {
		return List.copyOf(datasetVersionIds.get());
	}

}
