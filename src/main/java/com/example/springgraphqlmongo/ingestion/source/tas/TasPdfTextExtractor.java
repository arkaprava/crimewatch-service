package com.example.springgraphqlmongo.ingestion.source.tas;

import com.example.springgraphqlmongo.ingestion.IngestionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TasPdfTextExtractor {

	private TasPdfTextExtractor() {
	}

	public static String extractFullText(Path pdf) {
		try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			return stripper.getText(document);
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to extract text from PDF " + pdf, ex);
		}
	}

	public static List<PositionedLine> extractPositionedLines(Path pdf) {
		try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
			LineCollectingStripper stripper = new LineCollectingStripper();
			stripper.getText(document);
			return stripper.lines();
		}
		catch (IOException ex) {
			throw new IngestionException("Failed to extract positioned text from PDF " + pdf, ex);
		}
	}

	public record PositionedLine(String text, float minX, float maxX, float y) {
	}

	private static final class LineCollectingStripper extends PDFTextStripper {

		private final List<TextPosition> currentLine = new ArrayList<>();

		private final List<PositionedLine> lines = new ArrayList<>();

		private float currentY = Float.NaN;

		private LineCollectingStripper() throws IOException {
			super();
			setSortByPosition(true);
		}

		@Override
		protected void writeString(String text, List<TextPosition> textPositions) {
			for (TextPosition position : textPositions) {
				float y = Math.round(position.getYDirAdj());
				if (!currentLine.isEmpty() && (Float.isNaN(currentY) || Math.abs(y - currentY) > 2.5f)) {
					flushLine();
				}
				currentY = y;
				currentLine.add(position);
			}
		}

		@Override
		protected void writeLineSeparator() {
			flushLine();
		}

		@Override
		protected void writeParagraphEnd() {
			flushLine();
		}

		@Override
		protected void writePageEnd() {
			flushLine();
		}

		private void flushLine() {
			if (currentLine.isEmpty()) {
				return;
			}
			currentLine.sort(Comparator.comparing(TextPosition::getXDirAdj));
			StringBuilder builder = new StringBuilder();
			float minX = Float.MAX_VALUE;
			float maxX = Float.MIN_VALUE;
			for (TextPosition position : currentLine) {
				builder.append(position.getUnicode());
				minX = Math.min(minX, position.getXDirAdj());
				maxX = Math.max(maxX, position.getXDirAdj() + position.getWidthDirAdj());
			}
			String text = builder.toString().strip();
			if (!text.isEmpty()) {
				lines.add(new PositionedLine(text, minX, maxX, currentY));
			}
			currentLine.clear();
			currentY = Float.NaN;
		}

		private List<PositionedLine> lines() {
			flushLine();
			return List.copyOf(lines);
		}

	}

}
