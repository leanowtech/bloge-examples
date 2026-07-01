package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Usage index response for one visual operator reference.
 *
 * @param schemaVersion response schema version
 * @param operatorRef queried operator reference
 * @param currentFingerprint fingerprint currently exposed by the catalog
 * @param drafts stored drafts using the operator
 * @param publications immutable publications using the operator
 * @param diagnostics non-blocking usage diagnostics
 */
public record OperatorUsageResponse(
        String schemaVersion,
        String operatorRef,
        String currentFingerprint,
        List<OperatorDraftUsage> drafts,
        List<OperatorPublicationUsage> publications,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorUsage.v1";

    /**
     * Creates an operator usage response.
     */
    public OperatorUsageResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion;
        operatorRef = operatorRef == null ? "" : operatorRef;
        currentFingerprint = currentFingerprint == null ? "" : currentFingerprint;
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        publications = publications == null ? List.of() : List.copyOf(publications);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
