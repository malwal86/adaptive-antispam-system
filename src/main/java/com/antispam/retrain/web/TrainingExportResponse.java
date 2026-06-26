package com.antispam.retrain.web;

import com.antispam.retrain.ExportManifest;
import com.antispam.retrain.TrainingExample;
import com.antispam.retrain.TrainingExport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire view of the labeled-data export ({@code GET /retrain/export}, story 10.01): the feature
 * version every example is tied to, the per-source counts (the headline "what the next retrain learns
 * from"), and the examples themselves. Each example renders its label and source as their stable tokens
 * and its provenance as embedded JSON (parsed, not an escaped string) so the training step consumes a
 * clean object stream.
 *
 * @param featureVersion the feature schema version all examples are tied to
 * @param manifest       the de-identification manifest (story 14.04): what was applied + versions
 * @param total          the number of exported examples
 * @param countsBySource examples per source ({@code seed} / {@code feedback} / {@code arena}), in export order
 * @param examples       the exported training examples
 */
public record TrainingExportResponse(
        int featureVersion,
        ExportManifest manifest,
        int total,
        Map<String, Integer> countsBySource,
        List<Example> examples) {

    /**
     * One exported example on the wire.
     *
     * @param emailId         the labeled email
     * @param label           the training label token ({@code ham} / {@code spam} / {@code phish})
     * @param weight          how much the example counts in training
     * @param source          where the label came from
     * @param featureVersion  the feature schema version it is tied to
     * @param senderPseudonym the keyed-HMAC pseudonym of the sender (de-identified; story 14.04)
     * @param provenance      the per-example audit trail, embedded as JSON (de-identified)
     */
    public record Example(
            UUID emailId,
            String label,
            double weight,
            String source,
            int featureVersion,
            String senderPseudonym,
            JsonNode provenance) {
    }

    public static TrainingExportResponse from(TrainingExport export, ObjectMapper objectMapper) {
        Map<String, Integer> countsBySource = new LinkedHashMap<>();
        List<Example> examples = export.examples().stream()
                .map(example -> {
                    countsBySource.merge(example.source(), 1, Integer::sum);
                    return toExample(example, objectMapper);
                })
                .toList();
        return new TrainingExportResponse(
                export.featureVersion(), export.manifest(), examples.size(), countsBySource, examples);
    }

    private static Example toExample(TrainingExample example, ObjectMapper objectMapper) {
        return new Example(
                example.emailId(),
                example.label().dbValue(),
                example.weight(),
                example.source(),
                example.featureVersion(),
                example.senderPseudonym(),
                parse(example.provenance(), objectMapper));
    }

    private static JsonNode parse(String provenance, ObjectMapper objectMapper) {
        try {
            // Provenance is JSON this system wrote (the gate / arena / a json_build_object), so a parse
            // failure is a programming error, not a client error.
            return objectMapper.readTree(provenance);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("export provenance was not valid JSON: " + provenance, e);
        }
    }
}
