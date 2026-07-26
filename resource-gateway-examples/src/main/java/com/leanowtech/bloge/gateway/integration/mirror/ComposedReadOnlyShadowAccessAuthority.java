package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Joins online sampling, kill-switch, and deployment-isolation authorities.
 *
 * <p>Every decision is observed twice around connector work. Exact signed decision references and
 * policy limits must remain stable; routine observation timestamps may advance. No positive
 * result is cached beyond the common validity window.</p>
 */
public final class ComposedReadOnlyShadowAccessAuthority
        implements ReadOnlyShadowAccessAuthority {
    private static final String FINGERPRINT_DOMAIN =
            "resourceGateway.readOnlyShadowAccessAdmission.v1";

    private final ReadOnlyShadowSamplingGrantAuthority sampling;
    private final ReadOnlyShadowKillSwitchAuthority killSwitch;
    private final MirrorDeploymentIsolationRunTrustAuthority egress;
    private final Clock clock;

    /**
     * Creates one double-observed authority join.
     *
     * @param sampling online Data Governance sampling authority
     * @param killSwitch online operational kill-switch authority
     * @param egress deployment isolation and egress trust authority
     * @param clock trusted data-plane clock
     */
    public ComposedReadOnlyShadowAccessAuthority(
            ReadOnlyShadowSamplingGrantAuthority sampling,
            ReadOnlyShadowKillSwitchAuthority killSwitch,
            MirrorDeploymentIsolationRunTrustAuthority egress,
            Clock clock) {
        this.sampling = Objects.requireNonNull(
                sampling, "sampling");
        this.killSwitch = Objects.requireNonNull(
                killSwitch, "killSwitch");
        this.egress = Objects.requireNonNull(
                egress, "egress");
        this.clock = Objects.requireNonNull(
                clock, "clock");
    }

    @Override
    public boolean ready() {
        return available(sampling::available)
                && available(killSwitch::available)
                && available(egress::available);
    }

    @Override
    public Admission admit(
            ReadOnlyShadowDataPlane.Permit permit) {
        ReadOnlyShadowDataPlane.Permit exact =
                Objects.requireNonNull(permit, "permit");
        if (!ready()) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        Instant now = clock.instant();
        if (!exact.deadlineAt().isAfter(now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .DEADLINE_EXCEEDED);
        }
        ReadOnlyShadowJobRequest request =
                exact.request();
        ReadOnlyShadowSamplingGrantAuthority.Grant grant =
                resolveGrant(
                        request.scope(),
                        request.accessGrant(),
                        now);
        ReadOnlyShadowKillSwitchAuthority.State state =
                resolveKillSwitch(
                        request.scope(),
                        request.accessGrant(),
                        now);
        MirrorDeploymentIsolationRunTrust.Admission
                egressAdmission;
        try {
            egressAdmission =
                    egress.admit(request.scope());
        } catch (MirrorDeploymentIsolationRunTrustAuthority
                         .TrustException denied) {
            throw failure(
                    available(egress::available)
                            ? ReadOnlyShadowDataPlane.FailureReason
                            .EGRESS_DENIED
                            : ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        if (!request.accessGrant()
                .egressAuthorityRef()
                .equals(egressAdmission.attestationRef())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .EGRESS_DENIED);
        }
        Instant validUntil = minimum(
                exact.deadlineAt(),
                grant.expiresAt(),
                state.expiresAt(),
                egressAdmission.validUntil());
        ReadOnlyShadowComparison.AccessProof proof =
                request.accessGrant().zeroWriteProof();
        String fingerprint = fingerprint(
                request.scope(),
                proof,
                grant,
                state,
                egressAdmission,
                validUntil);
        return new Admission(
                fingerprint,
                proof,
                grant.limits(),
                grant,
                state,
                egressAdmission,
                now,
                validUntil);
    }

    @Override
    public Confirmation confirm(
            Admission admission,
            Instant startedAt,
            Instant completedAt) {
        Admission exact =
                Objects.requireNonNull(
                        admission, "admission");
        Instant started =
                Objects.requireNonNull(
                        startedAt, "startedAt");
        Instant completed =
                Objects.requireNonNull(
                        completedAt, "completedAt");
        Instant now = clock.instant();
        if (started.isBefore(exact.admittedAt())
                || completed.isBefore(started)
                || completed.isAfter(now)
                || !exact.validUntil().isAfter(completed)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .DEADLINE_EXCEEDED);
        }
        ReadOnlyShadowJobRequest.AccessGrant coordinates =
                new ReadOnlyShadowJobRequest.AccessGrant(
                        exact.accessProof().accessMode(),
                        exact.accessProof().samplingGrantRef(),
                        exact.accessProof().egressAuthorityRef(),
                        exact.accessProof().killSwitchRef(),
                        exact.accessProof().sampleOrdinal(),
                        exact.accessProof().maximumSamples());
        ReadOnlyShadowSamplingGrantAuthority.Grant grant =
                resolveGrant(
                        exact.scope(),
                        coordinates,
                        now);
        if (!sameGrantDecision(
                exact.samplingGrant(), grant)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .GRANT_REVOKED);
        }
        ReadOnlyShadowKillSwitchAuthority.State state =
                resolveKillSwitch(
                        exact.scope(),
                        coordinates,
                        now);
        if (!sameKillSwitchDecision(
                exact.killSwitch(), state)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .KILL_SWITCH_OPEN);
        }
        MirrorDeploymentIsolationRunTrust.Binding binding;
        try {
            binding = egress.confirm(
                    exact.egressAdmission(),
                    started,
                    completed);
        } catch (MirrorDeploymentIsolationRunTrustAuthority
                         .TrustException denied) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .EGRESS_DENIED);
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        if (!binding.attestationRef().equals(
                exact.accessProof()
                        .egressAuthorityRef())
                || !binding.decisionRef().equals(
                exact.egressAdmission()
                        .decisionRef())
                || binding.confirmedAt().isBefore(completed)
                || binding.confirmedAt().isAfter(now)
                || !exact.validUntil().isAfter(
                binding.confirmedAt())) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .EGRESS_DENIED);
        }
        return new Confirmation(
                exact.admissionFingerprint(),
                grant,
                state,
                binding,
                binding.confirmedAt());
    }

    private ReadOnlyShadowSamplingGrantAuthority.Grant
    resolveGrant(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowJobRequest.AccessGrant coordinates,
            Instant now) {
        ReadOnlyShadowSamplingGrantAuthority.Grant grant;
        try {
            grant = sampling.resolve(
                    scope,
                    coordinates.samplingGrantRef());
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        if (!scope.equals(grant.scope())
                || !coordinates.samplingGrantRef()
                .equals(grant.grantRef())
                || coordinates.maximumSamples()
                != grant.maximumSamples()
                || coordinates.sampleOrdinal()
                > grant.maximumSamples()
                || now.isBefore(grant.validFrom())
                || !grant.expiresAt().isAfter(now)
                || grant.observedAt().isAfter(now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .GRANT_REVOKED);
        }
        return grant;
    }

    private ReadOnlyShadowKillSwitchAuthority.State
    resolveKillSwitch(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowJobRequest.AccessGrant coordinates,
            Instant now) {
        ReadOnlyShadowKillSwitchAuthority.State state;
        try {
            state = killSwitch.resolve(
                    scope,
                    coordinates.killSwitchRef());
        } catch (ReadOnlyShadowDataPlane.Failure known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
        if (!scope.equals(state.scope())
                || !coordinates.killSwitchRef()
                .equals(state.killSwitchRef())
                || !state.enabled()
                || now.isBefore(state.effectiveAt())
                || !state.expiresAt().isAfter(now)
                || state.observedAt().isAfter(now)) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .KILL_SWITCH_OPEN);
        }
        return state;
    }

    private static boolean sameGrantDecision(
            ReadOnlyShadowSamplingGrantAuthority.Grant left,
            ReadOnlyShadowSamplingGrantAuthority.Grant right) {
        return left.scope().equals(right.scope())
                && left.grantRef().equals(right.grantRef())
                && left.maximumSamples()
                == right.maximumSamples()
                && left.validFrom().equals(right.validFrom())
                && left.expiresAt().equals(right.expiresAt())
                && left.limits().equals(right.limits())
                && left.authorityAttestationRef().equals(
                right.authorityAttestationRef());
    }

    private static boolean sameKillSwitchDecision(
            ReadOnlyShadowKillSwitchAuthority.State left,
            ReadOnlyShadowKillSwitchAuthority.State right) {
        return left.scope().equals(right.scope())
                && left.killSwitchRef().equals(
                right.killSwitchRef())
                && right.enabled()
                && left.effectiveAt().equals(
                right.effectiveAt())
                && left.expiresAt().equals(
                right.expiresAt())
                && left.authorityAttestationRef().equals(
                right.authorityAttestationRef());
    }

    private static String fingerprint(
            CapabilitySnapshot.Scope scope,
            ReadOnlyShadowComparison.AccessProof proof,
            ReadOnlyShadowSamplingGrantAuthority.Grant grant,
            ReadOnlyShadowKillSwitchAuthority.State state,
            MirrorDeploymentIsolationRunTrust.Admission egress,
            Instant validUntil) {
        return ProtocolFingerprint.ofText(
                FINGERPRINT_DOMAIN + "\n"
                        + scope.tenantId() + "\n"
                        + scope.organizationId() + "\n"
                        + scope.projectId() + "\n"
                        + scope.environmentId() + "\n"
                        + scope.region() + "\n"
                        + proof.accessMode().name() + "\n"
                        + proof.samplingGrantRef().fingerprint() + "\n"
                        + proof.sampleOrdinal() + "\n"
                        + proof.maximumSamples() + "\n"
                        + grant.authorityAttestationRef()
                        .fingerprint() + "\n"
                        + state.killSwitchRef().fingerprint() + "\n"
                        + state.authorityAttestationRef()
                        .fingerprint() + "\n"
                        + egress.decisionRef().fingerprint() + "\n"
                        + egress.attestationRef().fingerprint() + "\n"
                        + grant.limits() + "\n"
                        + validUntil);
    }

    private static Instant minimum(
            Instant first,
            Instant... rest) {
        Instant minimum = Objects.requireNonNull(
                first, "first");
        for (Instant candidate : rest) {
            Instant exact = Objects.requireNonNull(
                    candidate, "candidate");
            if (exact.isBefore(minimum)) {
                minimum = exact;
            }
        }
        return minimum;
    }

    private static boolean available(
            AvailabilityProbe probe) {
        try {
            return probe.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static ReadOnlyShadowDataPlane.Failure failure(
            ReadOnlyShadowDataPlane.FailureReason reason) {
        return new ReadOnlyShadowDataPlane.Failure(
                reason);
    }

    @FunctionalInterface
    private interface AvailabilityProbe {
        boolean available();
    }
}
