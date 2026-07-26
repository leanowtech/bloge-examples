package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Exact wire constants and readiness projection for the regional online baseline sidecar.
 */
public final class OnlineReadOnlyShadowBaselineProtocol {
    /** Vendor media type accepted for every command, capability, and observation exchange. */
    public static final String MEDIA_TYPE =
            "application/vnd.bloge.online-read-only-shadow-baseline+json";
    /** Request and response protocol version. */
    public static final String VERSION = "1.0";
    /** Required protocol negotiation header. */
    public static final String VERSION_HEADER =
            "X-BLOGE-Online-Baseline-Protocol";
    /** Required source idempotency identity header. */
    public static final String EXECUTION_ID_HEADER =
            "X-BLOGE-Shadow-Execution-Id";

    private OnlineReadOnlyShadowBaselineProtocol() {
    }

    /**
     * Bounded payload-free live capability projection returned over pinned mutual TLS.
     *
     * @param schemaVersion exact capability protocol version
     * @param protocolVersion exact command and observation protocol version
     * @param checkedAt sidecar-owned probe time
     * @param validUntil exclusive capability validity
     * @param serviceReady whether the sidecar can accept new commands
     * @param payloadIsolated whether payload values remain inside the regional trust domain
     * @param readOnlyWorkloadIdentity whether source identities are externally constrained to read
     * @param idempotentExecution whether execution identity fences duplicate source calls
     * @param payloadVaultReceipt whether each observation carries an opaque vault receipt
     * @param writeCredentialProhibited whether write-capable credentials are denied before calls
     * @param exactArtifactRead whether immutable observations can be read by exact coordinates
     */
    public record Capability(
            String schemaVersion,
            String protocolVersion,
            Instant checkedAt,
            Instant validUntil,
            boolean serviceReady,
            boolean payloadIsolated,
            boolean readOnlyWorkloadIdentity,
            boolean idempotentExecution,
            boolean payloadVaultReceipt,
            boolean writeCredentialProhibited,
            boolean exactArtifactRead
    ) {
        /** Current live capability protocol. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.onlineReadOnlyShadowBaselineCapability.v1";

        /** Validates the exact protocol identity and a positive bounded validity window. */
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
                        "online baseline capability identity or validity is invalid");
            }
        }

        /**
         * Evaluates all mandatory safety facts against the trusted consumer clock.
         *
         * @param clock trusted consumer clock
         * @return true only while every mandatory sidecar capability is fresh and positive
         */
        public boolean ready(Clock clock) {
            Instant now = Objects.requireNonNull(
                    clock, "clock").instant();
            return serviceReady
                    && payloadIsolated
                    && readOnlyWorkloadIdentity
                    && idempotentExecution
                    && payloadVaultReceipt
                    && writeCredentialProhibited
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
