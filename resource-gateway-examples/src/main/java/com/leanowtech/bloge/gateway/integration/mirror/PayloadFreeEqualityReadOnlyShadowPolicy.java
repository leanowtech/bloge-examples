package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in content-addressed normalization and equality policy for detached Shadow evidence.
 *
 * <p>The policy deliberately compares only facts already represented by signed payload-free
 * evidence. Behavior uses the shared semantic-result fingerprint, contract uses the complete
 * capability-closure fingerprint, effect uses the canonical external binding and resolution
 * closure, and state transition uses the nested state-evidence content address. The policy
 * identity covers those rules, so changing a rule requires a new artifact reference.</p>
 */
public final class PayloadFreeEqualityReadOnlyShadowPolicy
        implements ReadOnlyShadowComparisonEngine {
    /** Stable built-in policy artifact identity. */
    public static final String POLICY_ID =
            "payload-free-equality-v1";
    /**
     * Frozen content address shared with independent protocol consumers.
     *
     * <p>Changing the policy material requires a new policy id and protocol generation; it must
     * never silently rewrite this artifact.</p>
     */
    public static final String POLICY_FINGERPRINT =
            "sha256:66cb081470a0492453c5a35bbf7e9b2bb530abc2dbaaf86be8a564bec4c11f43";
    private static final int MAXIMUM_POLICY_BYTES =
            64 * 1024;
    private static final int MAXIMUM_FACT_BYTES =
            MirrorEvidenceIntegrityService.MAXIMUM_EVIDENCE_BYTES;

    private final ObjectMapper mapper;
    private final MirrorArtifactRef reference;

    /**
     * Creates the immutable built-in policy from its canonical public specification.
     *
     * @param mapper canonical protocol mapper
     */
    public PayloadFreeEqualityReadOnlyShadowPolicy(
            ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        String calculatedFingerprint =
                ProtocolFingerprint.ofBounded(
                        mapper,
                        policyMaterial(),
                        MAXIMUM_POLICY_BYTES);
        if (!POLICY_FINGERPRINT.equals(
                calculatedFingerprint)) {
            throw new IllegalStateException(
                    "built-in Shadow comparison policy protocol drift");
        }
        this.reference = new MirrorArtifactRef(
                "SHADOW_COMPARISON_POLICY",
                POLICY_ID,
                1,
                POLICY_FINGERPRINT);
    }

    /**
     * Returns the exact built-in comparison-policy artifact.
     *
     * @return content-addressed immutable policy reference
     */
    public MirrorArtifactRef reference() {
        return reference;
    }

    @Override
    public boolean ready() {
        return true;
    }

    /**
     * Projects one independently verified candidate bundle into canonical policy facts.
     *
     * @param evidence verified payload-free terminal run evidence
     * @return ordered supported dimension fingerprints
     */
    public Map<DomainFidelityProfile.Dimension, String> normalize(
            MirrorRunEvidence evidence) {
        MirrorRunEvidence exact =
                Objects.requireNonNull(evidence, "evidence");
        EnumMap<DomainFidelityProfile.Dimension, String> facts =
                new EnumMap<>(DomainFidelityProfile.Dimension.class);
        facts.put(
                DomainFidelityProfile.Dimension.BEHAVIOR,
                exact.semanticResultFingerprint());
        facts.put(
                DomainFidelityProfile.Dimension.CONTRACT,
                exact.capabilityClosureFingerprint());
        facts.put(
                DomainFidelityProfile.Dimension.EFFECT,
                ProtocolFingerprint.ofBounded(
                        mapper,
                        new EffectFacts(
                                exact.externalBindings(),
                                exact.resolutions()),
                        MAXIMUM_FACT_BYTES));
        if (exact.stateEvidence() != null) {
            facts.put(
                    DomainFidelityProfile.Dimension.STATE_TRANSITION,
                    exact.stateEvidence()
                            .stateEvidenceFingerprint());
        }
        LinkedHashMap<DomainFidelityProfile.Dimension, String> ordered =
                new LinkedHashMap<>();
        facts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Enum::name)))
                .forEach(entry ->
                        ordered.put(
                                entry.getKey(),
                                entry.getValue()));
        return Collections.unmodifiableMap(ordered);
    }

    @Override
    public List<ReadOnlyShadowComparison.DimensionComparison>
    compare(
            MirrorArtifactRef comparisonPolicyRef,
            ReadOnlyShadowConnectorObservation baseline,
            ReadOnlyShadowConnectorObservation candidate) {
        requirePolicy(comparisonPolicyRef);
        ReadOnlyShadowConnectorObservation exactBaseline =
                Objects.requireNonNull(baseline, "baseline");
        ReadOnlyShadowConnectorObservation exactCandidate =
                Objects.requireNonNull(candidate, "candidate");
        if (!reference.equals(
                exactBaseline.comparisonPolicyRef())
                || !reference.equals(
                exactCandidate.comparisonPolicyRef())) {
            throw new IllegalArgumentException(
                    "connector observations use a different comparison policy");
        }
        List<DomainFidelityProfile.Dimension> dimensions =
                new ArrayList<>();
        dimensions.addAll(
                exactBaseline.normalizedFactFingerprints()
                        .keySet());
        exactCandidate.normalizedFactFingerprints()
                .keySet().stream()
                .filter(dimension ->
                        !dimensions.contains(dimension))
                .forEach(dimensions::add);
        dimensions.sort(Comparator.comparing(Enum::name));
        return dimensions.stream()
                .map(dimension -> comparison(
                        dimension,
                        exactBaseline.normalizedFactFingerprints()
                                .getOrDefault(dimension, ""),
                        exactCandidate.normalizedFactFingerprints()
                                .getOrDefault(dimension, "")))
                .toList();
    }

    void requirePolicy(
            MirrorArtifactRef comparisonPolicyRef) {
        if (!reference.equals(
                Objects.requireNonNull(
                        comparisonPolicyRef,
                        "comparisonPolicyRef"))) {
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow comparison policy");
        }
    }

    private static ReadOnlyShadowComparison.DimensionComparison
    comparison(
            DomainFidelityProfile.Dimension dimension,
            String baseline,
            String candidate) {
        if (baseline.isBlank() || candidate.isBlank()) {
            return new ReadOnlyShadowComparison
                    .DimensionComparison(
                    dimension,
                    baseline,
                    candidate,
                    ReadOnlyShadowComparison
                            .DiffOutcome.INDETERMINATE,
                    List.of(
                            ReadOnlyShadowComparison
                                    .DiffType.EVIDENCE_GAP));
        }
        if (baseline.equals(candidate)) {
            return new ReadOnlyShadowComparison
                    .DimensionComparison(
                    dimension,
                    baseline,
                    candidate,
                    ReadOnlyShadowComparison
                            .DiffOutcome.MATCH,
                    List.of());
        }
        return new ReadOnlyShadowComparison
                .DimensionComparison(
                dimension,
                baseline,
                candidate,
                ReadOnlyShadowComparison
                        .DiffOutcome.MISMATCH,
                List.of(diffType(dimension)));
    }

    private static ReadOnlyShadowComparison.DiffType diffType(
            DomainFidelityProfile.Dimension dimension) {
        return switch (dimension) {
            case BEHAVIOR ->
                    ReadOnlyShadowComparison
                            .DiffType.OUTPUT_VALUE;
            case CONTRACT ->
                    ReadOnlyShadowComparison
                            .DiffType.OUTPUT_SCHEMA;
            case EFFECT ->
                    ReadOnlyShadowComparison
                            .DiffType.EFFECT;
            case STATE_TRANSITION ->
                    ReadOnlyShadowComparison
                            .DiffType.STATE;
            default -> throw new IllegalArgumentException(
                    "unsupported Shadow comparison dimension");
        };
    }

    private static PolicyMaterial policyMaterial() {
        return new PolicyMaterial(
                "RESOURCE_GATEWAY_PAYLOAD_FREE_EQUALITY_POLICY_V1",
                List.of(
                        "BEHAVIOR=semanticResultFingerprint",
                        "CONTRACT=capabilityClosureFingerprint",
                        "EFFECT=externalBindings+resolutions",
                        "STATE_TRANSITION=stateEvidenceFingerprint"),
                Map.of(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        ReadOnlyShadowComparison.DiffType.OUTPUT_VALUE,
                        DomainFidelityProfile.Dimension.CONTRACT,
                        ReadOnlyShadowComparison.DiffType.OUTPUT_SCHEMA,
                        DomainFidelityProfile.Dimension.EFFECT,
                        ReadOnlyShadowComparison.DiffType.EFFECT,
                        DomainFidelityProfile.Dimension.STATE_TRANSITION,
                        ReadOnlyShadowComparison.DiffType.STATE));
    }

    private record PolicyMaterial(
            String domain,
            List<String> normalizationRules,
            Map<DomainFidelityProfile.Dimension,
                    ReadOnlyShadowComparison.DiffType>
                    mismatchTypes
    ) {
    }

    private record EffectFacts(
            List<MirrorRunEvidence.ExternalBinding>
                    externalBindings,
            List<MirrorResolution> resolutions
    ) {
    }
}
