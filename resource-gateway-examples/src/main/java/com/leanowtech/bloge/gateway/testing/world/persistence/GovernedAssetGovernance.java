package com.leanowtech.bloge.gateway.testing.world.persistence;

import java.time.Instant;

/**
 * Immutable governance dimensions kept outside the world execution model.
 *
 * <p>A null retention expiry means that no expiry was declared. The repository validates
 * REAL assets at write time because that is the point at which retention becomes authoritative.</p>
 */
public record GovernedAssetGovernance(
        GovernedPayloadOrigin payloadOrigin,
        GovernedSecurityClassification securityClassification,
        Instant retentionExpiresAt,
        String accessPolicyRef,
        String approvalRef) {

    public static final String BUILTIN_SYNTHETIC_PUBLIC_POLICY = "builtin:synthetic-public";

    public GovernedAssetGovernance {
        if (payloadOrigin == null || securityClassification == null
                || accessPolicyRef == null || accessPolicyRef.isBlank()) {
            throw new IllegalArgumentException("RG.WORLD.GOVERNANCE.INVALID_METADATA");
        }
        accessPolicyRef = accessPolicyRef.trim();
        approvalRef = normalizeOptional(approvalRef);
        if (payloadOrigin == GovernedPayloadOrigin.REAL && approvalRef == null) {
            throw new IllegalArgumentException("RG.WORLD.GOVERNANCE.REAL_APPROVAL_REQUIRED");
        }
        if (retentionExpiresAt != null && !isDatabaseInstant(retentionExpiresAt)) {
            throw new IllegalArgumentException("RG.WORLD.GOVERNANCE.INVALID_EXPIRY");
        }
    }

    public static GovernedAssetGovernance safeDefaults() {
        return new GovernedAssetGovernance(GovernedPayloadOrigin.SYNTHETIC,
                GovernedSecurityClassification.PUBLIC, null,
                BUILTIN_SYNTHETIC_PUBLIC_POLICY, null);
    }

    /** Validates the extra write-time rule that only REAL requires a future expiry. */
    public void validateForWrite(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("RG.WORLD.GOVERNANCE.CLOCK_REQUIRED");
        }
        if (payloadOrigin == GovernedPayloadOrigin.REAL
                && (retentionExpiresAt == null || !retentionExpiresAt.isAfter(now))) {
            throw new IllegalArgumentException("RG.WORLD.GOVERNANCE.REAL_EXPIRY_REQUIRED");
        }
    }

    public java.util.Optional<Instant> retentionExpiry() {
        return java.util.Optional.ofNullable(retentionExpiresAt);
    }

    public GovernedPayloadOrigin origin() {
        return payloadOrigin;
    }

    public GovernedSecurityClassification classification() {
        return securityClassification;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isDatabaseInstant(Instant value) {
        return !value.equals(Instant.MIN) && !value.equals(Instant.MAX);
    }
}
