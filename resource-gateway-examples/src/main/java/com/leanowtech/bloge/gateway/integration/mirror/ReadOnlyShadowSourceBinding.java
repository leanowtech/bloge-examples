package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Signed payload-free binding for one detached baseline/candidate source pair.
 *
 * <p>The binding closes the ambiguity left by a plan and connector reference alone. A detached
 * Shadow request resolves this exact artifact, then independently resolves the referenced
 * candidate evidence bundle. Raw source values, credentials, endpoint addresses, and arbitrary
 * producer explanations are not representable.</p>
 *
 * @param schemaVersion exact binding protocol version
 * @param bindingFingerprint canonical content address excluding this field and the seal
 * @param bindingId stable binding identity
 * @param revision positive immutable revision
 * @param scope exact enterprise namespace
 * @param scenarioCaseRef exact classified Scenario case
 * @param targetCapabilityRef exact observed capability
 * @param candidatePlanRef exact sealed candidate plan
 * @param baselineBindingRef exact governed baseline connector generation
 * @param comparisonPolicyRef exact normalization and typed-diff policy
 * @param requestContextFingerprint canonical paired request identity
 * @param baselineObservationFingerprint canonical nested baseline observation identity
 * @param baseline payload-free baseline result and normalized fact fingerprints
 * @param candidateEvidenceRef exact independently signed candidate evidence bundle
 * @param validFrom earliest admitted detached consumption time
 * @param expiresAt exclusive detached consumption expiry
 * @param issuedAt source-binding authority issue time
 * @param bindingSeal detached source-binding authority signature
 */
public record ReadOnlyShadowSourceBinding(
        String schemaVersion,
        String bindingFingerprint,
        String bindingId,
        long revision,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef scenarioCaseRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef candidatePlanRef,
        MirrorArtifactRef baselineBindingRef,
        MirrorArtifactRef comparisonPolicyRef,
        String requestContextFingerprint,
        String baselineObservationFingerprint,
        BaselineObservation baseline,
        MirrorArtifactRef candidateEvidenceRef,
        Instant validFrom,
        Instant expiresAt,
        Instant issuedAt,
        VisualRunEvidenceSeal bindingSeal
) {
    /** Current detached source-binding wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowSourceBinding.v1";
    /** Artifact kind referenced by read-only Shadow job request v2. */
    public static final String ARTIFACT_KIND =
            "SHADOW_SOURCE_BINDING";
    /** Artifact kind projected for the independently resolvable baseline observation. */
    public static final String BASELINE_ARTIFACT_KIND =
            "SHADOW_BASELINE_OBSERVATION";
    /** Maximum canonical binding material admitted to content addressing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            2 * 1024 * 1024;
    /** Maximum domain-separated signing material. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<DomainFidelityProfile.Dimension>
            SUPPORTED_DIMENSIONS = EnumSet.of(
            DomainFidelityProfile.Dimension.BEHAVIOR,
            DomainFidelityProfile.Dimension.CONTRACT,
            DomainFidelityProfile.Dimension.EFFECT,
            DomainFidelityProfile.Dimension.STATE_TRANSITION);

    /** Validates exact source coordinates, bounded facts, validity window, and seal shape. */
    public ReadOnlyShadowSourceBinding {
        if (!SCHEMA_VERSION.equals(normalized(schemaVersion))) {
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow source-binding schemaVersion");
        }
        schemaVersion = SCHEMA_VERSION;
        bindingFingerprint = optionalFingerprint(
                bindingFingerprint, "bindingFingerprint");
        bindingId = identifier(bindingId, "bindingId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "source-binding revision must be positive");
        }
        scope = Objects.requireNonNull(scope, "scope");
        scenarioCaseRef = kind(
                scenarioCaseRef, "SCENARIO_CASE", "scenarioCaseRef");
        targetCapabilityRef = kind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        candidatePlanRef = kind(
                candidatePlanRef, "MIRROR_PLAN", "candidatePlanRef");
        baselineBindingRef = kind(
                baselineBindingRef,
                "SHADOW_BASELINE_BINDING",
                "baselineBindingRef");
        comparisonPolicyRef = kind(
                comparisonPolicyRef,
                "SHADOW_COMPARISON_POLICY",
                "comparisonPolicyRef");
        requestContextFingerprint = fingerprint(
                requestContextFingerprint,
                "requestContextFingerprint");
        baselineObservationFingerprint = optionalFingerprint(
                baselineObservationFingerprint,
                "baselineObservationFingerprint");
        baseline = Objects.requireNonNull(
                baseline, "baseline");
        candidateEvidenceRef = kind(
                candidateEvidenceRef,
                "MIRROR_EVIDENCE_BUNDLE",
                "candidateEvidenceRef");
        if (candidateEvidenceRef.revision() != 1) {
            throw new IllegalArgumentException(
                    "candidateEvidenceRef revision must be one");
        }
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        bindingSeal = bindingSeal == null
                ? VisualRunEvidenceSeal.unsigned() : bindingSeal;
        if (!expiresAt.isAfter(validFrom)
                || issuedAt.isBefore(baseline.observedAt())
                || issuedAt.isAfter(validFrom)) {
            throw new IllegalArgumentException(
                    "source-binding temporal closure is invalid");
        }
    }

    /**
     * Payload-free baseline result embedded in the signed source binding.
     *
     * @param semanticResultFingerprint canonical baseline business-result identity
     * @param normalizedFactFingerprints canonical fact-set identity by supported dimension
     * @param observedAt baseline source terminal observation time
     * @param evidenceClass exploratory or certifiable source class
     * @param evidenceComplete whether every claimed baseline fact is complete
     * @param writeCredentialExposed whether a write-capable credential reached the source
     * @param writeAttemptCount observed external write attempts
     */
    public record BaselineObservation(
            String semanticResultFingerprint,
            Map<DomainFidelityProfile.Dimension, String>
                    normalizedFactFingerprints,
            Instant observedAt,
            MirrorRunEvidence.EvidenceClass evidenceClass,
            boolean evidenceComplete,
            boolean writeCredentialExposed,
            long writeAttemptCount
    ) {
        /** Canonicalizes supported facts while retaining measured write violations. */
        public BaselineObservation {
            semanticResultFingerprint = fingerprint(
                    semanticResultFingerprint,
                    "semanticResultFingerprint");
            normalizedFactFingerprints = facts(
                    normalizedFactFingerprints);
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            evidenceClass = Objects.requireNonNull(
                    evidenceClass, "evidenceClass");
            if (writeAttemptCount < 0
                    || writeAttemptCount > 1_000_000_000L
                    || evidenceClass
                    == MirrorRunEvidence.EvidenceClass.CERTIFIABLE
                    && (!evidenceComplete
                    || writeCredentialExposed
                    || writeAttemptCount != 0)) {
                throw new IllegalArgumentException(
                        "baseline observation evidence closure is invalid");
            }
        }
    }

    /**
     * Builds the exact content-addressed outer artifact reference.
     *
     * @return exact content-addressed source-binding reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                bindingId,
                revision,
                fingerprint(
                        bindingFingerprint,
                        "bindingFingerprint"));
    }

    /**
     * Builds the exact content-addressed nested baseline reference.
     *
     * @return independently addressable nested baseline observation reference
     */
    public MirrorArtifactRef baselineArtifactRef() {
        return new MirrorArtifactRef(
                BASELINE_ARTIFACT_KIND,
                bindingId + ":baseline",
                revision,
                fingerprint(
                        baselineObservationFingerprint,
                        "baselineObservationFingerprint"));
    }

    /**
     * Recomputes nested baseline and complete source-binding content addresses.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (!baselineFingerprint(mapper).equals(
                baselineObservationFingerprint)
                || !calculateFingerprint(mapper).equals(
                bindingFingerprint)) {
            throw new IllegalArgumentException(
                    "read-only Shadow source-binding fingerprint mismatch");
        }
    }

    /** @return identical binding carrying the supplied content addresses */
    ReadOnlyShadowSourceBinding withFingerprints(
            String baselineFingerprint,
            String bindingFingerprint) {
        return copy(
                baselineFingerprint,
                bindingFingerprint,
                VisualRunEvidenceSeal.unsigned());
    }

    /**
     * Copies the binding with its detached authority seal.
     *
     * @param seal verified detached source-binding authority seal
     * @return identical binding carrying a detached authority seal
     */
    public ReadOnlyShadowSourceBinding withBindingSeal(
            VisualRunEvidenceSeal seal) {
        return copy(
                baselineObservationFingerprint,
                bindingFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    /**
     * Returns domain-separated material signed by the source-binding authority.
     *
     * @param mapper canonical protocol mapper
     * @return canonical signing-material fingerprint
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_BINDING_V1",
                        schemaVersion,
                        bindingId,
                        revision,
                        scope,
                        issuedAt,
                        fingerprint(
                                bindingFingerprint,
                                "bindingFingerprint")),
                MAXIMUM_ATTESTATION_BYTES);
    }

    String baselineFingerprint(ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                mapper,
                new BaselineFingerprintMaterial(
                        "RESOURCE_GATEWAY_READ_ONLY_SHADOW_BASELINE_OBSERVATION_V1",
                        schemaVersion,
                        bindingId,
                        revision,
                        scope,
                        targetCapabilityRef,
                        baselineBindingRef,
                        comparisonPolicyRef,
                        requestContextFingerprint,
                        baseline),
                MAXIMUM_CANONICAL_BYTES);
    }

    String calculateFingerprint(ObjectMapper mapper) {
        ObjectNode material = Objects.requireNonNull(
                mapper, "mapper").valueToTree(
                copy(
                        baselineObservationFingerprint,
                        "",
                        VisualRunEvidenceSeal.unsigned()));
        material.remove("bindingSeal");
        return ProtocolFingerprint.ofBounded(
                mapper,
                material,
                MAXIMUM_CANONICAL_BYTES);
    }

    private ReadOnlyShadowSourceBinding copy(
            String baselineFingerprint,
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new ReadOnlyShadowSourceBinding(
                schemaVersion,
                fingerprint,
                bindingId,
                revision,
                scope,
                scenarioCaseRef,
                targetCapabilityRef,
                candidatePlanRef,
                baselineBindingRef,
                comparisonPolicyRef,
                requestContextFingerprint,
                baselineFingerprint,
                baseline,
                candidateEvidenceRef,
                validFrom,
                expiresAt,
                issuedAt,
                seal);
    }

    private static Map<DomainFidelityProfile.Dimension, String> facts(
            Map<DomainFidelityProfile.Dimension, String> supplied) {
        if (supplied == null || supplied.isEmpty()
                || supplied.size() > SUPPORTED_DIMENSIONS.size()) {
            throw new IllegalArgumentException(
                    "baseline normalized facts are empty or unbounded");
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
                                "baseline fact dimension is not comparable");
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
                    field + " must reference " + expected);
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

    private record BaselineFingerprintMaterial(
            String domain,
            String schemaVersion,
            String bindingId,
            long revision,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef targetCapabilityRef,
            MirrorArtifactRef baselineBindingRef,
            MirrorArtifactRef comparisonPolicyRef,
            String requestContextFingerprint,
            BaselineObservation baseline
    ) {
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String bindingId,
            long revision,
            CapabilitySnapshot.Scope scope,
            Instant issuedAt,
            String bindingFingerprint
    ) {
    }
}
