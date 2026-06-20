package com.antispam.analyze.web;

import com.antispam.analyze.AnalyzeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/** Builds {@code POST /analyze} JSON bodies for the API tests. */
public final class AnalyzeRequestFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalyzeRequestFixtures() {
    }

    /** A paste-path body carrying the raw message. */
    public static String rawJson(String raw) {
        return write(new AnalyzeRequest(raw, "test", null));
    }

    /** A picker-path body carrying an existing email id. */
    public static String byIdJson(UUID emailId) {
        return write(new AnalyzeRequest(null, null, emailId));
    }

    private static String write(AnalyzeRequest request) {
        try {
            return MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize AnalyzeRequest", e);
        }
    }
}
