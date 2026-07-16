package com.leanowtech.bloge.gateway.integration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable machine-readable integration error contract.
 */
public record IntegrationProblem(
        String schemaVersion,
        String type,
        String title,
        int status,
        String code,
        boolean retryable,
        String correlationId,
        Map<String, Object> details
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.problem.v1";

    public IntegrationProblem {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        type = type == null || type.isBlank() ? "urn:bloge:problem:integration" : type;
        title = title == null ? "" : title;
        code = code == null ? "" : code;
        correlationId = correlationId == null ? "" : correlationId;
        details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public static IntegrationProblem badRequest(String code,
                                                String title,
                                                String correlationId,
                                                Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:bad-integration-request", title,
                400, code, false, correlationId, details);
    }

    public static IntegrationProblem unauthorized(String code,
                                                   String title,
                                                   String correlationId,
                                                   Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:integration-authentication", title,
                401, code, false, correlationId, details);
    }

    public static IntegrationProblem forbidden(String code,
                                                String title,
                                                String correlationId,
                                                Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:integration-authorization", title,
                403, code, false, correlationId, details);
    }

    public static IntegrationProblem notFound(String code,
                                              String title,
                                              String correlationId,
                                              Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:integration-resource-not-found", title,
                404, code, false, correlationId, details);
    }

    public static IntegrationProblem conflict(String code,
                                              String title,
                                              String correlationId,
                                              Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:integration-conflict", title,
                409, code, false, correlationId, details);
    }

    public static IntegrationProblem retryableConflict(String code,
                                                       String title,
                                                       String correlationId,
                                                       Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:integration-conflict", title,
                409, code, true, correlationId, details);
    }

    public static IntegrationProblem gone(String code,
                                          String title,
                                          String correlationId,
                                          Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:integration-resource-gone", title,
                410, code, false, correlationId, details);
    }

    /**
     * Creates a retryable capacity/backpressure response.
     *
     * <p>Callers should include a positive {@code retryAfterSeconds} detail. The HTTP adapter maps
     * that bounded aggregate value to {@code Retry-After}; resource identities must not be placed
     * in details.</p>
     */
    public static IntegrationProblem tooManyRequests(String code,
                                                     String title,
                                                     String correlationId,
                                                     Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:integration-capacity", title,
                429, code, true, correlationId, details);
    }

    public static IntegrationProblem serviceUnavailable(String code,
                                                         String title,
                                                         String correlationId,
                                                         Map<String, Object> details) {
        return new IntegrationProblem("", "urn:bloge:problem:integration-service-unavailable", title,
                503, code, true, correlationId, details);
    }
}
