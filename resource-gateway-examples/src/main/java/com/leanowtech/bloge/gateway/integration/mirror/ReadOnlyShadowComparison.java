package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed, payload-free comparison between one authorized read-only baseline observation and one
 * mirror candidate observation.
 *
 * <p>The protocol does not claim that a comparison job ran merely because two result hashes exist.
 * It freezes the exact owner inventory unit, sampling grant, kill-switch generation, egress
 * authority, paired request identity, both source artifacts, and dimension-specific normalized
 * fact fingerprints. Match and mismatch outcomes are derived from those fingerprints, while an
 * indeterminate result must expose an evidence gap. Business payload values never enter this
 * artifact.</p>
 *
 * @param schemaVersion exact comparison protocol version
 * @param comparisonId stable comparison identity
 * @param revision positive immutable revision
 * @param comparisonFingerprint canonical content address excluding this field and the seal
 * @param scope exact enterprise namespace
 * @param inventoryRef exact owner-approved fidelity inventory
 * @param unitId exact inventory coverage unit
 * @param scenarioCaseRef exact Scenario case classified for the paired request
 * @param targetCapabilityRef exact candidate capability revision
 * @param comparisonPolicyRef exact normalized-fact and typed-diff policy; absent only in legacy v1
 * @param sourceResolutionAttestationRef exact proof that both source artifacts were fetched and
 *                                        independently verified; absent only in legacy v1
 * @param accessProof zero-write sampling, egress, and kill-switch closure
 * @param baseline independently signed read-only baseline source
 * @param candidate independently signed mirror candidate source
 * @param observedAt authoritative comparison time
 * @param results canonical typed dimension comparisons
 * @param comparisonSeal detached producer signature over the content address
 */
public record ReadOnlyShadowComparison(
        String schemaVersion,
        String comparisonId,
        long revision,
        String comparisonFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef inventoryRef,
        String unitId,
        MirrorArtifactRef scenarioCaseRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef comparisonPolicyRef,
        MirrorArtifactRef sourceResolutionAttestationRef,
        AccessProof accessProof,
        SourceObservation baseline,
        SourceObservation candidate,
        Instant observedAt,
        List<DimensionComparison> results,
        VisualRunEvidenceSeal comparisonSeal
) {
    /** Legacy comparison protocol without an exact normalization-policy reference. */
    public static final String V1_SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowComparison.v1";
    /** Current comparison protocol binding normalized facts to one exact governed policy. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowComparison.v2";
    /** Artifact kind admitted by the Domain Fidelity projection kernel. */
    public static final String ARTIFACT_KIND =
            "FIDELITY_SHADOW_COMPARISON";
    /** Maximum canonical comparison bytes admitted to content addressing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            2 * 1024 * 1024;
    /** Maximum domain-separated signing material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<DomainFidelityProfile.Dimension>
            SUPPORTED_DIMENSIONS =
            EnumSet.of(
                    DomainFidelityProfile.Dimension.BEHAVIOR,
                    DomainFidelityProfile.Dimension.CONTRACT,
                    DomainFidelityProfile.Dimension.EFFECT,
                    DomainFidelityProfile.Dimension.STATE_TRANSITION);

    /** Validates bounded identity, zero-write closure, source pairing, and typed diff derivation. */
    public ReadOnlyShadowComparison {
        schemaVersion = version(schemaVersion);
        comparisonId = identifier(
                comparisonId, "comparisonId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "shadow comparison revision must be positive");
        }
        comparisonFingerprint = optionalFingerprint(
                comparisonFingerprint);
        scope = Objects.requireNonNull(scope, "scope");
        inventoryRef = requireKind(
                inventoryRef,
                DomainFidelityInventory.ARTIFACT_KIND,
                "inventoryRef");
        unitId = identifier(unitId, "unitId");
        scenarioCaseRef = requireKind(
                scenarioCaseRef,
                "SCENARIO_CASE",
                "scenarioCaseRef");
        targetCapabilityRef = requireKind(
                targetCapabilityRef,
                "CAPABILITY",
                "targetCapabilityRef");
        if (SCHEMA_VERSION.equals(schemaVersion)) {
            comparisonPolicyRef = requireKind(
                    comparisonPolicyRef,
                    "SHADOW_COMPARISON_POLICY",
                    "comparisonPolicyRef");
            sourceResolutionAttestationRef = requireKind(
                    sourceResolutionAttestationRef,
                    "SHADOW_SOURCE_RESOLUTION_ATTESTATION",
                    "sourceResolutionAttestationRef");
        } else if (comparisonPolicyRef != null
                || sourceResolutionAttestationRef != null) {
            throw new IllegalArgumentException(
                    "legacy shadow comparison must not declare v2 authority references");
        }
        accessProof = Objects.requireNonNull(
                accessProof, "accessProof");
        baseline = Objects.requireNonNull(
                baseline, "baseline");
        candidate = Objects.requireNonNull(
                candidate, "candidate");
        if (baseline.role() != SourceRole.BASELINE
                || candidate.role() != SourceRole.CANDIDATE
                || !"SHADOW_BASELINE_OBSERVATION".equals(
                baseline.artifactRef().kind())
                || !"MIRROR_EVIDENCE_BUNDLE".equals(
                candidate.artifactRef().kind())
                || !baseline.requestContextFingerprint()
                .equals(candidate.requestContextFingerprint())
                || !scope.equals(baseline.scope())
                || !scope.equals(candidate.scope())
                || !targetCapabilityRef.equals(
                candidate.targetCapabilityRef())) {
            throw new IllegalArgumentException(
                    "shadow comparison source pair is not an exact authorized request closure");
        }
        observedAt = Objects.requireNonNull(
                observedAt, "observedAt");
        if (observedAt.isBefore(baseline.completedAt())
                || observedAt.isBefore(candidate.completedAt())) {
            throw new IllegalArgumentException(
                    "shadow comparison cannot precede either source observation");
        }
        results = results == null
                ? List.of() : List.copyOf(results);
        List<DomainFidelityProfile.Dimension> order =
                results.stream()
                        .map(DimensionComparison::dimension)
                        .toList();
        List<DomainFidelityProfile.Dimension> canonical =
                order.stream()
                        .distinct()
                        .sorted(Comparator.comparing(Enum::name))
                        .toList();
        if (results.isEmpty()
                || !order.equals(canonical)
                || !SUPPORTED_DIMENSIONS.containsAll(order)) {
            throw new IllegalArgumentException(
                    "shadow comparison results must be unique, ordered, and supported");
        }
        comparisonSeal = comparisonSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : comparisonSeal;
    }

    /**
     * Sampling and network proof required before one baseline request can be compared.
     *
     * @param accessMode read-only production access or isolated sandbox access
     * @param samplingGrantRef exact data-governance sampling authorization
     * @param egressAuthorityRef exact externally attested egress policy
     * @param killSwitchRef exact enabled kill-switch generation
     * @param sampleOrdinal one-based admitted sample position
     * @param maximumSamples maximum requests admitted by the exact grant
     * @param writeCredentialExposed whether any source received a write-capable credential
     * @param writeAttemptCount observed external write attempts
     */
    public record AccessProof(
            AccessMode accessMode,
            MirrorArtifactRef samplingGrantRef,
            MirrorArtifactRef egressAuthorityRef,
            MirrorArtifactRef killSwitchRef,
            long sampleOrdinal,
            long maximumSamples,
            boolean writeCredentialExposed,
            long writeAttemptCount
    ) {
        /** Requires an exact bounded grant and proves that the comparison had no write capability. */
        public AccessProof {
            accessMode = Objects.requireNonNull(
                    accessMode, "accessMode");
            samplingGrantRef = requireKind(
                    samplingGrantRef,
                    "SHADOW_SAMPLING_GRANT",
                    "samplingGrantRef");
            egressAuthorityRef = requireKind(
                    egressAuthorityRef,
                    "MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION",
                    "egressAuthorityRef");
            killSwitchRef = requireKind(
                    killSwitchRef,
                    "SHADOW_KILL_SWITCH_STATE",
                    "killSwitchRef");
            if (sampleOrdinal < 1
                    || maximumSamples < 1
                    || sampleOrdinal > maximumSamples
                    || maximumSamples > 1_000_000_000L
                    || writeCredentialExposed
                    || writeAttemptCount != 0) {
                throw new IllegalArgumentException(
                        "shadow access proof violates the bounded zero-write policy");
            }
        }
    }

    /** Admitted non-mutating baseline access classes. */
    public enum AccessMode {
        READ_ONLY,
        SAFE_SANDBOX
    }

    /** Role of one independently signed observation in the pair. */
    public enum SourceRole {
        BASELINE,
        CANDIDATE
    }

    /**
     * Payload-free identity closure for one independently signed source artifact.
     *
     * @param role baseline or candidate role
     * @param artifactRef exact signed source artifact
     * @param scope exact enterprise namespace
     * @param targetCapabilityRef exact observed capability
     * @param requestContextFingerprint canonical paired request identity
     * @param semanticResultFingerprint canonical business-result identity
     * @param completedAt source terminal time
     * @param evidenceClass exploratory or certifiable source class
     * @param evidenceComplete whether the source exposed every fact it claimed to expose
     */
    public record SourceObservation(
            SourceRole role,
            MirrorArtifactRef artifactRef,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef targetCapabilityRef,
            String requestContextFingerprint,
            String semanticResultFingerprint,
            Instant completedAt,
            MirrorRunEvidence.EvidenceClass evidenceClass,
            boolean evidenceComplete
    ) {
        /** Validates one bounded payload-free signed-source coordinate set. */
        public SourceObservation {
            role = Objects.requireNonNull(role, "role");
            artifactRef = Objects.requireNonNull(
                    artifactRef, "artifactRef");
            scope = Objects.requireNonNull(scope, "scope");
            targetCapabilityRef = requireKind(
                    targetCapabilityRef,
                    "CAPABILITY",
                    "targetCapabilityRef");
            requestContextFingerprint = fingerprint(
                    requestContextFingerprint,
                    "requestContextFingerprint");
            semanticResultFingerprint = fingerprint(
                    semanticResultFingerprint,
                    "semanticResultFingerprint");
            completedAt = Objects.requireNonNull(
                    completedAt, "completedAt");
            evidenceClass = Objects.requireNonNull(
                    evidenceClass, "evidenceClass");
        }
    }

    /**
     * One typed normalized-fact comparison.
     *
     * @param dimension independently governed Fidelity dimension
     * @param baselineFingerprint normalized baseline fact set, blank only when unavailable
     * @param candidateFingerprint normalized candidate fact set, blank only when unavailable
     * @param outcome equality-derived result
     * @param diffTypes canonical closed difference vocabulary
     */
    public record DimensionComparison(
            DomainFidelityProfile.Dimension dimension,
            String baselineFingerprint,
            String candidateFingerprint,
            DiffOutcome outcome,
            List<DiffType> diffTypes
    ) {
        /** Derives match state from fact fingerprints and rejects cross-dimension diff labels. */
        public DimensionComparison {
            dimension = Objects.requireNonNull(
                    dimension, "dimension");
            DomainFidelityProfile.Dimension exactDimension =
                    dimension;
            if (!SUPPORTED_DIMENSIONS.contains(dimension)) {
                throw new IllegalArgumentException(
                        "shadow comparison dimension is not supported");
            }
            baselineFingerprint = optionalFingerprint(
                    baselineFingerprint);
            candidateFingerprint = optionalFingerprint(
                    candidateFingerprint);
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            diffTypes = diffTypes == null
                    ? List.of() : List.copyOf(diffTypes);
            List<DiffType> canonical =
                    diffTypes.stream()
                            .map(value -> Objects.requireNonNull(
                                    value, "diffType"))
                            .distinct()
                            .sorted(Comparator.comparing(Enum::name))
                            .toList();
            if (!canonical.equals(diffTypes)
                    || diffTypes.stream().anyMatch(
                    type -> !type.supports(
                            exactDimension))) {
                throw new IllegalArgumentException(
                        "shadow diff types must be unique, ordered, and dimension-compatible");
            }
            boolean baselinePresent =
                    !baselineFingerprint.isBlank();
            boolean candidatePresent =
                    !candidateFingerprint.isBlank();
            if (outcome == DiffOutcome.MATCH
                    && (!baselinePresent
                    || !candidatePresent
                    || !baselineFingerprint.equals(
                    candidateFingerprint)
                    || !diffTypes.isEmpty())
                    || outcome == DiffOutcome.MISMATCH
                    && (!baselinePresent
                    || !candidatePresent
                    || baselineFingerprint.equals(
                    candidateFingerprint)
                    || diffTypes.isEmpty()
                    || diffTypes.contains(
                    DiffType.EVIDENCE_GAP))
                    || outcome == DiffOutcome.INDETERMINATE
                    && (baselinePresent
                    && candidatePresent
                    || !diffTypes.equals(
                    List.of(DiffType.EVIDENCE_GAP)))) {
                throw new IllegalArgumentException(
                        "shadow comparison outcome is not derived from its fact fingerprints");
            }
        }
    }

    /** Closed equality-derived result for one normalized fact set. */
    public enum DiffOutcome {
        MATCH,
        MISMATCH,
        INDETERMINATE
    }

    /** Closed payload-free typed difference vocabulary. */
    public enum DiffType {
        BRANCH(DomainFidelityProfile.Dimension.BEHAVIOR),
        EFFECT(DomainFidelityProfile.Dimension.EFFECT),
        ERROR_CODE(DomainFidelityProfile.Dimension.BEHAVIOR),
        EVIDENCE_GAP(null),
        FALLBACK(DomainFidelityProfile.Dimension.BEHAVIOR),
        OUTPUT_SCHEMA(DomainFidelityProfile.Dimension.CONTRACT),
        OUTPUT_VALUE(DomainFidelityProfile.Dimension.BEHAVIOR),
        RETRY(DomainFidelityProfile.Dimension.BEHAVIOR),
        STATE(DomainFidelityProfile.Dimension.STATE_TRANSITION),
        TERMINAL_STATUS(DomainFidelityProfile.Dimension.BEHAVIOR),
        UNKNOWN_FIELD(DomainFidelityProfile.Dimension.CONTRACT);

        private final DomainFidelityProfile.Dimension dimension;

        DiffType(DomainFidelityProfile.Dimension dimension) {
            this.dimension = dimension;
        }

        boolean supports(
                DomainFidelityProfile.Dimension candidate) {
            return dimension == null || dimension == candidate;
        }
    }

    /**
     * Recomputes structural semantics and the exact comparison content address.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (comparisonFingerprint.isBlank()
                || !comparisonFingerprint.equals(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        withFingerprintAndSeal(
                                "",
                                VisualRunEvidenceSeal.unsigned()),
                        MAXIMUM_CANONICAL_BYTES))) {
            throw new IllegalArgumentException(
                    "shadow comparison fingerprint mismatch");
        }
    }

    /**
     * Returns domain-separated material for the detached producer signature.
     *
     * @param mapper canonical protocol mapper
     * @return canonical signing-material fingerprint
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        if (comparisonFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "shadow comparison must be content-addressed before signing");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        V1_SCHEMA_VERSION.equals(schemaVersion)
                                ? "RESOURCE_GATEWAY_READ_ONLY_SHADOW_COMPARISON_V1"
                                : "RESOURCE_GATEWAY_READ_ONLY_SHADOW_COMPARISON_V2",
                        schemaVersion,
                        comparisonId,
                        revision,
                        inventoryRef,
                        unitId,
                        observedAt,
                        comparisonFingerprint),
                MAXIMUM_ATTESTATION_BYTES);
    }

    /** @return exact immutable artifact reference consumed by Fidelity projection */
    public MirrorArtifactRef artifactRef() {
        if (comparisonFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "shadow comparison has no content address");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                comparisonId,
                revision,
                comparisonFingerprint);
    }

    /** @return whether both independently signed sources qualify as certifiable */
    public boolean certifiable() {
        return SCHEMA_VERSION.equals(schemaVersion)
                && comparisonPolicyRef != null
                && sourceResolutionAttestationRef != null
                && baseline.evidenceClass()
                == MirrorRunEvidence.EvidenceClass.CERTIFIABLE
                && candidate.evidenceClass()
                == MirrorRunEvidence.EvidenceClass.CERTIFIABLE;
    }

    /** @return whether both sources and every dimension expose a determinate fact comparison */
    public boolean evidenceComplete() {
        return baseline.evidenceComplete()
                && candidate.evidenceComplete()
                && results.stream().noneMatch(
                result -> result.outcome()
                        == DiffOutcome.INDETERMINATE);
    }

    /** @return identical comparison carrying a detached signature */
    public ReadOnlyShadowComparison withComparisonSeal(
            VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                comparisonFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    /** @return identical unsigned comparison carrying the supplied content address */
    ReadOnlyShadowComparison withFingerprint(
            String value) {
        return withFingerprintAndSeal(
                value,
                VisualRunEvidenceSeal.unsigned());
    }

    /** Keeps source and unit lineage out of generic logs. */
    @Override
    public String toString() {
        return "ReadOnlyShadowComparison[comparisonId="
                + comparisonId
                + ", revision=" + revision
                + ", resultCount=" + results.size() + "]";
    }

    private ReadOnlyShadowComparison withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new ReadOnlyShadowComparison(
                schemaVersion,
                comparisonId,
                revision,
                fingerprint,
                scope,
                inventoryRef,
                unitId,
                scenarioCaseRef,
                targetCapabilityRef,
                comparisonPolicyRef,
                sourceResolutionAttestationRef,
                accessProof,
                baseline,
                candidate,
                observedAt,
                results,
                seal);
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String comparisonId,
            long revision,
            MirrorArtifactRef inventoryRef,
            String unitId,
            Instant observedAt,
            String comparisonFingerprint
    ) {
    }

    private static String version(String value) {
        String normalized = value == null
                ? "" : value.trim();
        if (!V1_SCHEMA_VERSION.equals(normalized)
                && !SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported shadow comparison schemaVersion");
        }
        return normalized;
    }

    private static String identifier(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
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
            String value) {
        String normalized = value == null
                ? "" : value.trim();
        if (!normalized.isEmpty()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "optional fingerprint must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value,
            String kind,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }
}
