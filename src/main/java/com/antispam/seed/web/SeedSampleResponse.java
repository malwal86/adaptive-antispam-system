package com.antispam.seed.web;

import com.antispam.seed.SeedSample;
import java.util.UUID;

/**
 * {@code GET /seed/samples} item: a labeled sample the picker shows and can
 * analyse by id. The {@code label} is the lowercase ground-truth token
 * ({@code ham|spam|phish}) so the UI can group/colour the picker.
 *
 * @param emailId      analyse this id via {@code POST /analyze}
 * @param label        ground-truth class: {@code ham|spam|phish}
 * @param dataset      originating public corpus
 * @param subject      parsed subject (may be null)
 * @param senderDomain sender domain (may be null)
 */
public record SeedSampleResponse(
        UUID emailId,
        String label,
        String dataset,
        String subject,
        String senderDomain) {

    public static SeedSampleResponse from(SeedSample sample) {
        return new SeedSampleResponse(
                sample.emailId(),
                sample.label().dbValue(),
                sample.datasetSource(),
                sample.subject(),
                sample.senderDomain());
    }
}
