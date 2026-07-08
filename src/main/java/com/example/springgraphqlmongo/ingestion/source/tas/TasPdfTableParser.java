package com.example.springgraphqlmongo.ingestion.source.tas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TasPdfTableParser {

	private TasPdfTableParser() {
	}

	public static List<Map<String, String>> parseTables(List<TasPdfTextExtractor.PositionedLine> lines,
			List<String> headerKeywords) {
		List<Map<String, String>> rows = new ArrayList<>();
		int index = 0;
		while (index < lines.size()) {
			int headerIndex = findHeaderIndex(lines, index, headerKeywords);
			if (headerIndex < 0) {
				break;
			}
			List<TasPdfTextExtractor.PositionedLine> tableLines = collectTableLines(lines, headerIndex);
			if (tableLines.size() >= 2) {
				rows.addAll(parseTableLines(tableLines));
			}
			index = headerIndex + Math.max(tableLines.size(), 1);
		}
		return rows;
	}

	private static int findHeaderIndex(List<TasPdfTextExtractor.PositionedLine> lines, int start,
			List<String> headerKeywords) {
		for (int i = start; i < lines.size(); i++) {
			String lower = lines.get(i).text().toLowerCase();
			if (headerKeywords.stream().anyMatch(lower::contains)) {
				return i;
			}
		}
		return -1;
	}

	private static List<TasPdfTextExtractor.PositionedLine> collectTableLines(
			List<TasPdfTextExtractor.PositionedLine> lines, int headerIndex) {
		List<TasPdfTextExtractor.PositionedLine> tableLines = new ArrayList<>();
		float previousY = lines.get(headerIndex).y();
		for (int i = headerIndex; i < lines.size(); i++) {
			TasPdfTextExtractor.PositionedLine line = lines.get(i);
			if (!tableLines.isEmpty() && previousY - line.y() > 40f) {
				break;
			}
			if (line.text().toLowerCase().startsWith("source:")) {
				break;
			}
			tableLines.add(line);
			previousY = line.y();
		}
		return tableLines;
	}

	private static List<Map<String, String>> parseTableLines(List<TasPdfTextExtractor.PositionedLine> tableLines) {
		List<Map<String, String>> rows = new ArrayList<>();
		List<Float> columnBoundaries = detectColumnBoundaries(tableLines);
		if (columnBoundaries.size() < 2) {
			return rows;
		}

		for (int i = 1; i < tableLines.size(); i++) {
			TasPdfTextExtractor.PositionedLine line = tableLines.get(i);
			List<String> cells = splitIntoCells(line, columnBoundaries);
			if (cells.isEmpty() || cells.stream().allMatch(String::isBlank)) {
				continue;
			}
			Map<String, String> row = new LinkedHashMap<>();
			row.put("col0", cells.getFirst().strip());
			for (int col = 1; col < cells.size(); col++) {
				row.put("col" + col, cells.get(col).strip());
			}
			rows.add(row);
		}
		return rows;
	}

	private static List<Float> detectColumnBoundaries(List<TasPdfTextExtractor.PositionedLine> tableLines) {
		List<Float> starts = new ArrayList<>();
		for (TasPdfTextExtractor.PositionedLine line : tableLines) {
			starts.add(line.minX());
		}
		starts.sort(Comparator.naturalOrder());
		List<Float> boundaries = new ArrayList<>();
		float clusterThreshold = 12f;
		for (float start : starts) {
			if (boundaries.isEmpty() || start - boundaries.getLast() > clusterThreshold) {
				boundaries.add(start);
			}
		}
		return boundaries;
	}

	private static List<String> splitIntoCells(TasPdfTextExtractor.PositionedLine line, List<Float> boundaries) {
		List<String> cells = new ArrayList<>();
		for (int i = 0; i < boundaries.size(); i++) {
			cells.add("");
		}
		int column = locateColumn(line.minX(), boundaries);
		if (column >= 0 && column < cells.size()) {
			cells.set(column, line.text());
		}
		return cells;
	}

	private static int locateColumn(float minX, List<Float> boundaries) {
		int column = 0;
		for (int i = 0; i < boundaries.size(); i++) {
			if (minX + 1 >= boundaries.get(i)) {
				column = i;
			}
		}
		return column;
	}

}
