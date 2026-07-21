package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable monotonic authority for verified certificate-status source heads.
 *
 * <p>The floor is intentionally independent from the publication floor. A source head may advance
 * beyond the locally applied publication without changing any immutable publication. Persisting
 * the highest verified head prevents rollback after restart and makes exact catch-up lag an
 * auditable fact instead of a batch-limit heuristic.</p>
 */
public interface ControlPlaneCertificateStatusSourceHeadFloor {

    /** Closed mutation outcomes for one verified source-head attestation. */
    enum AcceptanceStatus {
        /** The first signed source head replaced the configured baseline placeholder. */
        INITIALIZED,
        /** A higher source head became durable. */
        ADVANCED,
        /** A newer attestation refreshed the same exact head. */
        RENEWED,
        /** The exact durable attestation was submitted again. */
        REPLAYED
    }

    /**
     * Tamper-checked durable source-head state.
     *
     * @param schemaVersion snapshot protocol version
     * @param deploymentScopeId exact deployment scope
     * @param baselineSequence deployment-pinned source baseline sequence
     * @param baselinePublicationFingerprint deployment-pinned baseline fingerprint
     * @param headSequence highest verified external source head
     * @param headPublicationFingerprint exact source-head or baseline fingerprint
     * @param attestationId current attestation identity, empty before initialization
     * @param attestationFingerprint current canonical attestation fingerprint
     * @param issuedAt current attestation issue time
     * @param expiresAt exclusive hard freshness deadline
     * @param observedAt database acceptance time
     */
    record Snapshot(
            String schemaVersion,
            String deploymentScopeId,
            long baselineSequence,
            String baselinePublicationFingerprint,
            long headSequence,
            String headPublicationFingerprint,
            String attestationId,
            String attestationFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt) {

        /** Current source-head floor snapshot protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusSourceHeadFloorSnapshot.v1";
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Validates the all-or-none initial and verified snapshot forms. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            deploymentScopeId = normalized(deploymentScopeId);
            baselinePublicationFingerprint = normalized(baselinePublicationFingerprint);
            headPublicationFingerprint = normalized(headPublicationFingerprint);
            attestationId = normalized(attestationId);
            attestationFingerprint = normalized(attestationFingerprint);
            boolean initialized = !attestationId.isBlank();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || baselineSequence < 0 || headSequence < baselineSequence
                    || !FINGERPRINT.matcher(baselinePublicationFingerprint).matches()
                    || !FINGERPRINT.matcher(headPublicationFingerprint).matches()
                    || headSequence == baselineSequence
                    && !headPublicationFingerprint.equals(baselinePublicationFingerprint)
                    || initialized && (!IDENTIFIER.matcher(attestationId).matches()
                    || !FINGERPRINT.matcher(attestationFingerprint).matches()
                    || issuedAt == null || expiresAt == null || observedAt == null
                    || !expiresAt.isAfter(issuedAt) || !expiresAt.isAfter(observedAt))
                    || !initialized && (!attestationFingerprint.isBlank()
                    || headSequence != baselineSequence
                    || !headPublicationFingerprint.equals(baselinePublicationFingerprint)
                    || issuedAt != null || expiresAt != null || observedAt != null)) {
                throw invalid("Certificate status source-head snapshot is invalid");
            }
        }

        /** @return whether at least one signed source head has been accepted */
        public boolean initialized() {
            return !attestationId.isBlank();
        }

        /** @return whether the attestation remains fresh at {@code now} */
        public boolean freshAt(Instant now) {
            return initialized() && now != null && now.isBefore(expiresAt);
        }

        /**
         * Computes exact external backlog only while this attestation remains fresh.
         *
         * @param appliedSequence locally applied durable publication sequence
         * @param now observation time
         * @return exact non-negative lag, or {@code -1} when no current proof exists
         */
        public long exactLagFrom(long appliedSequence, Instant now) {
            return freshAt(now) && appliedSequence >= baselineSequence
                    && appliedSequence <= headSequence
                    ? headSequence - appliedSequence : -1L;
        }
    }

    /** Result of accepting, renewing, or replaying one source-head attestation. */
    record Acceptance(AcceptanceStatus status, Snapshot snapshot) {
        /** Requires a non-null outcome and durable state. */
        public Acceptance {
            status = Objects.requireNonNull(status, "status");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Verifies and atomically floors one exact signed source-head attestation.
     *
     * @param sourceHead untrusted externally supplied source head
     * @return advanced, renewed, or exact-replay result
     */
    Acceptance accept(ControlPlaneCertificateStatusSourceHead sourceHead);

    /** @return current tamper-checked source-head state */
    Snapshot snapshot();

    /** @return true only when state survives process restart */
    boolean durable();

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
