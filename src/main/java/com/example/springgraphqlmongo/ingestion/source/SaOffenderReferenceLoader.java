package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.config.IngestionProperties;
import com.example.springgraphqlmongo.ingestion.IngestionException;
import com.example.springgraphqlmongo.ingestion.cache.DatasetTarArchive;
import com.example.springgraphqlmongo.ingestion.cache.SaDatasetCacheService;
import com.example.springgraphqlmongo.ingestion.offence.OffenceCategoryNormaliser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SaOffenderReferenceLoader {

	private final SaDatasetCacheService cacheService;

	private final IngestionProperties properties;

	private final OffenceCategoryNormaliser offenceCategoryNormaliser;

	public Map<String, SaOffenderStats> loadReference(boolean refresh) {
		Path offenderFile = cacheService.resolveOffenderFile(refresh);
		try {
			if (isSpreadsheet(offenderFile)) {
				return parseSpreadsheet(offenderFile);
			}
			return parseCsv(offenderFile);
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to parse SA offender reference file", ex);
		}
	}

	public Map<String, SaOffenderStats> loadReference() {
		return loadReference(properties.getSa().isRefreshOnIngest());
	}

	private Map<String, SaOffenderStats> parseCsv(Path file) throws IOException {
		Map<String, SaOffenderStats> stats = new HashMap<>();
		try (BufferedReader reader = DatasetTarArchive.openCsvReader(file)) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				return stats;
			}
			Map<String, Integer> columns = mapColumns(parseCsvLine(headerLine));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] values = parseCsvLine(line);
				addRow(stats, columns, values);
			}
		}
		log.info("Loaded {} SA offender reference rows from {}", stats.size(), file);
		return stats;
	}

	private Map<String, SaOffenderStats> parseSpreadsheet(Path file) throws IOException {
		Map<String, SaOffenderStats> stats = new HashMap<>();
		DataFormatter formatter = new DataFormatter();
		try (InputStream input = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(input)) {
			Sheet sheet = workbook.getSheetAt(0);
			Row header = sheet.getRow(0);
			if (header == null) {
				return stats;
			}
			Map<String, Integer> columns = new HashMap<>();
			for (int i = 0; i < header.getLastCellNum(); i++) {
				String name = formatter.formatCellValue(header.getCell(i)).trim();
				if (!name.isBlank()) {
					columns.put(normaliseHeader(name), i);
				}
			}
			for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
				Row row = sheet.getRow(rowIndex);
				if (row == null) {
					continue;
				}
				String[] values = new String[columns.size()];
				for (Map.Entry<String, Integer> entry : columns.entrySet()) {
					values[entry.getValue()] = formatter.formatCellValue(row.getCell(entry.getValue())).trim();
				}
				addRow(stats, columns, values);
			}
		}
		log.info("Loaded {} SA offender reference rows from {}", stats.size(), file);
		return stats;
	}

	private void addRow(Map<String, SaOffenderStats> stats, Map<String, Integer> columns, String[] values) {
		String state = value(columns, values, "state");
		String period = value(columns, values, "financialyear", "financial year", "reportingperiod",
				"reporting period");
		String offence = value(columns, values, "principaloffence", "principal offence", "offence");
		String countRaw = value(columns, values, "offendercount", "offender count", "count");
		if (state == null || period == null || offence == null || countRaw == null) {
			return;
		}
		int count;
		try {
			count = (int) Math.round(Double.parseDouble(countRaw.replace(",", "")));
		}
		catch (NumberFormatException ex) {
			return;
		}
		SaOffenderStats offenderStats = SaOffenderStats.builder()
				.state(state.toUpperCase(Locale.ROOT))
				.reportingPeriod(period)
				.principalOffence(offenceCategoryNormaliser.normalise(offence))
				.offenderCount(count)
				.build();
		stats.put(offenceCategoryNormaliser.correlationKey(state, period, offence), offenderStats);
	}

	private String value(Map<String, Integer> columns, String[] values, String... keys) {
		for (String key : keys) {
			Integer index = columns.get(normaliseHeader(key));
			if (index != null && index < values.length && values[index] != null && !values[index].isBlank()) {
				return values[index].trim();
			}
		}
		return null;
	}

	private Map<String, Integer> mapColumns(String[] headers) {
		Map<String, Integer> columns = new HashMap<>();
		for (int i = 0; i < headers.length; i++) {
			columns.put(normaliseHeader(headers[i]), i);
		}
		return columns;
	}

	private String normaliseHeader(String header) {
		return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	static String[] parseCsvLine(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < line.length(); i++) {
			char character = line.charAt(i);
			if (character == '"') {
				if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					current.append('"');
					i++;
				}
				else {
					inQuotes = !inQuotes;
				}
			}
			else if (character == ',' && !inQuotes) {
				fields.add(current.toString());
				current.setLength(0);
			}
			else {
				current.append(character);
			}
		}
		fields.add(current.toString());
		return fields.toArray(String[]::new);
	}

	private boolean isSpreadsheet(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".xls") || name.endsWith(".xlsx");
	}

}
