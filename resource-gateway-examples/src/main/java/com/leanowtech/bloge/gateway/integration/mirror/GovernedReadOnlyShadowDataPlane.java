package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Governed payload-free orchestration of one paired read-only Shadow observation.
 *
 * <p>This class is the only component allowed to join durable job control with online authority,
 * shared pressure control, baseline/candidate connectors, source verification, and normalized
 * typed diff. Every authority or connector call is preceded by a durable lease heartbeat; the
 * shared guard is renewed to no later than that replacement lease. Any write capability,
 * authority drift, source mismatch, or incomplete dependency fails closed before comparison
 * publication.</p>
 */
public final class GovernedReadOnlyShadowDataPlane
        implements ReadOnlyShadowDataPlane {
    private final ReadOnlyShadowAccessAuthority authority;
    private final ReadOnlyShadowExecutionGuard guard;
    private final ReadOnlyShadowBaselineConnector baseline;
    private final ReadOnlyShadowCandidateConnector candidate;
    private final ReadOnlyShadowSourceResolutionVerifier
            sourceVerifier;
    private final ReadOnlyShadowComparisonEngine comparisonEngine;
    private final Clock clock;

    /**
     * Creates one governed data-plane composition.
     *
     * @param authority double-observed grant, switch, and egress authority
     * @param guard shared rate, concurrency, and circuit authority
     * @param baseline payload-isolated baseline connector
     * @param candidate sealed candidate runtime connector
     * @param sourceVerifier independent source artifact resolver and verifier
     * @param comparisonEngine exact normalized-fact comparison policy
     * @param clock trusted data-plane clock
     */
    public GovernedReadOnlyShadowDataPlane(
            ReadOnlyShadowAccessAuthority authority,
            ReadOnlyShadowExecutionGuard guard,
            ReadOnlyShadowBaselineConnector baseline,
            ReadOnlyShadowCandidateConnector candidate,
            ReadOnlyShadowSourceResolutionVerifier sourceVerifier,
            ReadOnlyShadowComparisonEngine comparisonEngine,
            Clock clock) {
        this.authority = Objects.requireNonNull(
                authority, "authority");
        this.guard = Objects.requireNonNull(
                guard, "guard");
        this.baseline = Objects.requireNonNull(
                baseline, "baseline");
        this.candidate = Objects.requireNonNull(
                candidate, "candidate");
        this.sourceVerifier = Objects.requireNonNull(
                sourceVerifier, "sourceVerifier");
        this.comparisonEngine = Objects.requireNonNull(
                comparisonEngine, "comparisonEngine");
        this.clock = Objects.requireNonNull(
                clock, "clock");
    }

    @Override
    public boolean ready() {
        return safe(authority::ready)
                && safe(guard::ready)
                && safe(baseline::ready)
                && safe(candidate::ready)
                && safe(sourceVerifier::ready)
                && safe(comparisonEngine::ready);
    }

    @Override
    public ExecutionResult execute(
            ReadOnlyShadowDataPlane.Permit permit) {
        ReadOnlyShadowDataPlane.Permit exact =
                Objects.requireNonNull(permit, "permit");
        requireReady();
        heartbeat(exact, null);
        ReadOnlyShadowAccessAuthority.Admission admission =
                admit(exact);
        ReadOnlyShadowExecutionGuard.Lease guardLease =
                guard.acquire(exact, admission);
        ReadOnlyShadowDataPlane.Failure terminalFailure =
                null;
        try {
            Instant startedAt = admission.admittedAt();
            ReadOnlyShadowConnectorInvocation invocation =
                    new ReadOnlyShadowConnectorInvocation(
                            exact.executionId(),
                            exact.request(),
                            admission,
                            startedAt,
                            exact.deadlineAt());
            heartbeat(exact, guardLease);
            ReadOnlyShadowConnectorObservation baselineResult =
                    observeBaseline(invocation);
            requireZeroWrite(baselineResult);

            heartbeat(exact, guardLease);
            ReadOnlyShadowConnectorObservation candidateResult =
                    observeCandidate(invocation);
            requireZeroWrite(candidateResult);
            requirePair(
                    exact.request(),
                    baselineResult,
                    candidateResult);

            Instant completedAt = latest(
                    baselineResult.source().completedAt(),
                    candidateResult.source().completedAt());
            heartbeat(exact, guardLease);
            ReadOnlyShadowAccessAuthority.Confirmation confirmation =
                    confirm(
                            admission,
                            startedAt,
                            completedAt);

            heartbeat(exact, guardLease);
            MirrorArtifactRef sourceAttestation =
                    verifySources(
                            new ReadOnlyShadowSourceResolutionVerifier
                                    .Verification(
                                    exact.request(),
                                    admission,
                                    confirmation,
                                    baselineResult,
                                    candidateResult));
            List<ReadOnlyShadowComparison.DimensionComparison>
                    results = compare(
                    exact.request().comparisonPolicyRef(),
                    baselineResult,
                    candidateResult);
            Instant observedAt = latest(
                    clock.instant(),
                    completedAt,
                    confirmation.confirmedAt());
            ExecutionResult result =
                    result(
                            admission,
                            sourceAttestation,
                            baselineResult,
                            candidateResult,
                            observedAt,
                            results);
            guardLease.succeeded();
            return result;
        } catch (ReadOnlyShadowDataPlane.Failure failure) {
            terminalFailure = failure;
            recordFailure(guardLease, failure.reason());
            throw failure;
        } catch (RuntimeException invalid) {
            terminalFailure = failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
            recordFailure(
                    guardLease,
                    terminalFailure.reason());
            throw terminalFailure;
        } finally {
            close(guardLease, terminalFailure);
        }
    }

    private void requireReady() {
        if (!safe(authority::ready)
                || !safe(guard::ready)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        if (!safe(baseline::ready)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .BASELINE_SOURCE_UNAVAILABLE);
        }
        if (!safe(candidate::ready)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .CANDIDATE_RUNTIME_UNAVAILABLE);
        }
        if (!safe(sourceVerifier::ready)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
        if (!safe(comparisonEngine::ready)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .NORMALIZATION_POLICY_UNAVAILABLE);
        }
    }

    private ReadOnlyShadowAccessAuthority.Admission admit(
            ReadOnlyShadowDataPlane.Permit permit) {
        try {
            return authority.admit(permit);
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }

    private ReadOnlyShadowAccessAuthority.Confirmation confirm(
            ReadOnlyShadowAccessAuthority.Admission admission,
            Instant startedAt,
            Instant completedAt) {
        try {
            return authority.confirm(
                    admission, startedAt, completedAt);
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }

    private ReadOnlyShadowConnectorObservation observeBaseline(
            ReadOnlyShadowConnectorInvocation invocation) {
        try {
            return Objects.requireNonNull(
                    baseline.observe(invocation),
                    "baseline observation");
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .BASELINE_SOURCE_UNAVAILABLE);
        }
    }

    private ReadOnlyShadowConnectorObservation observeCandidate(
            ReadOnlyShadowConnectorInvocation invocation) {
        try {
            return Objects.requireNonNull(
                    candidate.observe(invocation),
                    "candidate observation");
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .CANDIDATE_RUNTIME_UNAVAILABLE);
        }
    }

    private MirrorArtifactRef verifySources(
            ReadOnlyShadowSourceResolutionVerifier.Verification
                    verification) {
        try {
            MirrorArtifactRef reference =
                    Objects.requireNonNull(
                            sourceVerifier.verify(
                                    verification),
                            "source resolution attestation");
            if (!"SHADOW_SOURCE_RESOLUTION_ATTESTATION"
                    .equals(reference.kind())) {
                throw new IllegalArgumentException(
                        "source resolution attestation kind is invalid");
            }
            return reference;
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
    }

    private List<ReadOnlyShadowComparison.DimensionComparison>
    compare(
            MirrorArtifactRef policy,
            ReadOnlyShadowConnectorObservation baselineResult,
            ReadOnlyShadowConnectorObservation candidateResult) {
        try {
            List<ReadOnlyShadowComparison.DimensionComparison>
                    results = comparisonEngine.compare(
                    policy,
                    baselineResult,
                    candidateResult);
            return results == null
                    ? List.of() : List.copyOf(results);
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .NORMALIZATION_FAILED);
        }
    }

    private static ExecutionResult result(
            ReadOnlyShadowAccessAuthority.Admission admission,
            MirrorArtifactRef sourceAttestation,
            ReadOnlyShadowConnectorObservation baselineResult,
            ReadOnlyShadowConnectorObservation candidateResult,
            Instant observedAt,
            List<ReadOnlyShadowComparison.DimensionComparison>
                    results) {
        try {
            return new ExecutionResult(
                    admission.accessProof(),
                    sourceAttestation,
                    baselineResult.source(),
                    candidateResult.source(),
                    observedAt,
                    results);
        } catch (RuntimeException invalid) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .NORMALIZATION_FAILED);
        }
    }

    private void heartbeat(
            ReadOnlyShadowDataPlane.Permit permit,
            ReadOnlyShadowExecutionGuard.Lease guardLease) {
        if (!permit.deadlineAt().isAfter(
                clock.instant())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .DEADLINE_EXCEEDED);
        }
        Instant leaseExpiresAt;
        try {
            leaseExpiresAt =
                    permit.control().heartbeat();
        } catch (RuntimeException lost) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .LEASE_LOST);
        }
        if (leaseExpiresAt == null
                || !leaseExpiresAt.isAfter(
                clock.instant())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .LEASE_LOST);
        }
        if (guardLease != null) {
            try {
                guardLease.renew(
                        leaseExpiresAt);
            } catch (ReadOnlyShadowDataPlane.Failure known) {
                throw known;
            } catch (RuntimeException unavailable) {
                throw failure(
                        ReadOnlyShadowDataPlane.FailureReason
                                .ADMISSION_AUTHORITY_UNAVAILABLE);
            }
        }
    }

    private static void requireZeroWrite(
            ReadOnlyShadowConnectorObservation observation) {
        if (observation.writeCredentialExposed()) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .WRITE_CAPABILITY_DETECTED);
        }
        if (observation.writeAttemptCount() != 0) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .WRITE_ATTEMPT_DETECTED);
        }
    }

    private static void requirePair(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowConnectorObservation baselineResult,
            ReadOnlyShadowConnectorObservation candidateResult) {
        ReadOnlyShadowComparison.SourceObservation left =
                baselineResult.source();
        ReadOnlyShadowComparison.SourceObservation right =
                candidateResult.source();
        if (left.role()
                != ReadOnlyShadowComparison.SourceRole.BASELINE
                || right.role()
                != ReadOnlyShadowComparison.SourceRole.CANDIDATE
                || !"SHADOW_BASELINE_OBSERVATION".equals(
                left.artifactRef().kind())
                || !"MIRROR_EVIDENCE_BUNDLE".equals(
                right.artifactRef().kind())
                || !request.scope().equals(left.scope())
                || !request.scope().equals(right.scope())
                || !request.targetCapabilityRef().equals(
                left.targetCapabilityRef())
                || !request.targetCapabilityRef().equals(
                right.targetCapabilityRef())
                || !request.comparisonPolicyRef().equals(
                baselineResult.comparisonPolicyRef())
                || !request.comparisonPolicyRef().equals(
                candidateResult.comparisonPolicyRef())
                || !left.requestContextFingerprint().equals(
                right.requestContextFingerprint())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
    }

    private static void recordFailure(
            ReadOnlyShadowExecutionGuard.Lease lease,
            ReadOnlyShadowDataPlane.FailureReason reason) {
        try {
            lease.failed(reason);
        } catch (RuntimeException ignored) {
            // The original classified failure remains authoritative.
        }
    }

    private static void close(
            ReadOnlyShadowExecutionGuard.Lease lease,
            ReadOnlyShadowDataPlane.Failure failure) {
        try {
            lease.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                throw GovernedReadOnlyShadowDataPlane.failure(
                        ReadOnlyShadowDataPlane.FailureReason
                                .ADMISSION_AUTHORITY_UNAVAILABLE);
            }
        }
    }

    private static Instant latest(
            Instant first,
            Instant... rest) {
        Instant latest =
                Objects.requireNonNull(first, "first");
        for (Instant candidate : rest) {
            Instant exact =
                    Objects.requireNonNull(
                            candidate, "candidate");
            if (exact.isAfter(latest)) {
                latest = exact;
            }
        }
        return latest;
    }

    private static boolean safe(
            BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static ReadOnlyShadowDataPlane.Failure failure(
            ReadOnlyShadowDataPlane.FailureReason reason) {
        return new ReadOnlyShadowDataPlane.Failure(
                reason);
    }
}
