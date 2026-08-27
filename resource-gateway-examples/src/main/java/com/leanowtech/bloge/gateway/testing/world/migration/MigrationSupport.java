package com.leanowtech.bloge.gateway.testing.world.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

final class MigrationSupport {
    static final String SCHEMA_VERSION = "bloge.worldMigrationDraftPackage.v1";
    static final String ALGORITHM_VERSION = "world-migration-v1";
    static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    static final int MAX_TEXT = 512;
    static final int MAX_ENTRIES = 10_000;
    static final int MAX_CANONICAL_BYTES = 1_048_576;
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private MigrationSupport() {
    }

    static String text(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT
                || value.chars().anyMatch(Character::isISOControl)) {
            throw fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        return value.trim();
    }

    static String fingerprint(String value) {
        String normalized = text(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        return normalized;
    }

    static <T> List<T> list(List<T> values) {
        if (values == null || values.size() > MAX_ENTRIES || values.stream().anyMatch(value -> value == null)) {
            throw fail(WorldMigrationException.Code.SOURCE_LIMIT_EXCEEDED);
        }
        return List.copyOf(values);
    }

    static <T> List<T> sorted(List<T> values, java.util.Comparator<T> comparator) {
        List<T> copy = new ArrayList<>(list(values));
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    static String hash(Object value) {
        try {
            return ProtocolFingerprint.ofBounded(MAPPER, value, MAX_CANONICAL_BYTES);
        } catch (RuntimeException failure) {
            throw fail(WorldMigrationException.Code.SOURCE_INTEGRITY);
        }
    }

    static java.util.Map<String, Object> material(Object... values) {
        if (values == null || values.length % 2 != 0) {
            throw fail(WorldMigrationException.Code.INVALID_INPUT);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (!(values[index] instanceof String key) || key.isBlank()
                    || values[index + 1] == null || result.put(key, values[index + 1]) != null) {
                throw fail(WorldMigrationException.Code.INVALID_INPUT);
            }
        }
        return result;
    }

    static WorldMigrationException fail(WorldMigrationException.Code code) {
        return new WorldMigrationException(code);
    }
}
