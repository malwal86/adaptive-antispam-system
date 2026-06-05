package com.antispam.seed;

import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Loads a labeled seed corpus into Postgres through the normal ingest path, so
 * the seeded mail is byte-faithful and deduped exactly like API-ingested mail.
 *
 * <p>The corpus is a directory tree {@code <root>/<dataset>/<class>/<files>},
 * e.g. {@code spamassassin/spam/*.eml} or {@code phishtank/phish/*.mbox}. The
 * dataset directory names the provenance; the class directory names the
 * {@link GroundTruthLabel}. Each file is normalized to one or more raw messages
 * ({@link MboxReader}), ingested with source {@code "seed"}, and labeled.
 *
 * <p><b>Idempotent and resumable.</b> Identical bytes dedupe at ingest and the
 * label insert is conflict-tolerant, so re-running adds nothing. A run that fails
 * partway leaves only completed (email, label) pairs — never a half-written one —
 * and re-running safely completes the rest. That is what keeps a partial load out
 * of an "ambiguous state".
 */
@Service
public class SeedLoader {

    /** Ingest provenance recorded on every seeded email (emails.ingest_source). */
    private static final String INGEST_SOURCE = "seed";

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final IngestService ingestService;
    private final GroundTruthLabelRepository labels;
    private final MboxReader mboxReader;

    @Autowired
    public SeedLoader(IngestService ingestService, GroundTruthLabelRepository labels, MboxReader mboxReader) {
        this.ingestService = ingestService;
        this.labels = labels;
        this.mboxReader = mboxReader;
    }

    /**
     * Loads every message under {@code corpusRoot}.
     *
     * @throws SeedException if the root is missing, empty, or a file/class
     *     directory cannot be read or recognized
     */
    public SeedReport load(Path corpusRoot) {
        if (!Files.isDirectory(corpusRoot)) {
            throw new SeedException("seed corpus directory not found: " + corpusRoot);
        }
        List<Path> datasetDirs = subdirectories(corpusRoot);
        if (datasetDirs.isEmpty()) {
            throw new SeedException("seed corpus has no dataset directories: " + corpusRoot);
        }

        Map<GroundTruthLabel, Integer> loaded = new EnumMap<>(GroundTruthLabel.class);
        Set<String> datasets = new TreeSet<>();
        int duplicates = 0;

        for (Path datasetDir : datasetDirs) {
            String dataset = datasetDir.getFileName().toString();
            for (Path classDir : subdirectories(datasetDir)) {
                GroundTruthLabel label = GroundTruthLabel.fromDirectoryName(classDir.getFileName().toString());
                for (Path file : files(classDir)) {
                    for (byte[] message : normalize(file)) {
                        if (loadMessage(message, label, dataset)) {
                            loaded.merge(label, 1, Integer::sum);
                            datasets.add(dataset);
                        } else {
                            duplicates++;
                        }
                    }
                }
            }
        }

        SeedReport report = new SeedReport(
                loaded, loaded.values().stream().mapToInt(Integer::intValue).sum(), duplicates, datasets);
        log.info("seed complete: loaded={} byLabel={} duplicatesSkipped={} datasets={}",
                report.totalLoaded(), loaded, report.duplicatesSkipped(), report.datasets());
        return report;
    }

    /**
     * Ingests one raw message and records its label.
     *
     * @return true if this produced a newly loaded, newly labeled email; false if
     *     it was already present (duplicate bytes or already labeled)
     */
    private boolean loadMessage(byte[] message, GroundTruthLabel label, String dataset) {
        if (message.length == 0) {
            return false;
        }
        IngestResult result = ingestService.ingest(message, INGEST_SOURCE);
        boolean labelWritten = labels.saveIfAbsent(result.emailId(), label, dataset);
        return !result.duplicate() && labelWritten;
    }

    private List<byte[]> normalize(Path file) {
        try {
            return mboxReader.messages(Files.readAllBytes(file), file.getFileName().toString());
        } catch (IOException e) {
            throw new SeedException("could not read seed file: " + file, e);
        }
    }

    private static List<Path> subdirectories(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new SeedException("could not list seed directory: " + dir, e);
        }
    }

    private static List<Path> files(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
        } catch (IOException e) {
            throw new SeedException("could not list seed directory: " + dir, e);
        } catch (UncheckedIOException e) {
            throw new SeedException("could not list seed directory: " + dir, e.getCause());
        }
    }
}
