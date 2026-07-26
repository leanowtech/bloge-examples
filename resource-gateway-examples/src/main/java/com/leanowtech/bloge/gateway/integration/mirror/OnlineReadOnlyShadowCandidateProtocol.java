package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Exact wire constants and readiness projection for the isolated online candidate sidecar.
 */
public final class OnlineReadOnlyShadowCandidateProtocol {
    /** Vendor media type accepted for commands, capabilities, and evidence exchanges. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.online-read-only-shadow-candidate+json";
    /** Request and response protocol version. */
    public static final String VERSION = "1.0";
    /** Required protocol negotiation header. */
    public static final String VERSION_HEADER =
            "X-BLOGE-Online-Candidate-Protocol";
    /** Required execution idempotency identity header. */
    public static final String EXECUTION_ID_HEADER =
            "X-BLOGE-Shadow-Execution-Id";

    private OnlineReadOnlyShadowCandidateProtocol() {
    }

    /**
     * Bounded payload-free live capability projection returned over pinned mutual TLS.
     *
     * @param schemaVersion exact capability protocol version
     * @param protocolVersion exact command and evidence protocol version
     * @param checkedAt sidecar-owned probe time
     * @param validUntil exclusive capability validity
     * @param serviceReady whether the sidecar accepts new commands and exact reads
     * @param payloadIsolated whether payload values remain inside the regional trust domain
     * @param sealedPlanExecution whether only the command-bound sealed plan can execute
     * @param idempotentExecution whether execution identity fences duplicate candidate runs
     * @param signedEvidence whether every returned bundle carries independent evidence
     * @param productionCredentialProhibited whether production credentials are denied
     * @param exactArtifactRead whether immutable evidence can be read by exact coordinates
     */
    public record Capability(
            String schemaVersion,
            String protocolVersion,
            Instant checkedAt,
            Instant validUntil,
            boolean serviceReady,
            boolean payloadIsolated,
            boolean sealedPlanExecution,
            boolean idempotentExecution,
            boolean signedEvidence,
            boolean productionCredentialProhibited,
            boolean exactArtifactRead
    ) {
        /** Current live capability protocol. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.onlineReadOnlyShadowCandidateCapability.v1";

        /** Validates exact identity and a positive, bounded validity window. */
        public Capability {
            schemaVersion = normalized(
                    schemaVersion, "schemaVersion");
            protocolVersion = normalized(
                    protocolVersion, "protocolVersion");
            checkedAt = Objects.requireNonNull(
                    checkedAt, "checkedAt");
            validUntil = Objects.requireNonNull(
                    validUntil, "validUntil");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !VERSION.equals(protocolVersion)
                    || !validUntil.isAfter(checkedAt)
                    || validUntil.isAfter(
                    checkedAt.plusSeconds(300))) {
                throw new IllegalArgumentException(
                        "online candidate capability identity or validity is invalid");
            }
        }

        /**
         * Evaluates every mandatory safety fact against the trusted consumer clock.
         *
         * @param clock trusted consumer clock
         * @return true only while every safety fact is fresh and positive
         */
        public boolean ready(Clock clock) {
            Instant now = Objects.requireNonNull(
                    clock, "clock").instant();
            return serviceReady
                    && payloadIsolated
                    && sealedPlanExecution
                    && idempotentExecution
                    && signedEvidence
                    && productionCredentialProhibited
                    && exactArtifactRead
                    && !checkedAt.isAfter(now.plusSeconds(60))
                    && validUntil.isAfter(now);
        }

        private static String normalized(
                String value,
                String field) {
            String exact = value == null
                    ? "" : value.trim();
            if (exact.isBlank() || exact.length() > 128) {
                throw new IllegalArgumentException(
                        field + " is blank or unbounded");
            }
            return exact;
        }
    }
}
