package com.leanowtech.bloge.gateway.testing.world.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class WorldImpactSupport {
    static final String STATIC_SCHEMA = "bloge.worldStaticDependency.v1";
    static final String RUNTIME_SCHEMA = "bloge.worldRuntimeConsumption.v1";
    static final String ALGORITHM = "world-impact-chain-v1";
    static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    static final int MAX_TEXT = 512;
    static final int MAX_ENTRIES = 10_000;
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private WorldImpactSupport() {
    }

    static String text(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT
                || value.chars().anyMatch(Character::isISOControl)) {
            throw fail(WorldImpactException.Code.INVALID_INPUT);
        }
        return value.trim();
    }

    static String fingerprint(String value) {
        String normalized = text(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw fail(WorldImpactException.Code.INVALID_INPUT);
        }
        return normalized;
    }

    static Instant instant(Instant value) {
        if (value == null) throw fail(WorldImpactException.Code.INVALID_INPUT);
        return value;
    }

    static <T> List<T> list(List<T> value) {
        if (value == null || value.size() > MAX_ENTRIES || value.stream().anyMatch(item -> item == null)) {
            throw fail(WorldImpactException.Code.LIMIT_EXCEEDED);
        }
        return List.copyOf(value);
    }

    static String hash(Map<String, ?> material) {
        try {
            return ProtocolFingerprint.of(MAPPER, material);
        } catch (RuntimeException failure) {
            throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
    }

    static Map<String, Object> material(Object... values) {
        if (values == null || values.length % 2 != 0) throw fail(WorldImpactException.Code.INVALID_INPUT);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (!(values[index] instanceof String key) || key.isBlank() || values[index + 1] == null
                    || result.put(key, values[index + 1]) != null) throw fail(WorldImpactException.Code.INVALID_INPUT);
        }
        return result;
    }

    static WorldImpactException fail(WorldImpactException.Code code) {
        return new WorldImpactException(code);
    }
}
