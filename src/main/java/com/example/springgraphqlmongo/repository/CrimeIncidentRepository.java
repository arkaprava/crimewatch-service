package com.example.springgraphqlmongo.repository;

import com.example.springgraphqlmongo.domain.CrimeIncident;
import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CrimeIncidentRepository extends MongoRepository<CrimeIncident, String> {

	boolean existsBySourceAndExternalId(String source, String externalId);

	boolean existsBySourceAndExternalIdAndIngestionRunId(String source, String externalId, String ingestionRunId);

	boolean existsBySourceAndIngestionRunId(String source, String ingestionRunId);

	long countBySource(String source);

	long deleteBySource(String source);

	List<CrimeIncident> findByCrimeType(CrimeType crimeType);

	List<CrimeIncident> findByStatus(CrimeStatus status);

	List<CrimeIncident> findByLocationCity(String city);

}
