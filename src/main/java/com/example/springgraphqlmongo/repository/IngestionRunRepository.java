package com.example.springgraphqlmongo.repository;

import com.example.springgraphqlmongo.domain.IngestionRun;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface IngestionRunRepository extends MongoRepository<IngestionRun, String> {

}
