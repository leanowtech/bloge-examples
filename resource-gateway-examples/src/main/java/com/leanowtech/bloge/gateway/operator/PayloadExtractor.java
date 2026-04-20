package com.leanowtech.bloge.gateway.operator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Extracts a nested value from a JSON string using a dot-notation path.
 *
 * <p>Examples:
 * <pre>{@code
 * var extractor = new PayloadExtractor();
 * // extract("{"data":{"name":"Alice"}}", "data") → {"name":"Alice"}
 * // extract("{"result":{"items":[1,2,3]}}", "result.items") → [1,2,3]
 * // extract("{"a":"b"}", null) → {"a":"b"} (full body)
 * }</pre>
 */
public class PayloadExtractor {

    private final ObjectMapper objectMapper;

    /**
     * Creates an extractor with a default {@link ObjectMapper}.
     */
    public PayloadExtractor() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates an extractor with the given {@link ObjectMapper}.
     *
     * @param objectMapper the Jackson mapper to use for parsing
     */
    public PayloadExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Extracts the value at the given dot-notation path from a JSON body.
     *
     * <p>If {@code payloadPath} is {@code null} or blank, the entire parsed JSON is returned.
     *
     * @param jsonBody    the raw JSON response body
     * @param payloadPath dot-notation path (e.g. "data", "result.items", "data.user.name")
     * @return the extracted value — a {@code Map}, {@code List}, {@code String}, {@code Number},
     *         {@code Boolean}, or {@code null}
     * @throws IllegalArgumentException if the JSON cannot be parsed or the path is invalid
     */
    @SuppressWarnings("unchecked")
    public Object extract(String jsonBody, String payloadPath) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return null;
        }

        Object parsed;
        try {
            parsed = objectMapper.readValue(jsonBody, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse JSON body: " + e.getMessage(), e);
        }

        if (payloadPath == null || payloadPath.isBlank()) {
            return parsed;
        }

        String[] segments = payloadPath.split("\\.");
        Object current = parsed;
        for (String segment : segments) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(segment);
                    current = list.get(index);
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }
}
