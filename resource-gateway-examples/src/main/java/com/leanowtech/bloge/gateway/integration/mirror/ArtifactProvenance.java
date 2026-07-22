package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;

/**
 * Trust and lineage facts attached to every governed mirror artifact.
 *
 * <p>Provenance is a control contract, not descriptive metadata. It prevents an observed or
 * inferred value from silently crossing into approved serving. Approval, expiry, sample bounds,
 * and known bias risks remain visible to every downstream compiler and evidence verifier.</p>
 *
 * @param schemaVersion provenance protocol version
 * @param sourceType how the artifact was produced
 * @param sourceRefs exact upstream revisions used to produce the artifact
 * @param tenantId owning tenant
 * @param purpose authorized data-use purpose
 * @param sampleFrom inclusive beginning of the observation window, when applicable
 * @param sampleTo inclusive end of the observation window, when applicable
 * @param sampleCount number of admitted observations, or {@code null} when not sample-derived
 * @param confidence bounded statistical confidence, or {@code null} when owner-declared
 * @param biasRisks explicit sampling, selection, drift, or attribution limitations
 * @param approvedBy approving owner or reviewer; blank before approval
 * @param approvedAt approval time; {@code null} before approval
 * @param expiresAt time after which the artifact cannot produce certifiable evidence
 * @param revocationRef immutable revocation decision reference; blank while not revoked
 */
public record ArtifactProvenance(
        String schemaVersion,
        SourceType sourceType,
        List<MirrorArtifactRef> sourceRefs,
        String tenantId,
        String purpose,
        Instant sampleFrom,
        Instant sampleTo,
        Long sampleCount,
        Confidence confidence,
        List<String> biasRisks,
        String approvedBy,
        Instant approvedAt,
        Instant expiresAt,
        String revocationRef
) {
    /** Current provenance protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.artifactProvenance.v1";

    /** Origin of a governed mirror artifact. */
    public enum SourceType {
        OWNER,
        RECORDED,
        INFERRED,
        SYNTHESIZED
    }

    /**
     * Normalizes nullable collections and validates temporal and approval invariants.
     */
    public ArtifactProvenance {
        schemaVersion = version(schemaVersion, SCHEMA_VERSION);
        sourceType = sourceType == null ? SourceType.OWNER : sourceType;
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        tenantId = required(tenantId, "tenantId");
        purpose = required(purpose, "purpose");
        if (sampleCount != null && sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must not be negative");
        }
        if (sampleFrom != null && sampleTo != null && sampleFrom.isAfter(sampleTo)) {
            throw new IllegalArgumentException("sampleFrom must not be after sampleTo");
        }
        biasRisks = normalizedList(biasRisks);
        approvedBy = normalized(approvedBy);
        revocationRef = normalized(revocationRef);
        if ((approvedAt == null) != approvedBy.isBlank()) {
            throw new IllegalArgumentException("approvedBy and approvedAt must be supplied together");
        }
        if (expiresAt != null && approvedAt != null && expiresAt.isBefore(approvedAt)) {
            throw new IllegalArgumentException("expiresAt must not precede approvedAt");
        }
        if (sourceType == SourceType.OWNER && confidence != null) {
            throw new IllegalArgumentException("owner-declared provenance must not claim statistical confidence");
        }
        if ((sourceType == SourceType.RECORDED || sourceType == SourceType.INFERRED)
                && sourceRefs.isEmpty()) {
            throw new IllegalArgumentException("recorded and inferred provenance require sourceRefs");
        }
    }

    /**
     * Returns the same lineage with a fresh owner approval decision.
     *
     * @param actor approving owner or delegated reviewer
     * @param time approval decision time
     * @param expiry certification expiry, or {@code null} when policy does not impose one
     * @return copied provenance carrying the approval
     */
    public ArtifactProvenance withApproval(String actor, Instant time, Instant expiry) {
        return new ArtifactProvenance(schemaVersion, sourceType, sourceRefs, tenantId, purpose,
                sampleFrom, sampleTo, sampleCount, confidence, biasRisks,
                required(actor, "approvedBy"), java.util.Objects.requireNonNull(time, "approvedAt"),
                expiry, "");
    }

    /**
     * Returns the same lineage with an immutable revocation decision reference.
     *
     * @param reference exact external revocation decision reference
     * @return copied provenance carrying the revocation
     */
    public ArtifactProvenance withRevocation(String reference) {
        return new ArtifactProvenance(schemaVersion, sourceType, sourceRefs, tenantId, purpose,
                sampleFrom, sampleTo, sampleCount, confidence, biasRisks, approvedBy, approvedAt,
                expiresAt, required(reference, "revocationRef"));
    }

    /** @return the same lineage with stale approval and revocation decisions removed */
    public ArtifactProvenance asDraft() {
        return new ArtifactProvenance(schemaVersion, sourceType, sourceRefs, tenantId, purpose,
                sampleFrom, sampleTo, sampleCount, confidence, biasRisks, "", null, null, "");
    }

    /**
     * Statistical confidence carried without pretending that a point estimate is exact.
     *
     * @param point point estimate in the closed interval [0,1]
     * @param lowerBound lower confidence bound in the closed interval [0,1]
     * @param upperBound upper confidence bound in the closed interval [0,1]
     * @param method named estimation method and version
     */
    public record Confidence(double point, double lowerBound, double upperBound, String method) {
        /** Validates bounded and ordered confidence values. */
        public Confidence {
            if (!bounded(point) || !bounded(lowerBound) || !bounded(upperBound)
                    || lowerBound > point || point > upperBound) {
                throw new IllegalArgumentException(
                        "confidence bounds must satisfy 0 <= lowerBound <= point <= upperBound <= 1");
            }
            method = required(method, "method");
        }

        private static boolean bounded(double value) {
            return Double.isFinite(value) && value >= 0.0d && value <= 1.0d;
        }
    }

    private static String version(String value, String expected) {
        String normalized = value == null || value.isBlank() ? expected : value.trim();
        if (!expected.equals(normalized)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + normalized);
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> normalizedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(ArtifactProvenance::normalized).filter(value -> !value.isEmpty())
                .distinct().sorted().toList();
    }
}
