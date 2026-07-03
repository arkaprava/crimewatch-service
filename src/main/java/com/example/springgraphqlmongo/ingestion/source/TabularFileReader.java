package com.example.springgraphqlmongo.ingestion.source;

import com.example.springgraphqlmongo.ingestion.IngestionException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

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
import java.util.function.Consumer;

public final class TabularFileReader {

	private static final DataFormatter FORMATTER = new DataFormatter();

	private TabularFileReader() {
	}

	public static void readRows(Path file, List<String> sheetNames, Consumer<Map<String, String>> rowConsumer) {
		String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
		try {
			if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
				readSpreadsheet(file, sheetNames, rowConsumer);
			}
			else {
				readCsv(file, rowConsumer);
			}
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to read tabular file " + file, ex);
		}
	}

	private static void readCsv(Path file, Consumer<Map<String, String>> rowConsumer) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(file)) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				return;
			}
			List<String> headers = List.of(SaOffenderReferenceLoader.parseCsvLine(headerLine));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				rowConsumer.accept(toRow(headers, SaOffenderReferenceLoader.parseCsvLine(line)));
			}
		}
	}

	private static void readSpreadsheet(Path file, List<String> sheetNames, Consumer<Map<String, String>> rowConsumer)
			throws IOException {
		try (InputStream input = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(input)) {
			for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
				Sheet sheet = workbook.getSheetAt(sheetIndex);
				if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
					continue;
				}
				if (sheetNames != null && !sheetNames.isEmpty()
						&& sheetNames.stream().noneMatch(name -> name.equalsIgnoreCase(sheet.getSheetName()))) {
					continue;
				}
				Row headerRow = sheet.getRow(sheet.getFirstRowNum());
				if (headerRow == null) {
					continue;
				}
				List<String> headers = new ArrayList<>();
				headerRow.forEach(cell -> headers.add(FORMATTER.formatCellValue(cell).trim()));
				for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
					Row row = sheet.getRow(rowIndex);
					if (row == null) {
						continue;
					}
					List<String> values = new ArrayList<>();
					for (int col = 0; col < headers.size(); col++) {
						values.add(FORMATTER.formatCellValue(row.getCell(col)).trim());
					}
					if (values.stream().allMatch(String::isBlank)) {
						continue;
					}
					rowConsumer.accept(toRow(headers, values.toArray(new String[0])));
				}
			}
		}
	}

	private static Map<String, String> toRow(List<String> headers, String[] values) {
		Map<String, String> row = new HashMap<>();
		for (int i = 0; i < headers.size(); i++) {
			String header = headers.get(i);
			if (header == null || header.isBlank()) {
				continue;
			}
			String value = i < values.length ? values[i] : null;
			row.put(normaliseHeader(header), value);
		}
		return row;
	}

	static String normaliseHeader(String header) {
		return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	static String columnValue(Map<String, String> row, String column) {
		if (column == null) {
			return null;
		}
		String value = row.get(normaliseHeader(column));
		return value == null ? null : value.replace("\"", "").trim();
	}

}
