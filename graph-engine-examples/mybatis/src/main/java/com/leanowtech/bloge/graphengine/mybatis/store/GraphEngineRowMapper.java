package com.leanowtech.bloge.graphengine.mybatis.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Utility helpers for reading case-insensitive column values from MyBatis
 * {@code resultType="map"} rows.
 */
final class GraphEngineRowMapper {
    private GraphEngineRowMapper() {
    }

    static Object get(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            value = row.get(key.toUpperCase());
        }
        return value;
    }

    static String str(Map<String, Object> row, String key) {
        Object value = get(row, key);
        return value == null ? null : value.toString();
    }

    static long lng(Map<String, Object> row, String key, long defaultValue) {
        Object value = get(row, key);
        if (value == null) {
            return defaultValue;
        }
        return ((Number) value).longValue();
    }

    static boolean bool(Map<String, Object> row, String key) {
        Object value = get(row, key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    static Instant instant(Map<String, Object> row, String key) {
        Object value = get(row, key);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return null;
    }
}
