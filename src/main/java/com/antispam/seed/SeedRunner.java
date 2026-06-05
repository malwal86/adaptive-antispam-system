package com.antispam.seed;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the seed load once at startup when {@code antispam.seed.enabled=true}, so
 * {@code make seed} is just "boot the app with seeding on". On a normal boot this
 * is a no-op. The loaded corpus is what the analyzer UI (01.05) and every later
 * training/replay/arena stage draw real mail from.
 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final SeedProperties properties;
    private final SeedLoader loader;

    @Autowired
    public SeedRunner(SeedProperties properties, SeedLoader loader) {
        this.properties = properties;
        this.loader = loader;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        if (properties.corpusDir() == null || properties.corpusDir().isBlank()) {
            throw new SeedException("antispam.seed.corpus-dir must be set when antispam.seed.enabled=true");
        }
        log.info("seeding corpus from {}", properties.corpusDir());
        SeedReport report = loader.load(Path.of(properties.corpusDir()));
        log.info("seed report: {} emails loaded (by class {}), {} duplicates skipped, datasets {}",
                report.totalLoaded(), report.loadedByLabel(), report.duplicatesSkipped(), report.datasets());
    }
}
