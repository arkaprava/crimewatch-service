package com.example.springgraphqlmongo.ingestion.cache;

import com.example.springgraphqlmongo.ingestion.IngestionException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stores large CSV datasets as gzip-compressed tar archives (optionally split
 * into 50 MB parts) and reads CSV rows directly from the archive without
 * extracting to disk.
 */
public final class DatasetTarArchive {

	public static final long MAX_PART_BYTES = 50_000_000L;

	private static final Pattern PART_SUFFIX = Pattern.compile("\\.part\\d{3}$");

	private DatasetTarArchive() {
	}

	public static boolean exists(Path datasetPath) {
		return resolveArchive(datasetPath).isPresent() || Files.isRegularFile(datasetPath);
	}

	public static Optional<Path> resolveArchive(Path datasetPath) {
		if (datasetPath == null) {
			return Optional.empty();
		}
		Path multipart = firstMultipartPart(datasetPath);
		if (multipart != null) {
			return Optional.of(multipart);
		}
		for (String suffix : List.of(".tar.gz", ".tgz", ".tar")) {
			Path candidate = Path.of(datasetPath.toString() + suffix);
			if (Files.isRegularFile(candidate)) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	public static Path preferredArchivePath(Path datasetPath) {
		return Path.of(datasetPath.toString() + ".tar.gz");
	}

	public static void writeCsvArchive(byte[] csvBytes, String entryName, Path datasetPath) throws IOException {
		byte[] tarBytes = toTar(csvBytes, entryName);
		Path archivePath = preferredArchivePath(datasetPath);
		Files.createDirectories(archivePath.getParent());
		deleteArchiveParts(archivePath);
		deleteArchiveParts(Path.of(datasetPath.toString() + ".tar"));
		if (tarBytes.length <= MAX_PART_BYTES) {
			Files.write(archivePath, gzip(tarBytes));
			return;
		}
		writeMultipart(Path.of(datasetPath.toString() + ".tar"), tarBytes);
	}

	public static void writeCsvArchive(Path csvFile, Path datasetPath) throws IOException {
		writeCsvArchive(Files.readAllBytes(csvFile), csvFile.getFileName().toString(), datasetPath);
	}

	public static BufferedReader openCsvReader(Path datasetPath) throws IOException {
		Optional<Path> archive = resolveArchive(datasetPath);
		if (archive.isPresent()) {
			return openCsvReaderFromArchive(archive.get(), datasetPath.getFileName().toString());
		}
		if (Files.isRegularFile(datasetPath)) {
			return Files.newBufferedReader(datasetPath);
		}
		throw new IngestionException("Dataset not found at " + datasetPath);
	}

	public static boolean isReadableCsvDataset(Path datasetPath) {
		try (BufferedReader reader = openCsvReader(datasetPath)) {
			char[] prefix = new char[512];
			int read = reader.read(prefix);
			if (read <= 0) {
				return false;
			}
			String sample = new String(prefix, 0, read);
			return sample.contains(",") || sample.contains("Suburb") || sample.contains("Geography")
					|| sample.contains("Location");
		}
		catch (Exception ex) {
			return false;
		}
	}

	public static byte[] readCsvPrefix(Path datasetPath, int maxBytes) throws IOException {
		Optional<Path> archive = resolveArchive(datasetPath);
		if (archive.isPresent()) {
			try (InputStream csvStream = openCsvInputStream(archive.get(), datasetPath.getFileName().toString())) {
				return csvStream.readNBytes(maxBytes);
			}
		}
		return Files.readAllBytes(datasetPath);
	}

	public static String sha256(Path datasetPath) throws IOException {
		Optional<Path> archive = resolveArchive(datasetPath);
		if (archive.isPresent()) {
			if (isMultipart(archive.get())) {
				return SaDatasetCacheService.sha256(openCombinedStream(listMultipartParts(archive.get())));
			}
			return SaDatasetCacheService.sha256(Files.newInputStream(archive.get()));
		}
		return SaDatasetCacheService.sha256(datasetPath);
	}

	public static InputStream openCombinedInputStream(Path datasetPath) throws IOException {
		Optional<Path> archive = resolveArchive(datasetPath);
		if (archive.isPresent()) {
			return openCombinedStream(listMultipartParts(archive.get()));
		}
		if (Files.isRegularFile(datasetPath)) {
			return Files.newInputStream(datasetPath);
		}
		throw new IngestionException("Dataset not found at " + datasetPath);
	}

	static byte[] toTarGz(byte[] csvBytes, String entryName) throws IOException {
		return gzip(toTar(csvBytes, entryName));
	}

	static byte[] toTar(byte[] csvBytes, String entryName) throws IOException {
		ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(tarBytes)) {
			TarArchiveEntry entry = new TarArchiveEntry(entryName);
			entry.setSize(csvBytes.length);
			tarOut.putArchiveEntry(entry);
			tarOut.write(csvBytes);
			tarOut.closeArchiveEntry();
		}
		return tarBytes.toByteArray();
	}

	private static byte[] gzip(byte[] bytes) throws IOException {
		ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
		try (GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(gzipBytes)) {
			gzipOut.write(bytes);
		}
		return gzipBytes.toByteArray();
	}

	private static BufferedReader openCsvReaderFromArchive(Path archivePath, String preferredEntryName)
			throws IOException {
		InputStream csvStream = openCsvInputStream(archivePath, preferredEntryName);
		return new BufferedReader(new InputStreamReader(csvStream, StandardCharsets.UTF_8)) {
			@Override
			public void close() throws IOException {
				try {
					super.close();
				}
				finally {
					csvStream.close();
				}
			}
		};
	}

	private static InputStream openDecompressedArchiveStream(Path archivePath, InputStream archiveStream)
			throws IOException {
		String name = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".gz")) {
			return new GzipCompressorInputStream(archiveStream);
		}
		return archiveStream;
	}

	private static InputStream openCsvInputStream(Path archivePath, String preferredEntryName) throws IOException {
		InputStream archiveStream = openCombinedStream(listMultipartParts(archivePath));
		InputStream payload = openDecompressedArchiveStream(archivePath, archiveStream);
		TarArchiveInputStream tarIn = new TarArchiveInputStream(payload);
		TarArchiveEntry entry = tarIn.getNextEntry();
		while (entry != null) {
			if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
				if (preferredEntryName == null || entry.getName().equalsIgnoreCase(preferredEntryName)
						|| entry.getName().endsWith(preferredEntryName)) {
					return new InputStream() {
						@Override
						public int read() throws IOException {
							return tarIn.read();
						}

						@Override
						public int read(byte[] buffer, int offset, int length) throws IOException {
							return tarIn.read(buffer, offset, length);
						}

						@Override
						public void close() throws IOException {
							tarIn.close();
						}
					};
				}
			}
			entry = tarIn.getNextEntry();
		}
		tarIn.close();
		throw new IngestionException("No CSV entry found in archive " + archivePath);
	}

	static InputStream openCombinedStream(List<Path> parts) throws IOException {
		if (parts.size() == 1) {
			return Files.newInputStream(parts.getFirst());
		}
		Enumeration<InputStream> streams = Collections.enumeration(parts.stream().map(path -> {
			try {
				return Files.newInputStream(path);
			}
			catch (IOException ex) {
				throw new IngestionException("Failed to open archive part " + path, ex);
			}
		}).toList());
		return new SequenceInputStream(streams);
	}

	public static List<Path> listMultipartParts(Path firstPart) throws IOException {
		if (!isMultipart(firstPart)) {
			return List.of(firstPart);
		}
		String baseName = firstPart.getFileName().toString().replaceFirst("\\.part\\d{3}$", "");
		Path parent = firstPart.getParent();
		try (var stream = Files.list(parent)) {
			return stream.filter(path -> path.getFileName().toString().startsWith(baseName + ".part"))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
		}
	}

	static Path firstMultipartPart(Path datasetPath) {
		Path singlePart = preferredArchivePath(datasetPath);
		Path part001 = Path.of(singlePart + ".part001");
		if (Files.isRegularFile(part001)) {
			return part001;
		}
		Path tarPart001 = Path.of(datasetPath + ".tar.part001");
		if (Files.isRegularFile(tarPart001)) {
			return tarPart001;
		}
		return null;
	}

	public static boolean isMultipart(Path archivePath) {
		return PART_SUFFIX.matcher(archivePath.getFileName().toString()).find();
	}

	private static void writeMultipart(Path archivePath, byte[] tarBytes) throws IOException {
		int partCount = (int) Math.ceil((double) tarBytes.length / MAX_PART_BYTES);
		for (int part = 0; part < partCount; part++) {
			int start = (int) (part * MAX_PART_BYTES);
			int end = (int) Math.min(tarBytes.length, start + MAX_PART_BYTES);
			Path partPath = Path.of(archivePath + ".part" + String.format("%03d", part + 1));
			Files.write(partPath, java.util.Arrays.copyOfRange(tarBytes, start, end));
		}
	}

	static void deleteArchiveParts(Path archivePath) throws IOException {
		Path parent = archivePath.getParent();
		String base = archivePath.getFileName().toString();
		if (parent == null || !Files.isDirectory(parent)) {
			return;
		}
		try (var stream = Files.list(parent)) {
			stream.filter(path -> {
				String name = path.getFileName().toString();
				return name.equals(base) || name.startsWith(base + ".part");
			}).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ex) {
					throw new IngestionException("Failed to delete old archive part " + path, ex);
				}
			});
		}
	}

}
