package com.example.springgraphqlmongo.repository;

import com.example.springgraphqlmongo.domain.DatasetVersion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface DatasetVersionRepository extends MongoRepository<DatasetVersion, String> {

	Optional<DatasetVersion> findTopBySourceAndResourceKeyOrderByVersionDesc(String source, String resourceKey);

	Optional<DatasetVersion> findBySourceAndResourceKeyAndSha256(String source, String resourceKey, String sha256);

}
