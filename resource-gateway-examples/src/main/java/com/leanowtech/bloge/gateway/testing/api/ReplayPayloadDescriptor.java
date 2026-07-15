package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.ReplayPayloadRef;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Payload-free immutable metadata for one governed replay value.
 *
 * @param schemaVersion descriptor protocol version
 * @param replayPayloadId stable payload id
 * @param revision immutable positive revision
 * @param fingerprint digest over this descriptor's fingerprint material and the stored value
 * @param classification payload data classification
 * @param source signed source-run lineage
 * @param redaction source and capture sanitization facts
 * @param capturedAt server capture time
 * @param expiresAt hard retention deadline
 * @param certificationEligible whether this payload can contribute F4 certifiable evidence
 * @param certificationGaps bounded reasons why certification is unavailable
 */
public record ReplayPayloadDescriptor(
        String schemaVersion,
        String replayPayloadId,
        long revision,
        String fingerprint,
        String classification,
        Source source,
        Redaction redaction,
        Instant capturedAt,
        Instant expiresAt,
        boolean certificationEligible,
        List<String> certificationGaps
) {
    /** Current replay-payload descriptor protocol version. */
    public static final String SCHEMA_VERSION = "bloge.replayPayloadDescriptor.v1";

    /** Freezes normalized descriptor facts. */
    public ReplayPayloadDescriptor {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        replayPayloadId = normalized(replayPayloadId);
        fingerprint = normalized(fingerprint);
        classification = defaulted(classification, "INTERNAL").toUpperCase(Locale.ROOT);
        source = source == null ? Source.empty() : source;
        redaction = redaction == null ? Redaction.empty() : redaction;
        capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
        expiresAt = expiresAt == null ? Instant.EPOCH : expiresAt;
        certificationGaps = certificationGaps == null ? List.of() : List.copyOf(certificationGaps);
    }

    /**
     * Projects the descriptor identity into the reference accepted by fixture rules.
     *
     * @return exact canonical fixture reference for this immutable payload
     */
    public ReplayPayloadRef reference() {
        return new ReplayPayloadRef(replayPayloadId, revision, fingerprint);
    }

    /**
     * Signed, immutable source coordinates.
     *
     * @param kind governed source kind
     * @param runId source run id
     * @param nodeId exact source node id
     * @param attempt exact one-based source attempt
     * @param runEvidenceFingerprint signed source run material fingerprint
     * @param sourcePayloadFingerprint detached source payload fingerprint
     * @param sourceEnvironment source environment, retained for boundary audit
     */
    public record Source(
            String kind,
            String runId,
            String nodeId,
            int attempt,
            String runEvidenceFingerprint,
            String sourcePayloadFingerprint,
            String sourceEnvironment
    ) {
        /** Normalizes source coordinates without inventing missing lineage. */
        public Source {
            kind = normalized(kind).toUpperCase(Locale.ROOT);
            runId = normalized(runId);
            nodeId = normalized(nodeId);
            runEvidenceFingerprint = normalized(runEvidenceFingerprint);
            sourcePayloadFingerprint = normalized(sourcePayloadFingerprint);
            sourceEnvironment = normalized(sourceEnvironment);
        }

        /**
         * Creates an empty non-certifiable source placeholder for defensive deserialization.
         *
         * @return empty source coordinates
         */
        public static Source empty() {
            return new Source("", "", "", 0, "", "", "");
        }
    }

    /**
     * Sanitization provenance. Truncated values are never accepted into the replay vault.
     *
     * @param sourceProfile sanitizer profile on the governed source payload
     * @param sourceRedactedCount source-payload redaction count
     * @param captureProfile server sanitizer profile applied during replay capture
     * @param captureRedactedCount additional capture-time redaction count
     * @param truncated whether either sanitization pass truncated the value
     * @param redactedPaths bounded capture-time redacted JSON paths
     */
    public record Redaction(
            String sourceProfile,
            int sourceRedactedCount,
            String captureProfile,
            int captureRedactedCount,
            boolean truncated,
            List<String> redactedPaths
    ) {
        /** Freezes bounded redaction facts. */
        public Redaction {
            sourceProfile = normalized(sourceProfile);
            sourceRedactedCount = Math.max(0, sourceRedactedCount);
            captureProfile = normalized(captureProfile);
            captureRedactedCount = Math.max(0, captureRedactedCount);
            redactedPaths = redactedPaths == null ? List.of() : List.copyOf(redactedPaths);
        }

        /**
         * Creates an empty redaction manifest for defensive deserialization.
         *
         * @return empty redaction facts
         */
        public static Redaction empty() {
            return new Redaction("", 0, "", 0, false, List.of());
        }
    }

    private static String defaulted(String value, String fallback) {
        String normalized = normalized(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
