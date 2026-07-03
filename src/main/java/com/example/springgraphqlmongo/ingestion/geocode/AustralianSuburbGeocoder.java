package com.example.springgraphqlmongo.ingestion.geocode;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.domain.AustralianSuburb;
import com.example.springgraphqlmongo.domain.GeocodeStatus;
import com.example.springgraphqlmongo.repository.AustralianSuburbRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AustralianSuburbGeocoder {

	private final AustralianSuburbRepository suburbRepository;

	private final IngestionProperties properties;

	private final JaroWinklerSimilarity similarity = new JaroWinklerSimilarity();

	public SuburbMatch resolve(String rawSuburb, String state) {
		if (rawSuburb == null || rawSuburb.isBlank()) {
			return SuburbMatch.unresolved("Unknown");
		}
		String normalised = normalise(rawSuburb);
		String stateCode = state != null ? state.toUpperCase(Locale.ROOT) : null;

		if (stateCode != null) {
			Optional<AustralianSuburb> exact = suburbRepository.findByStateAndNameIgnoreCase(stateCode, normalised);
			if (exact.isPresent()) {
				return toMatch(exact.get(), GeocodeStatus.RESOLVED);
			}

			List<AustralianSuburb> aliasMatches = suburbRepository
					.findByStateAndAliasesContainingIgnoreCase(stateCode, normalised);
			if (!aliasMatches.isEmpty()) {
				return toMatch(aliasMatches.getFirst(), GeocodeStatus.RESOLVED);
			}

			Optional<AustralianSuburb> fuzzy = fuzzyMatch(stateCode, normalised);
			if (fuzzy.isPresent()) {
				log.debug("Fuzzy matched suburb '{}' to '{}'", rawSuburb, fuzzy.get().getName());
				return toMatch(fuzzy.get(), GeocodeStatus.APPROXIMATE);
			}
		}

		log.warn("Unresolved suburb '{}' in state {}", rawSuburb, state);
		return SuburbMatch.unresolved(rawSuburb);
	}

	private Optional<AustralianSuburb> fuzzyMatch(String state, String normalised) {
		double threshold = properties.getSuburbs().getFuzzyMatchThreshold();
		return suburbRepository.findByState(state).stream()
				.map(suburb -> new ScoredSuburb(suburb, bestScore(normalised, suburb)))
				.filter(scored -> scored.score() >= threshold)
				.max(Comparator.comparingDouble(ScoredSuburb::score))
				.map(ScoredSuburb::suburb);
	}

	private double bestScore(String normalised, AustralianSuburb suburb) {
		double best = similarity.apply(normalised, normalise(suburb.getName()));
		if (suburb.getAliases() != null) {
			for (String alias : suburb.getAliases()) {
				best = Math.max(best, similarity.apply(normalised, normalise(alias)));
			}
		}
		return best;
	}

	private SuburbMatch toMatch(AustralianSuburb suburb, GeocodeStatus status) {
		return SuburbMatch.builder()
				.suburb(suburb)
				.status(status)
				.canonicalName(suburb.getName())
				.build();
	}

	static String normalise(String value) {
		return value.trim()
				.replaceAll("(?i)\\s*\\(?(SA|NSW|VIC|QLD|WA|TAS|NT|ACT)\\)?\\s*$", "")
				.replaceAll("[^a-zA-Z0-9\\s]", " ")
				.replaceAll("\\s+", " ")
				.toUpperCase(Locale.ROOT);
	}

	private record ScoredSuburb(AustralianSuburb suburb, double score) {
	}

}
