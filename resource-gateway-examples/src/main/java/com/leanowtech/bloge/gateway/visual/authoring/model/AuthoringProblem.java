package com.leanowtech.bloge.gateway.visual.authoring.model;

import java.util.List;
import java.util.UUID;

/**
 * Stable transport error for visual-library authoring requests.
 */
public record AuthoringProblem(
        String schemaVersion,
        String code,
        String message,
        int status,
        String draftId,
        long authoringRevision,
        List<AuthoringDiagnostic> diagnostics,
        String correlationId
) {
    public static final String SCHEMA_VERSION = "bloge.visualAuthoringProblem.v1";

    public AuthoringProblem {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        code = normalized(code, "RG.AUTHORING.REQUEST_FAILED");
        message = normalized(message, "Visual library authoring request failed.");
        status = status < 400 || status > 599 ? 400 : status;
        draftId = normalized(draftId, "");
        authoringRevision = Math.max(0, authoringRevision);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        correlationId = normalized(correlationId, UUID.randomUUID().toString());
    }

    public static AuthoringProblem of(String code,
                                      String message,
                                      int status,
                                      List<AuthoringDiagnostic> diagnostics) {
        return of(code, message, status, "", 0, diagnostics);
    }

    public static AuthoringProblem of(String code,
                                      String message,
                                      int status,
                                      String draftId,
                                      long authoringRevision,
                                      List<AuthoringDiagnostic> diagnostics) {
        return new AuthoringProblem(
                SCHEMA_VERSION,
                code,
                message,
                status,
                draftId,
                authoringRevision,
                diagnostics,
                UUID.randomUUID().toString()
        );
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
