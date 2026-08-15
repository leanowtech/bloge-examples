package com.leanowtech.bloge.gateway.testing.correctness.run;

import java.util.List;
import java.util.Locale;

/** Exact, payload-free request for a correctness preflight or governed run. */
public record CorrectnessRunRequest(
        String schemaVersion,
        PublicationRef publicationRef,
        Selection selection,
        String preflightFingerprint,
        String clientRequestId,
        Strategy strategy
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessRunRequest.v1";

    public enum Strategy { COLLECT_ALL, FAIL_FAST }

    public CorrectnessRunRequest {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        if (publicationRef == null || selection == null) {
            throw new IllegalArgumentException("Publication ref and selection are required");
        }
        preflightFingerprint = fingerprint(preflightFingerprint, "preflightFingerprint");
        clientRequestId = required(clientRequestId, "clientRequestId");
        strategy = strategy == null ? Strategy.COLLECT_ALL : strategy;
    }

    /** Exact reference to the current immutable v1 Publication manifest. */
    public record PublicationRef(
            String publicationId,
            long revision,
            String fingerprint
    ) {
        public PublicationRef {
            publicationId = required(publicationId, "publicationId");
            if (revision != 1) {
                throw new IllegalArgumentException(
                        "Correctness Publication v1 uses immutable revision 1");
            }
            fingerprint = CorrectnessRunRequest.fingerprint(
                    fingerprint, "publication fingerprint");
        }
    }

    /** Canonical bounded case selection; it never carries case input or fixture material. */
    public record Selection(
            Mode mode,
            List<String> caseIds,
            String selectionFingerprint
    ) {
        public enum Mode { ALL, SELECTED }

        public Selection {
            mode = mode == null ? Mode.ALL : mode;
            caseIds = caseIds == null ? List.of() : caseIds.stream()
                    .map(value -> required(value, "caseId"))
                    .distinct().sorted().toList();
            selectionFingerprint = fingerprint(
                    selectionFingerprint, "selectionFingerprint");
            if (mode == Mode.ALL && !caseIds.isEmpty()) {
                throw new IllegalArgumentException("ALL selection must not carry caseIds");
            }
            if (mode == Mode.SELECTED && caseIds.isEmpty()) {
                throw new IllegalArgumentException("SELECTED selection requires caseIds");
            }
            if (caseIds.size() > 100) {
                throw new IllegalArgumentException("Selection may contain at most 100 caseIds");
            }
        }
    }

    private static String version(String value, String expected) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return expected;
        if (!expected.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported schemaVersion: " + normalized);
        }
        return normalized;
    }

    static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    static String fingerprint(String value, String field) {
        String normalized = required(value, field).toLowerCase(Locale.ROOT);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact SHA-256 fingerprint");
        }
        return normalized;
    }
}
