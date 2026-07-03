package com.example.springgraphqlmongo.repository;

import com.example.springgraphqlmongo.domain.AustralianSuburb;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AustralianSuburbRepository extends MongoRepository<AustralianSuburb, String> {

	List<AustralianSuburb> findByState(String state);

	Optional<AustralianSuburb> findByStateAndNameIgnoreCase(String state, String name);

	List<AustralianSuburb> findByStateAndAliasesContainingIgnoreCase(String state, String alias);

}
