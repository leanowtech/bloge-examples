package com.leanowtech.bloge.gateway.integration;

import java.util.Locale;

/**
 * Governance-stable execution status vocabulary.
 */
public enum VisualRunStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    SKIPPED,
    PARTIAL,
    MOCKED,
    CANCELLED,
    FALLBACK,
    UNKNOWN;

    public static VisualRunStatus fromRuntime(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "OK" -> SUCCESS;
            case "FAILED", "FAILURE", "ERROR" -> FAILED;
            case "TIMEOUT", "TIMED_OUT" -> TIMEOUT;
            case "SKIPPED", "NOT_RUN" -> SKIPPED;
            case "PARTIAL", "PARTIALLY_SUCCEEDED" -> PARTIAL;
            case "MOCKED", "MOCK" -> MOCKED;
            case "CANCELLED", "CANCELED" -> CANCELLED;
            case "FALLBACK", "DEGRADED" -> FALLBACK;
            default -> UNKNOWN;
        };
    }
}
