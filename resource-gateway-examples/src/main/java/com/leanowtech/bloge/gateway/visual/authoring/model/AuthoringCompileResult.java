package com.leanowtech.bloge.gateway.visual.authoring.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringSourceMap;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryDiff;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryImpactReview;

import java.util.List;

/**
 * Server-authoritative result of compiling a human authoring document.
 */
public record AuthoringCompileResult(
        String schemaVersion,
        String draftId,
        long authoringRevision,
        String authoringFingerprint,
        String compileFingerprint,
        String compilerVersion,
        String grammarVersion,
        String catalogFingerprint,
        String previewAuthority,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        OperatorLibrary canonicalLibrary,
        String canonicalFingerprint,
        List<AuthoringSourceMap.Entry> sourceMap,
        List<AuthoringDiagnostic> diagnostics,
        List<ConfirmationRequest> confirmationRequests,
        AuthoringReadiness readiness,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        OperatorLibraryDiff diff,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        OperatorLibraryImpactReview impact
) {
    public static final String SCHEMA_VERSION = "bloge.visualLibraryCompileResult.v1";
    public static final String SERVER_AUTHORITATIVE = "SERVER_AUTHORITATIVE";
    public static final String LOCAL_PREVIEW = "LOCAL_PREVIEW";

    public AuthoringCompileResult {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        draftId = normalized(draftId, "");
        authoringRevision = Math.max(0, authoringRevision);
        authoringFingerprint = normalized(authoringFingerprint, "");
        compileFingerprint = normalized(compileFingerprint, "");
        compilerVersion = normalized(compilerVersion, "");
        grammarVersion = normalized(grammarVersion, "");
        catalogFingerprint = normalized(catalogFingerprint, "");
        previewAuthority = normalized(previewAuthority, SERVER_AUTHORITATIVE);
        canonicalFingerprint = normalized(canonicalFingerprint, "");
        sourceMap = sourceMap == null ? List.of() : List.copyOf(sourceMap);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        confirmationRequests = confirmationRequests == null ? List.of() : List.copyOf(confirmationRequests);
        readiness = readiness == null
                ? new AuthoringReadiness("INVALID", false, false, false, false, List.of())
                : readiness;
    }

    public boolean importable() {
        return readiness.importable()
                && canonicalLibrary != null
                && diagnostics.stream().noneMatch(AuthoringDiagnostic::error);
    }

    public AuthoringCompileResult withPreviewContext(String effectiveCatalogFingerprint,
                                                     List<AuthoringDiagnostic> additionalDiagnostics,
                                                     OperatorLibraryDiff effectiveDiff,
                                                     OperatorLibraryImpactReview effectiveImpact,
                                                     AuthoringReadiness effectiveReadiness) {
        List<AuthoringDiagnostic> merged = new java.util.ArrayList<>(diagnostics);
        if (additionalDiagnostics != null) {
            merged.addAll(additionalDiagnostics);
        }
        return new AuthoringCompileResult(
                schemaVersion,
                draftId,
                authoringRevision,
                authoringFingerprint,
                compileFingerprint,
                compilerVersion,
                grammarVersion,
                normalized(effectiveCatalogFingerprint, catalogFingerprint),
                previewAuthority,
                canonicalLibrary,
                canonicalFingerprint,
                sourceMap,
                merged,
                confirmationRequests,
                effectiveReadiness == null ? readiness : effectiveReadiness,
                effectiveDiff,
                effectiveImpact
        );
    }

    public record ConfirmationRequest(
            String code,
            String authoringPath,
            String question,
            List<String> allowedValues
    ) {
        public ConfirmationRequest {
            code = normalized(code, "");
            authoringPath = normalized(authoringPath, "/");
            question = normalized(question, "");
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
