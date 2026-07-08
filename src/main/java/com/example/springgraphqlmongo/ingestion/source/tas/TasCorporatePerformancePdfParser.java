package com.example.springgraphqlmongo.ingestion.source.tas;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class TasCorporatePerformancePdfParser {

	private static final Pattern REPORT_PERIOD = Pattern.compile(
			"Corporate Performance Report\\s+(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{4})",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern DIVISION_ROW = Pattern.compile(
			"^(HOBART|GLENORCHY|KINGSTON|BRIDGEWATER|CLARENCE|EAST COAST|LAUNCESTON|ST HELENS|NORTH-EAST|CENTRAL NORTH|BURNIE|DEVONPORT|CENTRAL WEST|UNKNOWN)\\b.*?(\\d{1,3}(?:,\\d{3})*)\\s+(\\d{1,3}(?:,\\d{3})*)\\s+(-?\\d{1,3}(?:,\\d{3})*)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern DISTRICT_ROW = Pattern.compile(
			"^(SOUTH|NORTH|WEST|STATE)\\b.*?(\\d{1,3}(?:,\\d{3})*)\\s+(\\d{1,3}(?:,\\d{3})*)\\s+(-?\\d{1,3}(?:,\\d{3})*)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern OFFENCE_TYPE_ROW = Pattern.compile(
			"^([A-Za-z][A-Za-z /:()\\-']{3,80})\\s+(\\d{1,3}(?:,\\d{3})*)\\s+(\\d{1,3}(?:,\\d{3})*)\\s+(-?\\d{1,3}(?:,\\d{3})*)");

	private TasCorporatePerformancePdfParser() {
	}

	public static List<TasCorporatePerformanceRow> parse(Path pdf) {
		String text = TasPdfTextExtractor.extractFullText(pdf);
		String reportPeriod = parseReportPeriod(text, pdf);
		List<TasCorporatePerformanceRow> rows = new ArrayList<>();
		rows.addAll(parseGeographyRows(text, reportPeriod));
		rows.addAll(parseStateOffenceRows(text, reportPeriod));
		if (rows.isEmpty()) {
			log.warn("No rows parsed from TAS corporate performance PDF {}; trying positioned fallback", pdf);
			rows.addAll(parseFromPositionedLines(pdf, reportPeriod));
		}
		log.info("Parsed {} TAS corporate performance rows for period {}", rows.size(), reportPeriod);
		return rows;
	}

	static String parseReportPeriod(String text, Path pdf) {
		Matcher matcher = REPORT_PERIOD.matcher(text);
		if (matcher.find()) {
			String month = matcher.group(1).substring(0, 3);
			return month + " " + matcher.group(2);
		}
		Matcher alt = Pattern.compile("(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{4})")
				.matcher(text);
		if (alt.find()) {
			return alt.group(1).substring(0, 3) + " " + alt.group(2);
		}
		throw new com.example.springgraphqlmongo.ingestion.IngestionException(
				"Unable to determine report period from TAS corporate performance PDF " + pdf);
	}

	private static List<TasCorporatePerformanceRow> parseGeographyRows(String text, String reportPeriod) {
		List<TasCorporatePerformanceRow> rows = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		String currentSection = "District Annual Offences";
		for (String rawLine : text.split("\\R")) {
			String line = normaliseLine(rawLine);
			if (line.isBlank()) {
				continue;
			}
			String lower = line.toLowerCase(Locale.ROOT);
			if (lower.contains("district annual offences")) {
				currentSection = "District Annual Offences";
				continue;
			}
			if (lower.contains("offences against the person")) {
				currentSection = "Offences Against the Person";
				continue;
			}
			if (lower.contains("offences against property")) {
				currentSection = "Offences Against Property";
				continue;
			}
			if (lower.startsWith("source:")) {
				continue;
			}

			Matcher divisionMatcher = DIVISION_ROW.matcher(line);
			if (divisionMatcher.find()) {
				addGeographyRow(rows, seen, reportPeriod, "division", divisionMatcher.group(1), currentSection,
						parseCount(divisionMatcher.group(3)));
				continue;
			}
			Matcher districtMatcher = DISTRICT_ROW.matcher(line);
			if (districtMatcher.find()) {
				addGeographyRow(rows, seen, reportPeriod, "district", districtMatcher.group(1), currentSection,
						parseCount(districtMatcher.group(3)));
			}
		}
		return rows;
	}

	private static List<TasCorporatePerformanceRow> parseStateOffenceRows(String text, String reportPeriod) {
		List<TasCorporatePerformanceRow> rows = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		String currentSection = "Offences Against Police";
		boolean inStateSection = false;
		for (String rawLine : text.split("\\R")) {
			String line = normaliseLine(rawLine);
			if (line.isBlank()) {
				continue;
			}
			String lower = line.toLowerCase(Locale.ROOT);
			if (lower.contains("offences against police")) {
				currentSection = "Offences Against Police";
				inStateSection = true;
				continue;
			}
			if (lower.contains("total person offences")) {
				currentSection = "Total Person Offences";
				inStateSection = true;
				continue;
			}
			if (lower.startsWith("source:")) {
				inStateSection = false;
				continue;
			}
			if (!inStateSection) {
				continue;
			}
			Matcher offenceMatcher = OFFENCE_TYPE_ROW.matcher(line);
			if (!offenceMatcher.matches()) {
				continue;
			}
			String offenceType = offenceMatcher.group(1).strip();
			int count = parseCount(offenceMatcher.group(3));
			if (count <= 0) {
				continue;
			}
			String key = reportPeriod + ":state:" + currentSection + ":" + offenceType;
			if (!seen.add(key)) {
				continue;
			}
			rows.add(TasCorporatePerformanceRow.builder()
					.reportPeriod(reportPeriod)
					.geographyLevel("state")
					.geographyName("STATE")
					.offenceSection(currentSection)
					.offenceType(offenceType)
					.count(count)
					.build());
		}
		return rows;
	}

	private static List<TasCorporatePerformanceRow> parseFromPositionedLines(Path pdf, String reportPeriod) {
		List<TasCorporatePerformanceRow> rows = new ArrayList<>();
		List<TasPdfTextExtractor.PositionedLine> lines = TasPdfTextExtractor.extractPositionedLines(pdf);
		Set<String> seen = new LinkedHashSet<>();
		for (TasPdfTextExtractor.PositionedLine line : lines) {
			String text = normaliseLine(line.text());
			Matcher divisionMatcher = DIVISION_ROW.matcher(text);
			if (divisionMatcher.find()) {
				addGeographyRow(rows, seen, reportPeriod, "division", divisionMatcher.group(1),
						"District Annual Offences", parseCount(divisionMatcher.group(3)));
				continue;
			}
			Matcher districtMatcher = DISTRICT_ROW.matcher(text);
			if (districtMatcher.find()) {
				addGeographyRow(rows, seen, reportPeriod, "district", districtMatcher.group(1),
						"District Annual Offences", parseCount(districtMatcher.group(3)));
			}
		}
		return rows;
	}

	private static void addGeographyRow(List<TasCorporatePerformanceRow> rows, Set<String> seen, String reportPeriod,
			String geographyLevel, String geographyName, String offenceSection, int count) {
		if (count <= 0 || "UNKNOWN".equalsIgnoreCase(geographyName)) {
			return;
		}
		String key = reportPeriod + ":" + geographyLevel + ":" + geographyName + ":" + offenceSection;
		if (!seen.add(key)) {
			return;
		}
		rows.add(TasCorporatePerformanceRow.builder()
				.reportPeriod(reportPeriod)
				.geographyLevel(geographyLevel)
				.geographyName(geographyName.toUpperCase(Locale.ROOT))
				.offenceSection(offenceSection)
				.offenceType(offenceSection)
				.count(count)
				.build());
	}

	private static String normaliseLine(String line) {
		return line.replace('\u00a0', ' ').replaceAll("\\s{2,}", " ").strip();
	}

	private static int parseCount(String raw) {
		return Integer.parseInt(raw.replace(",", ""));
	}

}
