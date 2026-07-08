package com.example.springgraphqlmongo.ingestion.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetTarArchiveTest {

	@TempDir
	Path tempDir;

	@Test
	void readsCsvDirectlyFromTarGzArchive() throws Exception {
		Path csv = tempDir.resolve("sample.csv");
		Files.writeString(csv, "Suburb,Offence category,Count\nParramatta,Assault,1\n");

		Path datasetPath = tempDir.resolve("sample.csv");
		DatasetTarArchive.writeCsvArchive(csv, datasetPath);

		assertThat(Files.exists(DatasetTarArchive.preferredArchivePath(datasetPath))).isTrue();
		try (BufferedReader reader = DatasetTarArchive.openCsvReader(datasetPath)) {
			assertThat(reader.readLine()).contains("Suburb");
			assertThat(reader.readLine()).contains("Parramatta");
		}
	}

	@Test
	void readsMultipartTarArchive() throws Exception {
		byte[] csvBytes = "Suburb,Offence category,Count\nParramatta,Assault,1\n".getBytes(StandardCharsets.UTF_8);
		byte[] tarBytes = DatasetTarArchive.toTar(csvBytes, "sample.csv");
		Path datasetPath = tempDir.resolve("sample.csv");
		Path archivePath = Path.of(datasetPath + ".tar");
		int splitAt = Math.max(1, tarBytes.length / 2);
		Files.write(Path.of(archivePath + ".part001"), java.util.Arrays.copyOfRange(tarBytes, 0, splitAt));
		Files.write(Path.of(archivePath + ".part002"), java.util.Arrays.copyOfRange(tarBytes, splitAt, tarBytes.length));

		try (BufferedReader reader = DatasetTarArchive.openCsvReader(datasetPath)) {
			assertThat(reader.readLine()).contains("Suburb");
			assertThat(reader.readLine()).contains("Parramatta");
		}
	}

	@Test
	void committedNswArchivePartsDoNotExceedFiftyMegabytes() throws Exception {
		Path datasetPath = Path.of("data/nsw/crime-statistics/suburb-data.csv");
		Optional<Path> archive = DatasetTarArchive.resolveArchive(datasetPath);
		assertThat(archive).isPresent();
		for (Path part : DatasetTarArchive.listMultipartParts(archive.get())) {
			assertThat(Files.size(part)).isLessThanOrEqualTo(DatasetTarArchive.MAX_PART_BYTES);
		}
	}

	@Test
	void readsCommittedNswArchive() throws Exception {
		Path datasetPath = Path.of("data/nsw/crime-statistics/suburb-data.csv");
		assertThat(DatasetTarArchive.exists(datasetPath)).isTrue();
		try (BufferedReader reader = DatasetTarArchive.openCsvReader(datasetPath)) {
			assertThat(reader.readLine()).contains("Suburb");
		}
	}

}
