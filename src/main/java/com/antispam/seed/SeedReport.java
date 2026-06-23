package com.antispam.seed;

import java.util.Map;
import java.util.Set;

/**
 * Summary of one seed run, logged so an operator can confirm the corpus is
 * balanced and that a re-run added nothing (the two success metrics of 01.03).
 *
 * @param loadedByLabel    count of newly loaded emails per class (omits classes
 *                         with none loaded this run)
 * @param totalLoaded      total newly loaded this run
 * @param duplicatesSkipped messages that were already present (deduped by content
 *                         hash) and so added no new row — 0 of these on a fresh
 *                         load, and <em>all</em> of them on an idempotent re-run
 * @param datasets         the dataset sources encountered (enron, spamassassin, ...)
 */
public record SeedReport(
        Map<GroundTruthLabel, Integer> loadedByLabel,
        int totalLoaded,
        int duplicatesSkipped,
        Set<String> datasets) {

    public SeedReport {
        loadedByLabel = Map.copyOf(loadedByLabel);
        datasets = Set.copyOf(datasets);
    }
}
