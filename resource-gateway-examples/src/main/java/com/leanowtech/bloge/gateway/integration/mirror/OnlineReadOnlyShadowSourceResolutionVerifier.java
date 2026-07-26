package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Independent exact-read verifier and attestation producer for one online Shadow source pair.
 *
 * <p>This verifier runs after both connectors and after terminal authority confirmation. It does
 * not trust connector projections. It independently resolves and verifies the signed regional
 * baseline observation and signed candidate Mirror bundle, reconstructs both payload-free
 * commands, reruns the exact normalization policy, closes every connector coordinate and
 * zero-write counter, and only then appends a signed v2 source-resolution attestation.</p>
 */
public final class OnlineReadOnlyShadowSourceResolutionVerifier
        implements ReadOnlyShadowSourceResolutionVerifier {
    private static final int MAXIMUM_IDENTITY_BYTES =
            96 * 1024;
    private static final String IDENTITY_DOMAIN =
            "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_IDENTITY_V2";

    private final OnlineReadOnlyShadowBaselineAuthority
            baselineAuthority;
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            baselineIntegrity;
    private final OnlineReadOnlyShadowCandidateAuthority
            candidateAuthority;
    private final MirrorEvidenceIntegrityService evidenceIntegrity;
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy;
    private final ReadOnlyShadowSourceResolutionAttestationRepository
            attestations;
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            attestationIntegrity;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * Creates one independent online paired-source resolution boundary.
     *
     * @param baselineAuthority exact regional baseline artifact resolver
     * @param baselineIntegrity independent baseline observation verifier
     * @param candidateAuthority exact candidate evidence resolver
     * @param evidenceIntegrity independent candidate Mirror evidence verifier
     * @param policy exact built-in normalized-fact policy
     * @param attestations append-only source-resolution repository
     * @param attestationIntegrity source-resolution signing authority
     * @param mapper canonical protocol mapper
     * @param clock trusted resolution clock
     */
    public OnlineReadOnlyShadowSourceResolutionVerifier(
            OnlineReadOnlyShadowBaselineAuthority
                    baselineAuthority,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    baselineIntegrity,
            OnlineReadOnlyShadowCandidateAuthority
                    candidateAuthority,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
            ReadOnlyShadowSourceResolutionAttestationRepository
                    attestations,
            ReadOnlyShadowSourceResolutionAttestationIntegrity
                    attestationIntegrity,
            ObjectMapper mapper,
            Clock clock) {
        this.baselineAuthority = Objects.requireNonNull(
                baselineAuthority, "baselineAuthority");
        this.baselineIntegrity = Objects.requireNonNull(
                baselineIntegrity, "baselineIntegrity");
        this.candidateAuthority = Objects.requireNonNull(
                candidateAuthority, "candidateAuthority");
        this.evidenceIntegrity = Objects.requireNonNull(
                evidenceIntegrity, "evidenceIntegrity");
        this.policy = Objects.requireNonNull(
                policy, "policy");
        this.attestations = Objects.requireNonNull(
                attestations, "attestations");
        this.attestationIntegrity = Objects.requireNonNull(
                attestationIntegrity,
                "attestationIntegrity");
        this.mapper = Objects.requireNonNull(
                mapper, "mapper");
        this.clock = Objects.requireNonNull(
                clock, "clock");
    }

    @Override
    public boolean ready() {
        try {
            return baselineAuthority.ready()
                    && baselineIntegrity.available()
                    && candidateAuthority.ready()
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
        ReadOnlyShadowJobRequest request =
                exact.request();
        if (!ReadOnlyShadowJobRequest.SCHEMA_VERSION
                .equals(request.schemaVersion())
                || request.effectiveSourceMode()
                != ReadOnlyShadowJobRequest.SourceMode
                .ONLINE_EXECUTION
                || request.sourceBindingRef() != null) {
            throw invalid();
        }
        policy.requirePolicy(
                request.comparisonPolicyRef());
        Instant now = clock.instant();
        if (now.isBefore(
                exact.confirmation().confirmedAt())
                || !request.deadlineAt().isAfter(now)) {
            throw invalid();
        }

        OnlineReadOnlyShadowBaselineCommand
                baselineCommand =
                baselineCommand(exact);
        OnlineReadOnlyShadowBaselineObservation
                baseline =
                resolveBaseline(
                        exact,
                        baselineCommand);
        OnlineReadOnlyShadowCandidateCommand
                candidateCommand =
                candidateCommand(
                        exact,
                        baseline);
        MirrorEvidenceBundle candidate =
                resolveCandidate(
                        exact,
                        candidateCommand);
        ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution baselineResolution =
                baselineResolution(
                        baseline,
                        now);
        ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution candidateResolution =
                candidateResolution(
                        candidate,
                        now);
        String attestationId =
                attestationId(
                        exact,
                        baselineCommand,
                        candidateCommand,
                        baselineResolution,
                        candidateResolution);
        ReadOnlyShadowSourceResolutionAttestation unsigned =
                new ReadOnlyShadowSourceResolutionAttestation(
                        ReadOnlyShadowSourceResolutionAttestation
                                .ONLINE_SCHEMA_VERSION,
                        "",
                        attestationId,
                        1,
                        request.scope(),
                        request.requestId(),
                        exact.executionId(),
                        ReadOnlyShadowJobRequest.SourceMode
                                .ONLINE_EXECUTION,
                        null,
                        baselineCommand.commandFingerprint(
                                mapper),
                        candidateCommand.commandFingerprint(
                                mapper),
                        policy.reference(),
                        baseline.requestContextFingerprint(),
                        exact.admission()
                                .admissionFingerprint(),
                        exact.admission().admittedAt(),
                        exact.confirmation().confirmedAt(),
                        baselineResolution,
                        candidateResolution,
                        now,
                        VisualRunEvidenceSeal.unsigned());
        try {
            return attestations.create(
                    attestationIntegrity.sign(
                            unsigned))
                    .artifactRef();
        } catch (ReadOnlyShadowSourceResolutionAttestationIntegrity
                         .Violation unavailable) {
            if (unavailable.reason()
                    == ReadOnlyShadowSourceResolutionAttestationIntegrity
                    .Reason.KEY_UNAVAILABLE) {
                throw unavailable();
            }
            throw invalid();
        }
    }

    private OnlineReadOnlyShadowBaselineCommand
    baselineCommand(
            Verification verification) {
        ReadOnlyShadowJobRequest request =
                verification.request();
        Instant deadline = request.deadlineAt()
                .isBefore(
                        verification.admission()
                                .validUntil())
                ? request.deadlineAt()
                : verification.admission()
                .validUntil();
        return new OnlineReadOnlyShadowBaselineCommand(
                OnlineReadOnlyShadowBaselineCommand
                        .SCHEMA_VERSION,
                verification.executionId(),
                request.requestId(),
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.baselineBindingRef(),
                request.comparisonPolicyRef(),
                request.accessGrant(),
                verification.admission()
                        .admissionFingerprint(),
                verification.admission()
                        .admittedAt(),
                deadline);
    }

    private OnlineReadOnlyShadowCandidateCommand
    candidateCommand(
            Verification verification,
            OnlineReadOnlyShadowBaselineObservation baseline) {
        ReadOnlyShadowJobRequest request =
                verification.request();
        Instant deadline = request.deadlineAt()
                .isBefore(
                        verification.admission()
                                .validUntil())
                ? request.deadlineAt()
                : verification.admission()
                .validUntil();
        return new OnlineReadOnlyShadowCandidateCommand(
                OnlineReadOnlyShadowCandidateCommand
                        .SCHEMA_VERSION,
                verification.executionId(),
                request.requestId(),
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.candidatePlanRef(),
                request.comparisonPolicyRef(),
                baseline.artifactRef(),
                baseline.payloadVaultReceiptRef(),
                baseline.requestContextFingerprint(),
                request.accessGrant(),
                verification.admission()
                        .admissionFingerprint(),
                verification.admission()
                        .admittedAt(),
                deadline);
    }

    private OnlineReadOnlyShadowBaselineObservation
    resolveBaseline(
            Verification verification,
            OnlineReadOnlyShadowBaselineCommand command) {
        ReadOnlyShadowComparison.SourceObservation supplied =
                verification.baseline().source();
        OnlineReadOnlyShadowBaselineObservation resolved;
        try {
            resolved = baselineIntegrity.requireVerified(
                    baselineAuthority.resolve(
                            verification.request().scope(),
                            supplied.artifactRef()));
        } catch (OnlineReadOnlyShadowBaselineAuthority
                         .AuthorityException failure) {
            if (failure.failure()
                    == OnlineReadOnlyShadowBaselineAuthority
                    .Failure.UNAVAILABLE) {
                throw unavailable();
            }
            throw invalid();
        } catch (IllegalStateException unavailable) {
            throw unavailable();
        } catch (RuntimeException invalid) {
            throw invalid();
        }
        if (!resolved.artifactRef().equals(
                supplied.artifactRef())
                || !resolved.commandFingerprint().equals(
                command.commandFingerprint(mapper))
                || !resolved.scope().equals(command.scope())
                || !resolved.executionId().equals(
                command.executionId())
                || !resolved.requestId().equals(
                command.requestId())
                || !resolved.scenarioCaseRef().equals(
                command.scenarioCaseRef())
                || !resolved.targetCapabilityRef().equals(
                command.targetCapabilityRef())
                || !resolved.baselineBindingRef().equals(
                command.baselineBindingRef())
                || !resolved.comparisonPolicyRef().equals(
                command.comparisonPolicyRef())
                || !resolved.samplingGrantRef().equals(
                command.accessGrant()
                        .samplingGrantRef())
                || !resolved.egressAuthorityRef().equals(
                command.accessGrant()
                        .egressAuthorityRef())
                || !resolved.killSwitchRef().equals(
                command.accessGrant()
                        .killSwitchRef())
                || !resolved.idempotencyKeyFingerprint()
                .equals(
                        command.idempotencyKeyFingerprint(
                                mapper))
                || resolved.accessMode()
                != OnlineReadOnlyShadowBaselineObservation
                .AccessMode.READ_ONLY
                || resolved.startedAt().isBefore(
                command.admittedAt())
                || resolved.completedAt().isAfter(
                command.deadlineAt())
                || !resolved.deadlineAt().equals(
                command.deadlineAt())
                || resolved.writeCredentialExposed()
                || resolved.writeAttemptCount() != 0) {
            throw invalid();
        }
        requireBaselineProjection(
                verification.baseline(),
                resolved);
        return resolved;
    }

    private MirrorEvidenceBundle resolveCandidate(
            Verification verification,
            OnlineReadOnlyShadowCandidateCommand command) {
        ReadOnlyShadowComparison.SourceObservation supplied =
                verification.candidate().source();
        MirrorEvidenceBundle bundle;
        try {
            bundle = evidenceIntegrity.requireVerified(
                    candidateAuthority.resolve(
                            verification.request().scope(),
                            supplied.artifactRef()))
                    .bundle();
        } catch (OnlineReadOnlyShadowCandidateAuthority
                         .AuthorityException failure) {
            if (failure.failure()
                    == OnlineReadOnlyShadowCandidateAuthority
                    .Failure.UNAVAILABLE) {
                throw unavailable();
            }
            throw invalid();
        } catch (IllegalStateException unavailable) {
            throw unavailable();
        } catch (RuntimeException invalid) {
            throw invalid();
        }
        MirrorRunEvidence evidence =
                bundle.evidence();
        if (!artifactRef(bundle).equals(
                supplied.artifactRef())
                || !evidence.requestId().equals(
                command.commandFingerprint(mapper))
                || !evidence.scope().equals(command.scope())
                || !evidence.planId().equals(
                command.candidatePlanRef().id())
                || !evidence.planFingerprint().equals(
                command.candidatePlanRef()
                        .fingerprint())
                || !evidence.rootCapability().equals(
                command.targetCapabilityRef())
                || !evidence.requestContextFingerprint()
                .equals(
                        command.requestContextFingerprint())
                || evidence.startedAt().isBefore(
                verification.baseline()
                        .source().completedAt())
                || evidence.completedAt().isAfter(
                command.deadlineAt())
                || bundle.attestation().signedAt()
                .isAfter(command.deadlineAt())) {
            throw invalid();
        }
        requireCandidateProjection(
                verification.candidate(),
                bundle);
        return bundle;
    }

    private static void requireBaselineProjection(
            ReadOnlyShadowConnectorObservation supplied,
            OnlineReadOnlyShadowBaselineObservation resolved) {
        ReadOnlyShadowComparison.SourceObservation source =
                supplied.source();
        if (source.role()
                != ReadOnlyShadowComparison.SourceRole.BASELINE
                || !resolved.scope().equals(source.scope())
                || !resolved.targetCapabilityRef().equals(
                source.targetCapabilityRef())
                || !resolved.requestContextFingerprint()
                .equals(
                        source.requestContextFingerprint())
                || !resolved.semanticResultFingerprint()
                .equals(
                        source.semanticResultFingerprint())
                || !resolved.completedAt().equals(
                source.completedAt())
                || resolved.evidenceClass()
                != source.evidenceClass()
                || resolved.evidenceComplete()
                != source.evidenceComplete()
                || !resolved.comparisonPolicyRef()
                .equals(
                        supplied.comparisonPolicyRef())
                || !resolved.normalizedFactFingerprints()
                .equals(
                        supplied
                                .normalizedFactFingerprints())
                || supplied.writeCredentialExposed()
                || supplied.writeAttemptCount() != 0) {
            throw invalid();
        }
    }

    private void requireCandidateProjection(
            ReadOnlyShadowConnectorObservation supplied,
            MirrorEvidenceBundle bundle) {
        MirrorRunEvidence evidence =
                bundle.evidence();
        ReadOnlyShadowComparison.SourceObservation source =
                supplied.source();
        Map<DomainFidelityProfile.Dimension, String> facts =
                policy.normalize(evidence);
        if (source.role()
                != ReadOnlyShadowComparison.SourceRole.CANDIDATE
                || !evidence.scope().equals(source.scope())
                || !evidence.rootCapability().equals(
                source.targetCapabilityRef())
                || !evidence.requestContextFingerprint()
                .equals(
                        source.requestContextFingerprint())
                || !evidence.semanticResultFingerprint()
                .equals(
                        source.semanticResultFingerprint())
                || !evidence.completedAt().equals(
                source.completedAt())
                || evidence.evidenceClass()
                != source.evidenceClass()
                || evidenceComplete(evidence.status())
                != source.evidenceComplete()
                || !policy.reference().equals(
                supplied.comparisonPolicyRef())
                || !facts.equals(
                supplied.normalizedFactFingerprints())
                || supplied.writeCredentialExposed()
                || supplied.writeAttemptCount() != 0) {
            throw invalid();
        }
    }

    private static ReadOnlyShadowSourceResolutionAttestation
            .SourceResolution baselineResolution(
            OnlineReadOnlyShadowBaselineObservation resolved,
            Instant resolvedAt) {
        return new ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution(
                ReadOnlyShadowComparison.SourceRole.BASELINE,
                resolved.artifactRef(),
                resolved.semanticResultFingerprint(),
                resolved.normalizedFactFingerprints(),
                resolved.completedAt(),
                resolvedAt,
                resolved.evidenceClass(),
                resolved.evidenceComplete(),
                false,
                0);
    }

    private ReadOnlyShadowSourceResolutionAttestation
            .SourceResolution candidateResolution(
            MirrorEvidenceBundle bundle,
            Instant resolvedAt) {
        MirrorRunEvidence evidence =
                bundle.evidence();
        return new ReadOnlyShadowSourceResolutionAttestation
                .SourceResolution(
                ReadOnlyShadowComparison.SourceRole.CANDIDATE,
                artifactRef(bundle),
                evidence.semanticResultFingerprint(),
                policy.normalize(evidence),
                evidence.completedAt(),
                resolvedAt,
                evidence.evidenceClass(),
                evidenceComplete(evidence.status()),
                false,
                0);
    }

    private String attestationId(
            Verification verification,
            OnlineReadOnlyShadowBaselineCommand baselineCommand,
            OnlineReadOnlyShadowCandidateCommand candidateCommand,
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
                                baselineCommand
                                        .commandFingerprint(
                                                mapper),
                                candidateCommand
                                        .commandFingerprint(
                                                mapper),
                                baseline.artifactRef(),
                                baseline.resolvedAt(),
                                candidate.artifactRef(),
                                candidate.resolvedAt()),
                        MAXIMUM_IDENTITY_BYTES);
        return "source-resolution-"
                + fingerprint.substring(
                "sha256:".length());
    }

    private static MirrorArtifactRef artifactRef(
            MirrorEvidenceBundle bundle) {
        return new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                bundle.evidence().runId(),
                1,
                bundle.bundleFingerprint());
    }

    private static boolean evidenceComplete(
            MirrorRunEvidence.Status status) {
        return switch (status) {
            case EVIDENCE_INCOMPLETE,
                 CONTROL_PLAN_UNAVAILABLE -> false;
            default -> true;
        };
    }

    private static ReadOnlyShadowDataPlane.Failure
    invalid() {
        return new ReadOnlyShadowDataPlane.Failure(
                ReadOnlyShadowDataPlane.FailureReason
                        .SOURCE_VERIFICATION_FAILED);
    }

    private static ReadOnlyShadowDataPlane.Failure
    unavailable() {
        return new ReadOnlyShadowDataPlane.Failure(
                ReadOnlyShadowDataPlane.FailureReason
                        .SOURCE_RESOLUTION_UNAVAILABLE);
    }

    private record ResolutionIdentity(
            String domain,
            String executionId,
            String admissionFingerprint,
            Instant confirmedAt,
            String baselineCommandFingerprint,
            String candidateCommandFingerprint,
            MirrorArtifactRef baselineRef,
            Instant baselineResolvedAt,
            MirrorArtifactRef candidateRef,
            Instant candidateResolvedAt
    ) {
    }
}
