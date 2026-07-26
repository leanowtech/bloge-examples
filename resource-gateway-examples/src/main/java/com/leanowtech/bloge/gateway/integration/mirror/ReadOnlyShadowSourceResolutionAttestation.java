package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed proof that one exact detached Shadow source pair was independently re-resolved.
 *
 * <p>The attestation carries only content addresses, normalized fact fingerprints, evidence
 * classifications, timing, and zero-write counters. Historical artifact completion is separated
 * from the current connector resolution time so delayed replay cannot masquerade as an online
 * source execution.</p>
 *
 * @param schemaVersion exact attestation protocol version
 * @param attestationFingerprint canonical content address excluding this field and the seal
 * @param attestationId deterministic paired-resolution identity
 * @param revision positive immutable revision
 * @param scope complete enterprise namespace
 * @param requestId durable Shadow request identity
 * @param executionId stable logical execution identity across worker retries
 * @param sourceBindingRef exact signed detached source binding
 * @param comparisonPolicyRef exact normalization and typed-diff policy
 * @param requestContextFingerprint canonical paired request identity
 * @param admissionFingerprint exact joined online-authority decision
 * @param admittedAt trusted authority admission time
 * @param confirmedAt trusted terminal authority confirmation time
 * @param baseline independently reconstructed baseline resolution
 * @param candidate independently reconstructed candidate resolution
 * @param issuedAt trusted attestation issue time
 * @param attestationSeal detached source-resolution authority signature
 */
public record ReadOnlyShadowSourceResolutionAttestation(
        String schemaVersion,
        String attestationFingerprint,
        String attestationId,
        long revision,
        CapabilitySnapshot.Scope scope,
        String requestId,
        String executionId,
        MirrorArtifactRef sourceBindingRef,
        MirrorArtifactRef comparisonPolicyRef,
        String requestContextFingerprint,
        String admissionFingerprint,
        Instant admittedAt,
        Instant confirmedAt,
        SourceResolution baseline,
        SourceResolution candidate,
        Instant issuedAt,
        VisualRunEvidenceSeal attestationSeal
) {
    /** Current detached source-resolution attestation protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowSourceResolutionAttestation.v1";
    /** Artifact kind referenced by read-only Shadow comparison v2 and v3. */
    public static final String ARTIFACT_KIND =
            "SHADOW_SOURCE_RESOLUTION_ATTESTATION";
    /** Maximum canonical attestation material. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            4 * 1024 * 1024;
    /** Maximum domain-separated signature material. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<DomainFidelityProfile.Dimension>
            SUPPORTED_DIMENSIONS = EnumSet.of(
            DomainFidelityProfile.Dimension.BEHAVIOR,
            DomainFidelityProfile.Dimension.CONTRACT,
            DomainFidelityProfile.Dimension.EFFECT,
            DomainFidelityProfile.Dimension.STATE_TRANSITION);

    /** Validates complete paired, temporal, content-addressed, and zero-write closure. */
    public ReadOnlyShadowSourceResolutionAttestation {
        if (!SCHEMA_VERSION.equals(normalized(schemaVersion))) {
            throw new IllegalArgumentException(
                    "unsupported source-resolution attestation schemaVersion");
        }
        schemaVersion = SCHEMA_VERSION;
        attestationFingerprint = optionalFingerprint(
                attestationFingerprint,
                "attestationFingerprint");
        attestationId = identifier(
                attestationId, "attestationId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "source-resolution attestation revision must be positive");
        }
        scope = Objects.requireNonNull(scope, "scope");
        requestId = identifier(requestId, "requestId");
        executionId = identifier(
                executionId, "executionId");
        sourceBindingRef = kind(
                sourceBindingRef,
                ReadOnlyShadowSourceBinding.ARTIFACT_KIND,
                "sourceBindingRef");
        comparisonPolicyRef = kind(
                comparisonPolicyRef,
                "SHADOW_COMPARISON_POLICY",
                "comparisonPolicyRef");
        requestContextFingerprint = fingerprint(
                requestContextFingerprint,
                "requestContextFingerprint");
        admissionFingerprint = fingerprint(
                admissionFingerprint,
                "admissionFingerprint");
        admittedAt = Objects.requireNonNull(
                admittedAt, "admittedAt");
        confirmedAt = Objects.requireNonNull(
                confirmedAt, "confirmedAt");
        baseline = Objects.requireNonNull(
                baseline, "baseline");
        candidate = Objects.requireNonNull(
                candidate, "candidate");
        issuedAt = Objects.requireNonNull(
                issuedAt, "issuedAt");
        attestationSeal = attestationSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : attestationSeal;
        if (baseline.role()
                != ReadOnlyShadowComparison.SourceRole.BASELINE
                || candidate.role()
                != ReadOnlyShadowComparison.SourceRole.CANDIDATE
                || !ReadOnlyShadowSourceBinding.BASELINE_ARTIFACT_KIND
                .equals(baseline.artifactRef().kind())
                || !"MIRROR_EVIDENCE_BUNDLE".equals(
                candidate.artifactRef().kind())
                || baseline.resolvedAt().isBefore(admittedAt)
                || candidate.resolvedAt().isBefore(admittedAt)
                || confirmedAt.isBefore(
                baseline.resolvedAt())
                || confirmedAt.isBefore(
                candidate.resolvedAt())
                || issuedAt.isBefore(confirmedAt)
                || baseline.writeCredentialExposed()
                || candidate.writeCredentialExposed()
                || baseline.writeAttemptCount() != 0
                || candidate.writeAttemptCount() != 0) {
            throw new IllegalArgumentException(
                    "source-resolution attestation pair is inconsistent");
        }
    }

    /**
     * One independently reconstructed source resolution.
     *
     * @param role baseline or candidate role
     * @param artifactRef exact independently verified source artifact
     * @param semanticResultFingerprint canonical business-result identity
     * @param normalizedFactFingerprints exact policy fact fingerprints
     * @param sourceCompletedAt terminal time signed by the source artifact
     * @param resolvedAt terminal time of the current exact connector resolution
     * @param evidenceClass exploratory or certifiable source class
     * @param evidenceComplete whether all claimed facts are complete
     * @param writeCredentialExposed whether a write-capable credential reached resolution
     * @param writeAttemptCount observed external writes during resolution
     */
    public record SourceResolution(
            ReadOnlyShadowComparison.SourceRole role,
            MirrorArtifactRef artifactRef,
            String semanticResultFingerprint,
            Map<DomainFidelityProfile.Dimension, String>
                    normalizedFactFingerprints,
            Instant sourceCompletedAt,
            Instant resolvedAt,
            MirrorRunEvidence.EvidenceClass evidenceClass,
            boolean evidenceComplete,
            boolean writeCredentialExposed,
            long writeAttemptCount
    ) {
        /** Canonicalizes facts and validates source-versus-resolution timing. */
        public SourceResolution {
            role = Objects.requireNonNull(role, "role");
            artifactRef = Objects.requireNonNull(
                    artifactRef, "artifactRef");
            semanticResultFingerprint = fingerprint(
                    semanticResultFingerprint,
                    "semanticResultFingerprint");
            normalizedFactFingerprints = facts(
                    normalizedFactFingerprints);
            sourceCompletedAt = Objects.requireNonNull(
                    sourceCompletedAt,
                    "sourceCompletedAt");
            resolvedAt = Objects.requireNonNull(
                    resolvedAt, "resolvedAt");
            evidenceClass = Objects.requireNonNull(
                    evidenceClass, "evidenceClass");
            if (resolvedAt.isBefore(sourceCompletedAt)
                    || writeAttemptCount < 0
                    || writeAttemptCount > 1_000_000_000L) {
                throw new IllegalArgumentException(
                        "source resolution timing or write counters are invalid");
            }
        }
    }

    /**
     * Builds the exact content-addressed attestation reference.
     *
     * @return exact immutable source-resolution attestation reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                attestationId,
                revision,
                fingerprint(
                        attestationFingerprint,
                        "attestationFingerprint"));
    }

    /**
     * Recomputes the complete attestation content address.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (!calculateFingerprint(mapper).equals(
                attestationFingerprint)) {
            throw new IllegalArgumentException(
                    "source-resolution attestation fingerprint mismatch");
        }
    }

    /**
     * Returns domain-separated material signed by the resolution authority.
     *
     * @param mapper canonical protocol mapper
     * @return canonical signing-material fingerprint
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V1",
                        schemaVersion,
                        attestationId,
                        revision,
                        scope,
                        issuedAt,
                        fingerprint(
                                attestationFingerprint,
                                "attestationFingerprint")),
                MAXIMUM_ATTESTATION_BYTES);
    }

    ReadOnlyShadowSourceResolutionAttestation withFingerprint(
            String fingerprint) {
        return copy(
                fingerprint,
                VisualRunEvidenceSeal.unsigned());
    }

    /**
     * Copies the addressed attestation with a detached authority seal.
     *
     * @param seal detached source-resolution authority seal
     * @return immutable sealed attestation
     */
    public ReadOnlyShadowSourceResolutionAttestation withSeal(
            VisualRunEvidenceSeal seal) {
        return copy(
                attestationFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    String calculateFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new FingerprintMaterial(
                        schemaVersion,
                        "",
                        attestationId,
                        revision,
                        scope,
                        requestId,
                        executionId,
                        sourceBindingRef,
                        comparisonPolicyRef,
                        requestContextFingerprint,
                        admissionFingerprint,
                        admittedAt,
                        confirmedAt,
                        baseline,
                        candidate,
                        issuedAt),
                MAXIMUM_CANONICAL_BYTES);
    }

    private ReadOnlyShadowSourceResolutionAttestation copy(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new ReadOnlyShadowSourceResolutionAttestation(
                schemaVersion,
                fingerprint,
                attestationId,
                revision,
                scope,
                requestId,
                executionId,
                sourceBindingRef,
                comparisonPolicyRef,
                requestContextFingerprint,
                admissionFingerprint,
                admittedAt,
                confirmedAt,
                baseline,
                candidate,
                issuedAt,
                seal);
    }

    private static Map<DomainFidelityProfile.Dimension, String> facts(
            Map<DomainFidelityProfile.Dimension, String> supplied) {
        if (supplied == null || supplied.isEmpty()
                || supplied.size() > SUPPORTED_DIMENSIONS.size()) {
            throw new IllegalArgumentException(
                    "source-resolution facts are empty or unbounded");
        }
        LinkedHashMap<DomainFidelityProfile.Dimension, String> result =
                new LinkedHashMap<>();
        supplied.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Enum::name)))
                .forEach(entry -> {
                    DomainFidelityProfile.Dimension dimension =
                            Objects.requireNonNull(
                                    entry.getKey(), "dimension");
                    if (!SUPPORTED_DIMENSIONS.contains(dimension)) {
                        throw new IllegalArgumentException(
                                "source-resolution dimension is unsupported");
                    }
                    result.put(
                            dimension,
                            fingerprint(
                                    entry.getValue(),
                                    "normalizedFactFingerprint"));
                });
        return Collections.unmodifiableMap(result);
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " has an invalid artifact kind");
        }
        return exact;
    }

    private static String identifier(
            String value,
            String field) {
        String exact = normalized(value);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value,
            String field) {
        String exact = normalized(value);
        if (!exact.isEmpty()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(
            String value,
            String field) {
        String exact = optionalFingerprint(value, field);
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is required");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String attestationId,
            long revision,
            CapabilitySnapshot.Scope scope,
            Instant issuedAt,
            String attestationFingerprint
    ) {
    }

    private record FingerprintMaterial(
            String schemaVersion,
            String attestationFingerprint,
            String attestationId,
            long revision,
            CapabilitySnapshot.Scope scope,
            String requestId,
            String executionId,
            MirrorArtifactRef sourceBindingRef,
            MirrorArtifactRef comparisonPolicyRef,
            String requestContextFingerprint,
            String admissionFingerprint,
            Instant admittedAt,
            Instant confirmedAt,
            SourceResolution baseline,
            SourceResolution candidate,
            Instant issuedAt
    ) {
    }
}
