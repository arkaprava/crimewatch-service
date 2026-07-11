package com.example.springgraphqlmongo.repository;

import com.example.springgraphqlmongo.domain.SourceIngestionState;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SourceIngestionStateRepository extends MongoRepository<SourceIngestionState, String> {

	Optional<SourceIngestionState> findBySource(String source);

}
