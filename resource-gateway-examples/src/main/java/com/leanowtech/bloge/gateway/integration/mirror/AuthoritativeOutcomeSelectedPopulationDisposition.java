package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed payload-free authority disposition for a selected member that may not be observed.
 *
 * <p>Version 1 only permits a legally authorized deletion. It binds an exact selected-population
 * revision and member address to an owner-versioned retention policy, independently governed
 * deletion approval, and deletion authority set. Free-form exemptions are intentionally absent.</p>
 *
 * @param schemaVersion exact disposition protocol version
 * @param dispositionId stable disposition identity
 * @param revision positive immutable revision
 * @param dispositionFingerprint canonical content address excluding this field and the seal
 * @param scope exact enterprise namespace
 * @param populationRef exact selected-population root
 * @param unitId exact Fidelity inventory unit
 * @param stratumId exact owner-defined sampling stratum
 * @param sampleOrdinal one-based member position within the unit stratum
 * @param inclusionFingerprint exact pre-treatment inclusion material
 * @param subjectFingerprint exact payload-free subject identity
 * @param attributionKeyFingerprint exact action-to-outcome identity
 * @param disposition closed authority disposition
 * @param reason closed deletion reason
 * @param retentionPolicyRef exact owner-versioned retention policy
 * @param deletionApprovalRef exact independently governed deletion approval
 * @param deletionAuthoritySetRef exact deletion authority membership
 * @param effectiveAt authority-effective deletion time
 * @param attestedAt Resource Gateway attestation time
 * @param dispositionSeal detached Resource Gateway signature
 */
public record AuthoritativeOutcomeSelectedPopulationDisposition(
        String schemaVersion,
        String dispositionId,
        long revision,
        String dispositionFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef populationRef,
        String unitId,
        String stratumId,
        long sampleOrdinal,
        String inclusionFingerprint,
        String subjectFingerprint,
        String attributionKeyFingerprint,
        Disposition disposition,
        DeletionReason reason,
        MirrorArtifactRef retentionPolicyRef,
        MirrorArtifactRef deletionApprovalRef,
        MirrorArtifactRef deletionAuthoritySetRef,
        Instant effectiveAt,
        Instant attestedAt,
        VisualRunEvidenceSeal dispositionSeal
) {
    /** Current selected-member disposition wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationDisposition.v1";
    /** Artifact kind used by completeness evidence. */
    public static final String ARTIFACT_KIND =
            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION";
    /** Maximum canonical disposition bytes. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            256 * 1024;
    /** Maximum domain-separated signing-material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces exact member identity and governed deletion lineage. */
    public AuthoritativeOutcomeSelectedPopulationDisposition {
        schemaVersion = version(schemaVersion);
        dispositionId = identifier(
                dispositionId, "dispositionId");
        if (revision < 1 || sampleOrdinal < 1) {
            throw new IllegalArgumentException(
                    "selected member disposition revisions and ordinals must be positive");
        }
        dispositionFingerprint = optionalFingerprint(
                dispositionFingerprint,
                "dispositionFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        populationRef = requireKind(
                populationRef,
                AuthoritativeOutcomeSelectedPopulationManifest
                        .ARTIFACT_KIND,
                "populationRef");
        unitId = identifier(unitId, "unitId");
        stratumId = identifier(
                stratumId, "stratumId");
        inclusionFingerprint = fingerprint(
                inclusionFingerprint,
                "inclusionFingerprint");
        subjectFingerprint = fingerprint(
                subjectFingerprint,
                "subjectFingerprint");
        attributionKeyFingerprint = fingerprint(
                attributionKeyFingerprint,
                "attributionKeyFingerprint");
        disposition = Objects.requireNonNull(
                disposition, "disposition");
        reason = Objects.requireNonNull(reason, "reason");
        retentionPolicyRef = requireKind(
                retentionPolicyRef,
                "OUTCOME_DATA_RETENTION_POLICY",
                "retentionPolicyRef");
        deletionApprovalRef = requireKind(
                deletionApprovalRef,
                "OUTCOME_MEMBER_DELETION_APPROVAL",
                "deletionApprovalRef");
        deletionAuthoritySetRef = requireKind(
                deletionAuthoritySetRef,
                "OUTCOME_DELETION_AUTHORITY_SET",
                "deletionAuthoritySetRef");
        effectiveAt = Objects.requireNonNull(
                effectiveAt, "effectiveAt");
        attestedAt = Objects.requireNonNull(
                attestedAt, "attestedAt");
        if (attestedAt.isBefore(effectiveAt)) {
            throw new IllegalArgumentException(
                    "selected member disposition attestation cannot precede authority effect");
        }
        dispositionSeal = dispositionSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : dispositionSeal;
    }

    /** Closed member disposition vocabulary. */
    public enum Disposition {
        LEGALLY_DELETED
    }

    /** Closed reasons that require an independently verified approval artifact. */
    public enum DeletionReason {
        DATA_SUBJECT_REQUEST,
        LEGAL_RETENTION_EXPIRY,
        REGULATORY_ERASURE,
        SOURCE_SYSTEM_AUTHORIZED_PURGE
    }

    /**
     * Recomputes protocol semantics and content addressing.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (dispositionFingerprint.isBlank()
                || !dispositionFingerprint.equals(
                calculateFingerprint(mapper))) {
            throw new IllegalArgumentException(
                    "selected member disposition fingerprint mismatch");
        }
    }

    /**
     * Calculates the content address with address and seal blanked.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 content address
     */
    public String calculateFingerprint(ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                withFingerprintAndSeal(
                        "",
                        VisualRunEvidenceSeal.unsigned()),
                MAXIMUM_CANONICAL_BYTES);
    }

    /**
     * Returns domain-separated Resource Gateway signing material.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 attestation material
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        if (dispositionFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "selected member disposition must be content-addressed before signing");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_MEMBER_DISPOSITION_V1",
                        schemaVersion,
                        dispositionId,
                        revision,
                        populationRef,
                        unitId,
                        stratumId,
                        sampleOrdinal,
                        effectiveAt,
                        attestedAt,
                        dispositionFingerprint),
                MAXIMUM_ATTESTATION_BYTES);
    }

    /** @return exact disposition reference after signing */
    public MirrorArtifactRef artifactRef() {
        if (dispositionFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "selected member disposition is not content-addressed");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                dispositionId,
                revision,
                dispositionFingerprint);
    }

    /**
     * Attaches a detached Resource Gateway signature.
     *
     * @param seal governed producer seal
     * @return identical disposition carrying the seal
     */
    public AuthoritativeOutcomeSelectedPopulationDisposition
    withDispositionSeal(VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                dispositionFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    /**
     * Replaces the provisional Resource Gateway attestation time.
     *
     * @param value trusted signing-intent time
     * @return unsigned disposition carrying the exact time
     */
    AuthoritativeOutcomeSelectedPopulationDisposition
    withAttestedAt(Instant value) {
        return new AuthoritativeOutcomeSelectedPopulationDisposition(
                schemaVersion,
                dispositionId,
                revision,
                "",
                scope,
                populationRef,
                unitId,
                stratumId,
                sampleOrdinal,
                inclusionFingerprint,
                subjectFingerprint,
                attributionKeyFingerprint,
                disposition,
                reason,
                retentionPolicyRef,
                deletionApprovalRef,
                deletionAuthoritySetRef,
                effectiveAt,
                Objects.requireNonNull(value, "value"),
                VisualRunEvidenceSeal.unsigned());
    }

    /** Keeps member fingerprints out of generic logs. */
    @Override
    public String toString() {
        return "AuthoritativeOutcomeSelectedPopulationDisposition[dispositionId="
                + dispositionId + ", revision=" + revision
                + ", unitId=" + unitId
                + ", stratumId=" + stratumId
                + ", sampleOrdinal=" + sampleOrdinal
                + ", disposition=" + disposition + "]";
    }

    private AuthoritativeOutcomeSelectedPopulationDisposition
    withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new AuthoritativeOutcomeSelectedPopulationDisposition(
                schemaVersion,
                dispositionId,
                revision,
                fingerprint,
                scope,
                populationRef,
                unitId,
                stratumId,
                sampleOrdinal,
                inclusionFingerprint,
                subjectFingerprint,
                attributionKeyFingerprint,
                disposition,
                reason,
                retentionPolicyRef,
                deletionApprovalRef,
                deletionAuthoritySetRef,
                effectiveAt,
                attestedAt,
                seal);
    }

    private static String version(String value) {
        String normalized = value == null
                || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported selected member disposition schemaVersion");
        }
        return normalized;
    }

    private static String identifier(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef reference,
            String kind,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(reference, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String dispositionId,
            long revision,
            MirrorArtifactRef populationRef,
            String unitId,
            String stratumId,
            long sampleOrdinal,
            Instant effectiveAt,
            Instant attestedAt,
            String dispositionFingerprint
    ) {
    }
}
