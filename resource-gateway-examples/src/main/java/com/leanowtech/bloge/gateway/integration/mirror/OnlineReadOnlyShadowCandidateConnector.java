package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Online sealed-candidate connector bound to one independently verified baseline observation.
 *
 * <p>The connector re-resolves the baseline artifact before extracting its regional vault receipt,
 * submits a payload-free candidate command, independently verifies the returned Mirror evidence,
 * and closes command, plan, scope, capability, context, and execution-window coordinates. It
 * reports zero external writes only after the Mirror evidence isolation model has rejected
 * production credentials, production context carriers, real external calls, and network
 * egress.</p>
 */
public final class OnlineReadOnlyShadowCandidateConnector
        implements ReadOnlyShadowCandidateConnector {
    private final OnlineReadOnlyShadowBaselineAuthority
            baselineAuthority;
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            baselineIntegrity;
    private final OnlineReadOnlyShadowCandidateAuthority
            candidateAuthority;
    private final MirrorEvidenceIntegrityService
            evidenceIntegrity;
    private final PayloadFreeEqualityReadOnlyShadowPolicy
            policy;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * Creates one online candidate connector.
     *
     * @param baselineAuthority exact online baseline artifact resolver
     * @param baselineIntegrity independently governed baseline evidence verifier
     * @param candidateAuthority isolated candidate execution and evidence authority
     * @param evidenceIntegrity independently governed Mirror evidence verifier
     * @param policy exact payload-free normalization policy
     * @param mapper canonical protocol mapper
     * @param clock trusted connector clock
     */
    public OnlineReadOnlyShadowCandidateConnector(
            OnlineReadOnlyShadowBaselineAuthority
                    baselineAuthority,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    baselineIntegrity,
            OnlineReadOnlyShadowCandidateAuthority
                    candidateAuthority,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            PayloadFreeEqualityReadOnlyShadowPolicy policy,
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
                    && policy.ready();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Rejects unpaired online execution.
     *
     * @param invocation exact governed invocation coordinates
     * @return never returns
     * @throws ReadOnlyShadowDataPlane.Failure always, because online execution needs a baseline
     */
    @Override
    public ReadOnlyShadowConnectorObservation observe(
            ReadOnlyShadowConnectorInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        throw failure(
                ReadOnlyShadowDataPlane.FailureReason
                        .SOURCE_VERIFICATION_FAILED);
    }

    @Override
    public ReadOnlyShadowConnectorObservation observePaired(
            ReadOnlyShadowConnectorInvocation invocation,
            ReadOnlyShadowConnectorObservation baseline) {
        ReadOnlyShadowConnectorInvocation exact =
                Objects.requireNonNull(
                        invocation, "invocation");
        ReadOnlyShadowConnectorObservation baselineResult =
                Objects.requireNonNull(
                        baseline, "baseline");
        ReadOnlyShadowJobRequest request =
                exact.request();
        if (request.effectiveSourceMode()
                != ReadOnlyShadowJobRequest.SourceMode
                .ONLINE_EXECUTION
                || !ReadOnlyShadowJobRequest.SCHEMA_VERSION
                .equals(request.schemaVersion())
                || !clock.instant().isBefore(
                exact.deadlineAt())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .CANDIDATE_RUNTIME_UNAVAILABLE);
        }
        try {
            policy.requirePolicy(
                    request.comparisonPolicyRef());
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .NORMALIZATION_POLICY_UNAVAILABLE);
        }

        OnlineReadOnlyShadowBaselineObservation
                baselineObservation =
                resolveBaseline(
                        request,
                        baselineResult);
        Instant sourceDeadline = exact.deadlineAt()
                .isBefore(
                        exact.accessAdmission()
                                .validUntil())
                ? exact.deadlineAt()
                : exact.accessAdmission()
                .validUntil();
        OnlineReadOnlyShadowCandidateCommand command =
                command(
                        exact,
                        baselineObservation,
                        sourceDeadline);
        try {
            MirrorEvidenceBundle bundle =
                    evidenceIntegrity.requireVerified(
                            candidateAuthority.execute(
                                    command))
                            .bundle();
            validate(
                    command,
                    baselineObservation,
                    bundle);
            return adapt(bundle);
        } catch (OnlineReadOnlyShadowCandidateAuthority
                         .AuthorityException unavailable) {
            throw failure(
                    unavailable.failure()
                            == OnlineReadOnlyShadowCandidateAuthority
                            .Failure.UNAVAILABLE
                            ? ReadOnlyShadowDataPlane.FailureReason
                            .CANDIDATE_RUNTIME_UNAVAILABLE
                            : ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        } catch (ReadOnlyShadowDataPlane.Failure classified) {
            throw classified;
        } catch (IllegalStateException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .CANDIDATE_RUNTIME_UNAVAILABLE);
        } catch (RuntimeException invalid) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
    }

    private OnlineReadOnlyShadowBaselineObservation
    resolveBaseline(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowConnectorObservation supplied) {
        ReadOnlyShadowComparison.SourceObservation source =
                supplied.source();
        if (source.role()
                != ReadOnlyShadowComparison.SourceRole.BASELINE
                || !OnlineReadOnlyShadowBaselineObservation
                .ARTIFACT_KIND.equals(
                        source.artifactRef().kind())
                || !request.scope().equals(source.scope())
                || !request.targetCapabilityRef().equals(
                        source.targetCapabilityRef())
                || !request.comparisonPolicyRef().equals(
                        supplied.comparisonPolicyRef())
                || supplied.writeCredentialExposed()
                || supplied.writeAttemptCount() != 0) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
        try {
            OnlineReadOnlyShadowBaselineObservation resolved =
                    baselineIntegrity.requireVerified(
                            baselineAuthority.resolve(
                                    request.scope(),
                                    source.artifactRef()));
            if (!source.artifactRef().equals(
                    resolved.artifactRef())
                    || !source.requestContextFingerprint()
                    .equals(
                            resolved.requestContextFingerprint())
                    || !source.semanticResultFingerprint()
                    .equals(
                            resolved.semanticResultFingerprint())
                    || !source.completedAt().equals(
                            resolved.completedAt())
                    || source.evidenceClass()
                    != resolved.evidenceClass()
                    || source.evidenceComplete()
                    != resolved.evidenceComplete()
                    || !supplied.normalizedFactFingerprints()
                    .equals(
                            resolved.normalizedFactFingerprints())
                    || resolved.writeCredentialExposed()
                    || resolved.writeAttemptCount() != 0) {
                throw failure(
                        ReadOnlyShadowDataPlane.FailureReason
                                .SOURCE_VERIFICATION_FAILED);
            }
            return resolved;
        } catch (OnlineReadOnlyShadowBaselineAuthority
                         .AuthorityException unavailable) {
            throw failure(
                    unavailable.failure()
                            == OnlineReadOnlyShadowBaselineAuthority
                            .Failure.UNAVAILABLE
                            ? ReadOnlyShadowDataPlane.FailureReason
                            .BASELINE_SOURCE_UNAVAILABLE
                            : ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        } catch (ReadOnlyShadowDataPlane.Failure classified) {
            throw classified;
        } catch (IllegalStateException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .BASELINE_SOURCE_UNAVAILABLE);
        } catch (RuntimeException invalid) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
    }

    private OnlineReadOnlyShadowCandidateCommand command(
            ReadOnlyShadowConnectorInvocation invocation,
            OnlineReadOnlyShadowBaselineObservation baseline,
            Instant sourceDeadline) {
        ReadOnlyShadowJobRequest request =
                invocation.request();
        return new OnlineReadOnlyShadowCandidateCommand(
                OnlineReadOnlyShadowCandidateCommand
                        .SCHEMA_VERSION,
                invocation.executionId(),
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
                invocation.accessAdmission()
                        .admissionFingerprint(),
                invocation.accessAdmission()
                        .admittedAt(),
                sourceDeadline);
    }

    private void validate(
            OnlineReadOnlyShadowCandidateCommand command,
            OnlineReadOnlyShadowBaselineObservation baseline,
            MirrorEvidenceBundle bundle) {
        MirrorRunEvidence evidence = bundle.evidence();
        MirrorArtifactRef exactRef =
                artifactRef(bundle);
        if (!evidence.requestId().equals(
                command.commandFingerprint(mapper))
                || !evidence.scope().equals(command.scope())
                || !evidence.planId().equals(
                command.candidatePlanRef().id())
                || !evidence.planFingerprint().equals(
                command.candidatePlanRef().fingerprint())
                || !evidence.rootCapability().equals(
                command.targetCapabilityRef())
                || !evidence.requestContextFingerprint()
                .equals(
                        command.requestContextFingerprint())
                || evidence.startedAt().isBefore(
                baseline.completedAt())
                || evidence.completedAt().isAfter(
                command.deadlineAt())
                || bundle.attestation().signedAt()
                .isAfter(command.deadlineAt())
                || bundle.attestation().signedAt()
                .isAfter(
                        clock.instant().plusSeconds(60))
                || !"MIRROR_EVIDENCE_BUNDLE".equals(
                exactRef.kind())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
    }

    private ReadOnlyShadowConnectorObservation adapt(
            MirrorEvidenceBundle bundle) {
        MirrorRunEvidence evidence = bundle.evidence();
        return new ReadOnlyShadowConnectorObservation(
                new ReadOnlyShadowComparison.SourceObservation(
                        ReadOnlyShadowComparison.SourceRole
                                .CANDIDATE,
                        artifactRef(bundle),
                        evidence.scope(),
                        evidence.rootCapability(),
                        evidence.requestContextFingerprint(),
                        evidence.semanticResultFingerprint(),
                        evidence.completedAt(),
                        evidence.evidenceClass(),
                        evidenceComplete(evidence.status())),
                policy.reference(),
                policy.normalize(evidence),
                false,
                0);
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

    private static ReadOnlyShadowDataPlane.Failure failure(
            ReadOnlyShadowDataPlane.FailureReason reason) {
        return new ReadOnlyShadowDataPlane.Failure(reason);
    }
}
