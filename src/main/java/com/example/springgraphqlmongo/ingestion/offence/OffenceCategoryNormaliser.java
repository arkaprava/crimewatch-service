package com.example.springgraphqlmongo.ingestion.offence;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class OffenceCategoryNormaliser {

	private static final Map<String, String> NORMALISED = Map.ofEntries(
			Map.entry("assault", "Assault"),
			Map.entry("serious assault", "Assault"),
			Map.entry("common assault", "Assault"),
			Map.entry("theft", "Theft"),
			Map.entry("motor vehicle theft", "Theft"),
			Map.entry("steal", "Theft"),
			Map.entry("shop stealing", "Theft"),
			Map.entry("robbery", "Robbery"),
			Map.entry("burglary", "Burglary"),
			Map.entry("unlawful entry", "Burglary"),
			Map.entry("property damage", "Property Damage"),
			Map.entry("graffiti", "Property Damage"),
			Map.entry("arson", "Arson"),
			Map.entry("fraud", "Fraud"),
			Map.entry("deception", "Fraud"),
			Map.entry("drug", "Drug Offences"),
			Map.entry("homicide", "Homicide"),
			Map.entry("murder", "Homicide"),
			Map.entry("abduction", "Abduction"),
			Map.entry("kidnap", "Abduction"));

	public String normalise(String raw) {
		if (raw == null || raw.isBlank()) {
			return "Other";
		}
		String lower = raw.toLowerCase(Locale.ROOT);
		return NORMALISED.entrySet().stream()
				.filter(entry -> lower.contains(entry.getKey()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(raw.trim());
	}

	public String correlationKey(String state, String reportingPeriod, String offence) {
		return state.toUpperCase(Locale.ROOT) + "|" + reportingPeriod + "|" + normalise(offence);
	}

}
