package com.example.springgraphqlmongo.ingestion;

import java.util.List;
import java.util.Optional;

/** Holds every configured and custom {@link CrimeDataSource} known to the application. */
public class CrimeDataSourceRegistry {

	private final List<CrimeDataSource> sources;

	public CrimeDataSourceRegistry(List<CrimeDataSource> sources) {
		this.sources = List.copyOf(sources);
	}

	public List<CrimeDataSource> getAll() {
		return sources;
	}

	public Optional<CrimeDataSource> findByName(String name) {
		return sources.stream().filter(source -> source.name().equals(name)).findFirst();
	}

}
