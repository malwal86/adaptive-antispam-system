package com.antispam.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serializes values whose shape is a closed set of records and primitives, where a serialization
 * failure can only mean a non-serializable field crept in — a programming error, not a recoverable
 * runtime condition. Collapses the repeated {@code try/catch (JsonProcessingException)} wrapper into
 * one call while preserving each call site's {@code "failed to serialize <label>"} message.
 */
public final class JsonCodec {

    private JsonCodec() {}

    /**
     * Serializes {@code value} to a JSON string, or throws {@link IllegalStateException}
     * ({@code "failed to serialize " + label}) if Jackson cannot.
     */
    public static String serialize(ObjectMapper mapper, Object value, String label) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize " + label, e);
        }
    }
}
