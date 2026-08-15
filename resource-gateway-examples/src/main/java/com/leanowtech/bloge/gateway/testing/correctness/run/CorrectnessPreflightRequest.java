package com.leanowtech.bloge.gateway.testing.correctness.run;

import java.util.List;
import java.util.Locale;

/** Payload-free request to resolve the execution plan visible to a correctness author. */
public record CorrectnessPreflightRequest(
        String schemaVersion,
        CorrectnessRunRequest.PublicationRef publicationRef,
        SelectionIntent selection
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessPreflightRequest.v1";

    public CorrectnessPreflightRequest {
        String normalized = schemaVersion == null ? "" : schemaVersion.trim();
        schemaVersion = normalized.isEmpty() ? SCHEMA_VERSION : normalized;
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported correctness preflight schemaVersion");
        }
        if (publicationRef == null || selection == null) {
            throw new IllegalArgumentException("Publication ref and selection are required");
        }
    }

    /** Adapts an already reviewed run selection into a stale-view-guarded preflight request. */
    public CorrectnessPreflightRequest(
            String schemaVersion,
            CorrectnessRunRequest.PublicationRef publicationRef,
            CorrectnessRunRequest.Selection selection
    ) {
        this(schemaVersion, publicationRef, SelectionIntent.reviewed(selection));
    }

    /** User selection before the server resolves and fingerprints the exact TestSuite closure. */
    public record SelectionIntent(
            CorrectnessRunRequest.Selection.Mode mode,
            List<String> caseIds,
            String expectedSelectionFingerprint
    ) {
        public SelectionIntent {
            mode = mode == null ? CorrectnessRunRequest.Selection.Mode.ALL : mode;
            caseIds = caseIds == null ? List.of() : caseIds.stream()
                    .map(value -> required(value, "caseId"))
                    .distinct().sorted().toList();
            expectedSelectionFingerprint = expectedSelectionFingerprint == null
                    ? "" : expectedSelectionFingerprint.trim().toLowerCase(Locale.ROOT);
            if (mode == CorrectnessRunRequest.Selection.Mode.ALL && !caseIds.isEmpty()) {
                throw new IllegalArgumentException("ALL selection must not carry caseIds");
            }
            if (mode == CorrectnessRunRequest.Selection.Mode.SELECTED && caseIds.isEmpty()) {
                throw new IllegalArgumentException("SELECTED selection requires caseIds");
            }
            if (caseIds.size() > 100) {
                throw new IllegalArgumentException(
                        "Selection may contain at most 100 caseIds");
            }
            if (!expectedSelectionFingerprint.isEmpty()
                    && !expectedSelectionFingerprint.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "expectedSelectionFingerprint must be an exact SHA-256 fingerprint");
            }
        }

        public static SelectionIntent reviewed(CorrectnessRunRequest.Selection selection) {
            if (selection == null) return null;
            return new SelectionIntent(
                    selection.mode(), selection.caseIds(), selection.selectionFingerprint());
        }

        private static String required(String value, String field) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }
}
