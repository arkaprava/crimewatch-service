package com.example.springgraphqlmongo.ingestion.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DatasetFileValidator {

	private DatasetFileValidator() {
	}

	public static boolean looksLikeDataset(byte[] bytes) {
		if (bytes == null || bytes.length < 4) {
			return false;
		}
		if (bytes[0] == 'P' && bytes[1] == 'K') {
			return true;
		}
		String prefix = new String(bytes, 0, Math.min(bytes.length, 200));
		String trimmed = prefix.stripLeading();
		if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html")) {
			return false;
		}
		return prefix.contains(",") || prefix.contains("Geography") || prefix.contains("Location")
				|| prefix.contains("Suburb") || prefix.contains("State");
	}

	public static boolean isReadableDataset(Path file) {
		if (DatasetTarArchive.exists(file)) {
			return DatasetTarArchive.isReadableCsvDataset(file);
		}
		try {
			byte[] prefix = Files.readAllBytes(file);
			return prefix.length > 0 && looksLikeDataset(prefix);
		}
		catch (IOException ex) {
			return false;
		}
	}

}
