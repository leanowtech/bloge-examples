package com.leanowtech.bloge.gateway.visualadapter.authoring.resource;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Stable payload-free Problem Detail contract for the authoring HTTP surface. */
public record ApiResourceAuthoringProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String code,
        String correlationId,
        List<FieldError> fieldErrors,
        List<RecoveryAction> recoveryActions
) {
    /** Copies collection fields so error responses cannot be mutated after creation. */
    public ApiResourceAuthoringProblemDetail {
        type = value(type, "urn:bloge:problem:authoring");
        title = value(title, "Authoring request failed");
        detail = value(detail, title);
        code = value(code, "RG.AUTHORING.UNKNOWN");
        correlationId = value(correlationId, "unknown");
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        recoveryActions = recoveryActions == null ? List.of() : List.copyOf(recoveryActions);
    }

    /** One safe validation coordinate. */
    public record FieldError(String path, String code, String message) { }

    /** One client action that can resolve the failure. */
    public record RecoveryAction(String kind, @JsonInclude(JsonInclude.Include.NON_NULL) String path) { }

    private static String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}
