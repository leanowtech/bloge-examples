package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;

import java.time.Instant;

/**
 * Receipt for an exact preview-fenced design catalog commit.
 */
public record AuthoringCommitResult(
        String schemaVersion,
        String draftId,
        long authoringRevision,
        String authoringFingerprint,
        String canonicalFingerprint,
        String catalogFingerprintBeforeCommit,
        long targetRevision,
        OperatorLibrary library,
        AuthoringCompileResult preview,
        Instant committedAt,
        String committedBy
) {
    public static final String SCHEMA_VERSION = "bloge.visualLibraryAuthoringCommitResult.v1";

    public AuthoringCommitResult {
        schemaVersion = normalized(schemaVersion, SCHEMA_VERSION);
        draftId = normalized(draftId, "");
        authoringRevision = Math.max(0, authoringRevision);
        authoringFingerprint = normalized(authoringFingerprint, "");
        canonicalFingerprint = normalized(canonicalFingerprint, "");
        catalogFingerprintBeforeCommit = normalized(catalogFingerprintBeforeCommit, "");
        targetRevision = Math.max(0, targetRevision);
        committedAt = committedAt == null ? Instant.EPOCH : committedAt;
        committedBy = normalized(committedBy, "");
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
