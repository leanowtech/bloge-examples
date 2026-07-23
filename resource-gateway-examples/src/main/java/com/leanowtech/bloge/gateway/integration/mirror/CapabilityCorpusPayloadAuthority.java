package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * External authority for short-lived materialization of one sanitized corpus response.
 *
 * <p>Resource Gateway stores only content-addressed payload metadata. A deployment-owned
 * implementation obtains the canonical JSON from its regional vault after rechecking proof,
 * retention, purpose, and deletion state. Returned bytes are consumed only while building an
 * in-memory execution generation and must never be logged, persisted, included in evidence, or
 * retained by the authority implementation after this call.</p>
 */
public interface CapabilityCorpusPayloadAuthority {
    /**
     * Reports whether the regional payload authority can currently make a trustworthy decision.
     *
     * @return true only while materialization and deletion checks are usable
     */
    boolean available();

    /**
     * Materializes one exact sanitized response or returns a closed failure.
     *
     * @param request exact payload, proof, schema, purpose, and use-horizon coordinates
     * @return canonical JSON bytes or a payload-free closed failure
     */
    Materialization materialize(MaterializationRequest request);

    /**
     * Returns a fail-closed authority for deployments without a payload-vault integration.
     *
     * @return unavailable authority
     */
    static CapabilityCorpusPayloadAuthority unavailable() {
        return new CapabilityCorpusPayloadAuthority() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Materialization materialize(MaterializationRequest request) {
                return Materialization.unavailable(
                        "CORPUS_PAYLOAD_AUTHORITY_UNAVAILABLE");
            }
        };
    }

    /**
     * Exact short-lived payload request.
     *
     * @param scope complete enterprise scope
     * @param capabilityRef exact capability being simulated
     * @param publicationRef exact serving publication
     * @param observationRef exact source observation
     * @param payloadRef exact sanitized response payload
     * @param sanitizationProofRef exact response sanitization proof
     * @param schemaRef exact response schema
     * @param classification post-sanitization classification
     * @param vaultRegion exact regional vault
     * @param declaredSizeBytes immutable payload byte size
     * @param dataUseGrantRef exact data-use grant
     * @param authorizedPurpose server-minted mirror purpose
     * @param materializedAt trusted local materialization instant
     * @param requiredUntil exclusive minimum availability horizon
     */
    record MaterializationRequest(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef publicationRef,
            MirrorArtifactRef observationRef,
            MirrorArtifactRef payloadRef,
            MirrorArtifactRef sanitizationProofRef,
            MirrorArtifactRef schemaRef,
            CapabilityObservationEnvelope.Classification classification,
            String vaultRegion,
            long declaredSizeBytes,
            MirrorArtifactRef dataUseGrantRef,
            String authorizedPurpose,
            Instant materializedAt,
            Instant requiredUntil
    ) {
        /** Validates complete exact coordinates without copying payload content. */
        public MaterializationRequest {
            scope = Objects.requireNonNull(scope, "scope");
            capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
            publicationRef = ref(publicationRef,
                    CapabilityCorpusPublication.ARTIFACT_KIND, "publicationRef");
            observationRef = ref(observationRef,
                    CapabilityObservationEnvelope.ARTIFACT_KIND, "observationRef");
            payloadRef = ref(payloadRef, "SANITIZED_PAYLOAD", "payloadRef");
            sanitizationProofRef = ref(sanitizationProofRef,
                    "PAYLOAD_SANITIZATION_PROOF", "sanitizationProofRef");
            schemaRef = ref(schemaRef, "JSON_SCHEMA", "schemaRef");
            classification = Objects.requireNonNull(classification, "classification");
            vaultRegion = required(vaultRegion, "vaultRegion");
            if (declaredSizeBytes < 0
                    || declaredSizeBytes > MirrorResolutionIntegrity.MAXIMUM_OUTPUT_BYTES) {
                throw new IllegalArgumentException("declaredSizeBytes is outside its bound");
            }
            dataUseGrantRef = ref(dataUseGrantRef, "DATA_USE_GRANT", "dataUseGrantRef");
            authorizedPurpose = required(authorizedPurpose, "authorizedPurpose");
            materializedAt = Objects.requireNonNull(materializedAt, "materializedAt");
            requiredUntil = Objects.requireNonNull(requiredUntil, "requiredUntil");
            if (!requiredUntil.isAfter(materializedAt)) {
                throw new IllegalArgumentException("requiredUntil must follow materializedAt");
            }
        }
    }

    /** Closed payload-authority outcome. */
    enum Outcome {
        /** Exact canonical sanitized JSON was materialized. */
        MATERIALIZED,
        /** Deletion, revocation, retention, proof, or vault policy rejected the payload. */
        REJECTED,
        /** The authority could not make a trustworthy decision. */
        UNAVAILABLE
    }

    /**
     * Short-lived canonical material with a payload-safe textual representation.
     *
     * <p>The constructor defensively copies bytes and {@link #canonicalJson()} returns another
     * copy. Callers should drop both copies immediately after compiling the run generation.</p>
     */
    final class Materialization {
        private final Outcome outcome;
        private final String reasonCode;
        private final byte[] canonicalJson;

        private Materialization(Outcome outcome, String reasonCode, byte[] canonicalJson) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.reasonCode = reason(reasonCode);
            this.canonicalJson = canonicalJson == null
                    ? new byte[0] : Arrays.copyOf(canonicalJson, canonicalJson.length);
            if ((outcome == Outcome.MATERIALIZED) != (this.canonicalJson.length > 0)) {
                throw new IllegalArgumentException(
                        "only MATERIALIZED may carry non-empty canonical JSON");
            }
        }

        /** @return successful canonical JSON materialization */
        public static Materialization materialized(byte[] canonicalJson) {
            return new Materialization(Outcome.MATERIALIZED, "MATERIALIZED", canonicalJson);
        }

        /** @return deterministic payload rejection */
        public static Materialization rejected(String reasonCode) {
            return new Materialization(Outcome.REJECTED, reasonCode, null);
        }

        /** @return infrastructure-unavailable outcome */
        public static Materialization unavailable(String reasonCode) {
            return new Materialization(Outcome.UNAVAILABLE, reasonCode, null);
        }

        /** @return closed outcome */
        public Outcome outcome() {
            return outcome;
        }

        /** @return stable payload-free reason */
        public String reasonCode() {
            return reasonCode;
        }

        /** @return defensive copy of canonical JSON, empty for failures */
        public byte[] canonicalJson() {
            return Arrays.copyOf(canonicalJson, canonicalJson.length);
        }

        /** Prevents payload bytes from entering logs through object rendering. */
        @Override
        public String toString() {
            return "Materialization[outcome=" + outcome + ", reasonCode=" + reasonCode
                    + ", bytes=" + canonicalJson.length + "]";
        }
    }

    private static MirrorArtifactRef ref(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(field + " must be bounded and non-blank");
        }
        return exact;
    }

    private static String reason(String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Z][A-Z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
        return exact;
    }
}
