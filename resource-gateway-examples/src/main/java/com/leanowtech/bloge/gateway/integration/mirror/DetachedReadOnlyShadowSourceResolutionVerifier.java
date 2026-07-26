package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Independent verifier and attestation producer for exact detached Shadow sources.
 *
 * <p>This component does not trust connector output. It re-resolves the signed binding and
 * candidate bundle, independently verifies both signatures and content addresses, re-runs the
 * exact normalization policy, compares every source coordinate and zero-write counter, and only
 * then appends a signed source-resolution attestation.</p>
 */
public final class DetachedReadOnlyShadowSourceResolutionVerifier
        implements ReadOnlyShadowSourceResolutionVerifier {
    private static final int MAXIMUM_IDENTITY_BYTES =
            64 * 1024;
    private static final String IDENTITY_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_IDENTITY_V1";

    private final ReadOnlyShadowSourceBindingService bindings;
    private final MirrorEvidenceRepository evidence;
    private final MirrorEvidenceIntegrityService evidenceIntegrity;
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy;
    private final ReadOnlyShadowSourceResolutionAttestationRepository
            attestations;
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            attestationIntegrity;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * Creates the independent detached source-resolution boundary.
     *
     * @param bindings exact signed source-binding resolver
     * @param evidence append-only signed Mirror evidence repository
     * @param evidenceIntegrity independent candidate evidence verifier
     * @param policy exact built-in normalization and comparison policy
     * @param attestations append-only source-resolution repository
     * @param attestationIntegrity source-resolution signing authority
     * @param mapper canonical protocol mapper
     * @param clock trusted resolution clock
     */
    public DetachedReadOnlyShadowSourceResolutionVerifier(
            ReadOnlyShadowSourceBindingService bindings,
            MirrorEvidenceRepository evidence,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
            ReadOnlyShadowSourceResolutionAttestationRepository
                    attestations,
            ReadOnlyShadowSourceResolutionAttestationIntegrity
                    attestationIntegrity,
            ObjectMapper mapper,
            Clock clock) {
        this.bindings = Objects.requireNonNull(
                bindings, "bindings");
        this.evidence = Objects.requireNonNull(
                evidence, "evidence");
        this.evidenceIntegrity = Objects.requireNonNull(
                evidenceIntegrity, "evidenceIntegrity");
        this.policy = Objects.requireNonNull(
                policy, "policy");
        this.attestations = Objects.requireNonNull(
                attestations, "attestations");
        this.attestationIntegrity = Objects.requireNonNull(
                attestationIntegrity,
                "attestationIntegrity");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean ready() {
        try {
            return bindings.ready()
                    && evidenceIntegrity.available()
                    && policy.ready()
                    && attestationIntegrity.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public MirrorArtifactRef verify(
            Verification verification) {
        Verification exact =
                Objects.requireNonNull(
                        verification, "verification");
        ReadOnlyShadowJobRequest request = exact.request();
        if (!ReadOnlyShadowJobRequest.V2_SCHEMA_VERSION.equals(
                request.schemaVersion())
                || request.effectiveSourceMode()
                != ReadOnlyShadowJobRequest.SourceMode
                .DETACHED_EVIDENCE
                || request.sourceBindingRef() == null) {
            throw new IllegalArgumentException(
                    "detached source verification requires a v2 exact binding");
        }
        policy.requirePolicy(
                request.comparisonPolicyRef());
        Instant now = clock.instant();
        if (now.isBefore(exact.confirmation().confirmedAt())
                || !request.deadlineAt().isAfter(now)) {
            throw new IllegalArgumentException(
                    "source-resolution attestation time is outside the job window");
        }
        ReadOnlyShadowSourceBinding binding =
                bindings.resolve(
                        request.scope(),
                        request.sourceBindingRef(),
                        now);
        requireBinding(request, binding);

        MirrorEvidenceBundle candidateBundle =
                evidence.find(
                        binding.scope(),
                        binding.candidateEvidenceRef().id())
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "candidate evidence is unavailable during source verification"));
        MirrorEvidenceBundle verifiedCandidate =
                evidenceIntegrity
                        .requireVerified(candidateBundle)
                        .bundle();
        MirrorRunEvidence candidateEvidence =
                verifiedCandidate.evidence();
        requireCandidateBinding(
                binding,
                verifiedCandidate);

        ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution baseline =
                baseline(binding, exact.baseline());
        ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution candidate =
                candidate(
                        binding,
                        candidateEvidence,
                        verifiedCandidate.bundleFingerprint(),
                        exact.candidate());
        String attestationId = attestationId(
                exact,
                baseline,
                candidate);
        ReadOnlyShadowSourceResolutionAttestation unsigned =
                new ReadOnlyShadowSourceResolutionAttestation(
                        ReadOnlyShadowSourceResolutionAttestation
                                .SCHEMA_VERSION,
                        "",
                        attestationId,
                        1,
                        request.scope(),
                        request.requestId(),
                        exact.executionId(),
                        binding.artifactRef(),
                        policy.reference(),
                        binding.requestContextFingerprint(),
                        exact.admission()
                                .admissionFingerprint(),
                        exact.admission().admittedAt(),
                        exact.confirmation().confirmedAt(),
                        baseline,
                        candidate,
                        now,
                        VisualRunEvidenceSeal.unsigned());
        ReadOnlyShadowSourceResolutionAttestation signed =
                attestationIntegrity.sign(unsigned);
        return attestations.create(signed).artifactRef();
    }

    private void requireBinding(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowSourceBinding binding) {
        if (!request.sourceBindingRef().equals(
                binding.artifactRef())
                || !request.scope().equals(binding.scope())
                || !request.scenarioCaseRef().equals(
                binding.scenarioCaseRef())
                || !request.targetCapabilityRef().equals(
                binding.targetCapabilityRef())
                || !request.candidatePlanRef().equals(
                binding.candidatePlanRef())
                || !request.baselineBindingRef().equals(
                binding.baselineBindingRef())
                || !policy.reference().equals(
                binding.comparisonPolicyRef())) {
            throw new IllegalArgumentException(
                    "source binding differs from the verification request");
        }
    }

    private static void requireCandidateBinding(
            ReadOnlyShadowSourceBinding binding,
            MirrorEvidenceBundle bundle) {
        MirrorRunEvidence evidence = bundle.evidence();
        MirrorArtifactRef reference = new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                evidence.runId(),
                1,
                bundle.bundleFingerprint());
        if (!binding.candidateEvidenceRef().equals(reference)
                || !binding.scope().equals(evidence.scope())
                || !binding.candidatePlanRef().id().equals(
                evidence.planId())
                || !binding.candidatePlanRef().fingerprint().equals(
                evidence.planFingerprint())
                || !binding.targetCapabilityRef().equals(
                evidence.rootCapability())
                || !binding.requestContextFingerprint().equals(
                evidence.requestContextFingerprint())) {
            throw new IllegalArgumentException(
                    "candidate evidence differs from the source binding");
        }
    }

    private ReadOnlyShadowSourceResolutionAttestation.SourceResolution
    baseline(
            ReadOnlyShadowSourceBinding binding,
            ReadOnlyShadowConnectorObservation observation) {
        ReadOnlyShadowSourceBinding.BaselineObservation source =
                binding.baseline();
        requireObservation(
                observation,
                ReadOnlyShadowComparison.SourceRole.BASELINE,
                binding.baselineArtifactRef(),
                binding,
                source.semanticResultFingerprint(),
                source.normalizedFactFingerprints(),
                source.evidenceClass(),
                source.evidenceComplete(),
                source.writeCredentialExposed(),
                source.writeAttemptCount());
        return new ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution(
                ReadOnlyShadowComparison.SourceRole.BASELINE,
                binding.baselineArtifactRef(),
                source.semanticResultFingerprint(),
                source.normalizedFactFingerprints(),
                source.observedAt(),
                observation.source().completedAt(),
                source.evidenceClass(),
                source.evidenceComplete(),
                source.writeCredentialExposed(),
                source.writeAttemptCount());
    }

    private ReadOnlyShadowSourceResolutionAttestation.SourceResolution
    candidate(
            ReadOnlyShadowSourceBinding binding,
            MirrorRunEvidence evidence,
            String bundleFingerprint,
            ReadOnlyShadowConnectorObservation observation) {
        MirrorArtifactRef artifactRef = new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                evidence.runId(),
                1,
                bundleFingerprint);
        Map<DomainFidelityProfile.Dimension, String> facts =
                policy.normalize(evidence);
        boolean complete = evidenceComplete(evidence.status());
        requireObservation(
                observation,
                ReadOnlyShadowComparison.SourceRole.CANDIDATE,
                artifactRef,
                binding,
                evidence.semanticResultFingerprint(),
                facts,
                evidence.evidenceClass(),
                complete,
                false,
                0);
        return new ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution(
                ReadOnlyShadowComparison.SourceRole.CANDIDATE,
                artifactRef,
                evidence.semanticResultFingerprint(),
                facts,
                evidence.completedAt(),
                observation.source().completedAt(),
                evidence.evidenceClass(),
                complete,
                false,
                0);
    }

    private void requireObservation(
            ReadOnlyShadowConnectorObservation observation,
            ReadOnlyShadowComparison.SourceRole role,
            MirrorArtifactRef artifactRef,
            ReadOnlyShadowSourceBinding binding,
            String semanticResultFingerprint,
            Map<DomainFidelityProfile.Dimension, String> facts,
            MirrorRunEvidence.EvidenceClass evidenceClass,
            boolean evidenceComplete,
            boolean writeCredentialExposed,
            long writeAttemptCount) {
        ReadOnlyShadowConnectorObservation exact =
                Objects.requireNonNull(
                        observation, "observation");
        ReadOnlyShadowComparison.SourceObservation source =
                exact.source();
        if (source.role() != role
                || !artifactRef.equals(source.artifactRef())
                || !binding.scope().equals(source.scope())
                || !binding.targetCapabilityRef().equals(
                source.targetCapabilityRef())
                || !binding.requestContextFingerprint().equals(
                source.requestContextFingerprint())
                || !semanticResultFingerprint.equals(
                source.semanticResultFingerprint())
                || evidenceClass != source.evidenceClass()
                || evidenceComplete != source.evidenceComplete()
                || !policy.reference().equals(
                exact.comparisonPolicyRef())
                || !facts.equals(
                exact.normalizedFactFingerprints())
                || writeCredentialExposed
                != exact.writeCredentialExposed()
                || writeAttemptCount
                != exact.writeAttemptCount()) {
            throw new IllegalArgumentException(
                    "connector observation differs from independently resolved source");
        }
    }

    private String attestationId(
            Verification verification,
            ReadOnlyShadowSourceResolutionAttestation
                    .SourceResolution baseline,
            ReadOnlyShadowSourceResolutionAttestation
                    .SourceResolution candidate) {
        String fingerprint =
                ProtocolFingerprint.ofBounded(
                        mapper,
                        new ResolutionIdentity(
                                IDENTITY_DOMAIN,
                                verification.executionId(),
                                verification.admission()
                                        .admissionFingerprint(),
                                verification.confirmation()
                                        .confirmedAt(),
                                baseline.artifactRef(),
                                baseline.resolvedAt(),
                                candidate.artifactRef(),
                                candidate.resolvedAt()),
                        MAXIMUM_IDENTITY_BYTES);
        return "source-resolution-"
                + fingerprint.substring("sha256:".length());
    }

    private static boolean evidenceComplete(
            MirrorRunEvidence.Status status) {
        return switch (status) {
            case EVIDENCE_INCOMPLETE,
                 CONTROL_PLAN_UNAVAILABLE -> false;
            default -> true;
        };
    }

    private record ResolutionIdentity(
            String domain,
            String executionId,
            String admissionFingerprint,
            Instant confirmedAt,
            MirrorArtifactRef baselineRef,
            Instant baselineResolvedAt,
            MirrorArtifactRef candidateRef,
            Instant candidateResolvedAt
    ) {
    }
}
