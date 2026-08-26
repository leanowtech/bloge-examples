package com.leanowtech.bloge.gateway.testing.world.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;

/** Trusted, payload-free metadata for one exact immutable asset revision. */
public record GovernedAssetMetadata(
        GovernedResourceRef exactRef,
        GovernedAssetGovernance governance,
        String governanceFingerprint) {

    public GovernedAssetMetadata {
        Objects.requireNonNull(governance, "governance");
        governanceFingerprint = governanceFingerprint == null || governanceFingerprint.isBlank()
                ? null : governanceFingerprint.trim();
    }

    public GovernedAssetMetadata(GovernedAssetGovernance governance) {
        this(null, governance, null);
    }

    public GovernedAssetMetadata(GovernedAssetGovernance governance, String governanceFingerprint) {
        this(null, governance, governanceFingerprint);
    }

    public GovernedAssetMetadata(GovernedResourceRef exactRef, GovernedAssetGovernance governance) {
        this(exactRef, governance, null);
    }

    public GovernedAssetMetadata(GovernedPayloadOrigin origin,
                                 GovernedSecurityClassification classification,
                                 Instant retentionExpiresAt,
                                 String accessPolicyRef,
                                 String approvalRef) {
        this(null, new GovernedAssetGovernance(origin, classification, retentionExpiresAt,
                accessPolicyRef, approvalRef), null);
    }

    public GovernedPayloadOrigin payloadOrigin() {
        return governance.payloadOrigin();
    }

    public GovernedResourceRef ref() {
        return exactRef;
    }

    public GovernedSecurityClassification securityClassification() {
        return governance.securityClassification();
    }

    public Instant retentionExpiresAt() {
        return governance.retentionExpiresAt();
    }

    public java.util.Optional<Instant> retentionExpiry() {
        return governance.retentionExpiry();
    }

    public String accessPolicyRef() {
        return governance.accessPolicyRef();
    }

    public String approvalRef() {
        return governance.approvalRef();
    }

    public GovernedAssetMetadata sealed(String exactGovernanceFingerprint) {
        return new GovernedAssetMetadata(exactRef, governance, exactGovernanceFingerprint);
    }

    public static GovernedAssetMetadata safeDefaults() {
        return new GovernedAssetMetadata(GovernedAssetGovernance.safeDefaults());
    }

    /** Seals governance dimensions together with every coordinate component of an asset ref. */
    public static String fingerprint(GovernedResourceRef ref, GovernedAssetGovernance governance) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(governance, "governance");
        String material = String.join("\n", ref.tenantId(), ref.kind().name(), ref.id(),
                Long.toString(ref.revision()), ref.fingerprint(), governance.payloadOrigin().name(),
                governance.securityClassification().name(),
                governance.retentionExpiresAt() == null ? "" : governance.retentionExpiresAt().toString(),
                governance.accessPolicyRef(), governance.approvalRef() == null ? "" : governance.approvalRef());
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }
}
