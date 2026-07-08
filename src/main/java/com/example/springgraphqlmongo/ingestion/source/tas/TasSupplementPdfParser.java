package com.example.springgraphqlmongo.ingestion.source.tas;

import com.example.springgraphqlmongo.ingestion.IngestionException;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class TasSupplementPdfParser {

	private static final Pattern FINANCIAL_YEAR = Pattern.compile("(\\d{4})-(\\d{2})");

	private static final Pattern MAJOR_CATEGORY = Pattern.compile(
			"^([A-D]\\.\\s+Offences Against (?:the Person|Property)|Fraud and Similar Offences|Other \\(Miscellaneous\\) Offences\\*\\*|Total Offences\\*\\*)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern DETAILED_OFFENCE = Pattern.compile(
			"^([A-Za-z][A-Za-z /\\-']{2,60})\\s+(\\d{1,3}(?:,\\d{3})*)\\s*$");

	private TasSupplementPdfParser() {
	}

	public static List<TasSupplementRow> parse(Path pdf) {
		String text = TasPdfTextExtractor.extractFullText(pdf);
		String financialYear = parseFinancialYear(text, pdf);
		List<TasSupplementRow> rows = new ArrayList<>();
		rows.addAll(parseMajorCategories(text, financialYear));
		rows.addAll(parseDetailedOffences(text, financialYear));
		if (rows.isEmpty()) {
			throw new IngestionException("No offence rows parsed from TAS supplement PDF " + pdf);
		}
		log.info("Parsed {} TAS supplement rows for financial year {}", rows.size(), financialYear);
		return rows;
	}

	static String parseFinancialYear(String text, Path pdf) {
		Matcher matcher = FINANCIAL_YEAR.matcher(text);
		while (matcher.find()) {
			int startYear = Integer.parseInt(matcher.group(1));
			int endSuffix = Integer.parseInt(matcher.group(2));
			if (endSuffix == (startYear + 1) % 100) {
				return matcher.group(1) + "-" + matcher.group(2);
			}
		}
		throw new IngestionException("Unable to determine financial year from TAS supplement PDF " + pdf);
	}

	private static List<TasSupplementRow> parseMajorCategories(String text, String financialYear) {
		List<TasSupplementRow> rows = new ArrayList<>();
		Map<String, Integer> seen = new LinkedHashMap<>();
		boolean inMajorTable = false;
		for (String rawLine : text.split("\\R")) {
			String line = normaliseLine(rawLine);
			if (line.isBlank()) {
				continue;
			}
			if (line.toLowerCase(Locale.ROOT).contains("number of offences recorded and cleared")) {
				inMajorTable = true;
				continue;
			}
			if (!inMajorTable) {
				continue;
			}
			if (line.toLowerCase(Locale.ROOT).startsWith("source:")) {
				break;
			}
			Matcher categoryMatcher = MAJOR_CATEGORY.matcher(line);
			if (!categoryMatcher.find()) {
				continue;
			}
			String category = categoryMatcher.group(1).replaceAll("\\*+$", "").strip();
			List<Integer> numbers = extractTrailingIntegers(line.substring(categoryMatcher.end()));
			if (numbers.isEmpty()) {
				continue;
			}
			int recorded = numbers.getFirst();
			Integer cleared = numbers.size() > 1 ? numbers.get(1) : null;
			if (recorded <= 0 || seen.containsKey(category)) {
				continue;
			}
			seen.put(category, recorded);
			rows.add(TasSupplementRow.builder()
					.financialYear(financialYear)
					.category(category)
					.offence(category)
					.recorded(recorded)
					.cleared(cleared)
					.build());
		}
		return rows;
	}

	private static List<TasSupplementRow> parseDetailedOffences(String text, String financialYear) {
		List<TasSupplementRow> rows = new ArrayList<>();
		String currentCategory = null;
		for (String rawLine : text.split("\\R")) {
			String line = normaliseLine(rawLine);
			if (line.isBlank()) {
				continue;
			}
			String lower = line.toLowerCase(Locale.ROOT);
			if (lower.contains("offences against the person")) {
				currentCategory = "Offences Against the Person";
				continue;
			}
			if (lower.contains("offences against property")) {
				currentCategory = "Offences Against Property";
				continue;
			}
			if (lower.contains("fraud and similar offences")) {
				currentCategory = "Fraud and Similar Offences";
				continue;
			}
			if (lower.contains("other (miscellaneous) offences")) {
				currentCategory = "Other (Miscellaneous) Offences";
				continue;
			}
			if (currentCategory == null || lower.startsWith("source:") || lower.contains("total offences")) {
				continue;
			}
			Matcher offenceMatcher = DETAILED_OFFENCE.matcher(line);
			if (!offenceMatcher.matches()) {
				continue;
			}
			String offence = offenceMatcher.group(1).strip();
			int recorded = parseCount(offenceMatcher.group(2));
			if (recorded <= 0 || offence.length() < 4) {
				continue;
			}
			rows.add(TasSupplementRow.builder()
					.financialYear(financialYear)
					.category(currentCategory)
					.offence(offence)
					.recorded(recorded)
					.cleared(null)
					.build());
		}
		return rows;
	}

	private static String normaliseLine(String line) {
		return line.replace('\u00a0', ' ').replaceAll("\\s{2,}", " ").strip();
	}

	private static List<Integer> extractTrailingIntegers(String remainder) {
		List<Integer> numbers = new ArrayList<>();
		Matcher matcher = Pattern.compile("(\\d{1,3}(?:,\\d{3})*)").matcher(remainder);
		while (matcher.find()) {
			numbers.add(parseCount(matcher.group(1)));
		}
		return numbers;
	}

	private static int parseCount(String raw) {
		return Integer.parseInt(raw.replace(",", ""));
	}

}
