package com.leanowtech.bloge.gateway.testing.function;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, public limits shared by function-control validation and capability discovery. */
public record FunctionControlLimits(
        int maxStringChars,
        int maxDeclarations,
        int maxRules,
        int maxListEntries,
        int maxObjectEntries,
        int maxJsonValueDepth,
        int maxJsonValueBytes,
        int maxSchemaDepth,
        int maxSchemaBytes,
        int maxDurationMillis,
        int maxConsumption
) {
    public static final FunctionControlLimits CURRENT = new FunctionControlLimits(
            4_096, 256, 256, 256, 256, 64, 256 * 1024, 32, 128 * 1024, 60_000, 1_000_000);

    public FunctionControlLimits {
        if (maxStringChars < 1 || maxDeclarations < 1 || maxRules < 1 || maxListEntries < 1
                || maxObjectEntries < 1 || maxJsonValueDepth < 1 || maxJsonValueBytes < 1
                || maxSchemaDepth < 1 || maxSchemaBytes < 1 || maxDurationMillis < 1
                || maxConsumption < 1) {
            throw new IllegalArgumentException("function control limits must be positive");
        }
    }

    /** Payload-free machine-readable limits for the integration capability probe. */
    public Map<String, Integer> capabilityMap(int maxDecodedEnvelopeBytes) {
        if (maxDecodedEnvelopeBytes < 1) {
            throw new IllegalArgumentException("decoded envelope limit must be positive");
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("testControlEnvelopeDecodedBytes", maxDecodedEnvelopeBytes);
        values.put("functionNameChars", maxStringChars);
        values.put("functionDeclarations", maxDeclarations);
        values.put("functionRules", maxRules);
        values.put("functionListEntries", maxListEntries);
        values.put("functionObjectEntries", maxObjectEntries);
        values.put("functionDurationMillis", maxDurationMillis);
        values.put("functionConsumption", maxConsumption);
        values.put("functionJsonValueBytes", maxJsonValueBytes);
        values.put("functionJsonValueDepth", maxJsonValueDepth);
        values.put("functionSchemaBytes", maxSchemaBytes);
        values.put("functionSchemaDepth", maxSchemaDepth);
        return Collections.unmodifiableMap(values);
    }
}
