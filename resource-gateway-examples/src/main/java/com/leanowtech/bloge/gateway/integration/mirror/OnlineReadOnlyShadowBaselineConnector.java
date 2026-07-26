package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Governed online baseline connector over one independently signed regional sidecar.
 */
public final class OnlineReadOnlyShadowBaselineConnector
        implements ReadOnlyShadowBaselineConnector {
    private final OnlineReadOnlyShadowBaselineAuthority authority;
    private final OnlineReadOnlyShadowBaselineObservationIntegrity integrity;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * Creates the payload-isolated online baseline connector.
     *
     * @param authority strict regional sidecar protocol boundary
     * @param integrity independently governed observation verifier
     * @param mapper canonical protocol mapper
     * @param clock trusted connector clock
     */
    public OnlineReadOnlyShadowBaselineConnector(
            OnlineReadOnlyShadowBaselineAuthority authority,
            OnlineReadOnlyShadowBaselineObservationIntegrity
                    integrity,
            ObjectMapper mapper,
            Clock clock) {
        this.authority = Objects.requireNonNull(
                authority, "authority");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.mapper = Objects.requireNonNull(
                mapper, "mapper");
        this.clock = Objects.requireNonNull(
                clock, "clock");
    }

    @Override
    public boolean ready() {
        try {
            return integrity.available()
                    && authority.ready();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public ReadOnlyShadowConnectorObservation observe(
            ReadOnlyShadowConnectorInvocation invocation) {
        ReadOnlyShadowConnectorInvocation exact =
                Objects.requireNonNull(
                        invocation, "invocation");
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
                            .BASELINE_SOURCE_UNAVAILABLE);
        }
        Instant sourceDeadline = exact.deadlineAt()
                .isBefore(
                        exact.accessAdmission()
                                .validUntil())
                ? exact.deadlineAt()
                : exact.accessAdmission()
                .validUntil();
        OnlineReadOnlyShadowBaselineCommand command =
                command(exact, sourceDeadline);
        try {
            OnlineReadOnlyShadowBaselineObservation observed =
                    integrity.requireVerified(
                            authority.observe(command));
            validate(command, observed);
            return adapt(observed);
        } catch (OnlineReadOnlyShadowBaselineAuthority
                         .AuthorityException unavailable) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .BASELINE_SOURCE_UNAVAILABLE);
        } catch (ReadOnlyShadowDataPlane.Failure classified) {
            throw classified;
        } catch (RuntimeException invalid) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
    }

    private OnlineReadOnlyShadowBaselineCommand command(
            ReadOnlyShadowConnectorInvocation invocation,
            Instant sourceDeadline) {
        ReadOnlyShadowJobRequest request =
                invocation.request();
        return new OnlineReadOnlyShadowBaselineCommand(
                OnlineReadOnlyShadowBaselineCommand
                        .SCHEMA_VERSION,
                invocation.executionId(),
                request.requestId(),
                request.scope(),
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.baselineBindingRef(),
                request.comparisonPolicyRef(),
                request.accessGrant(),
                invocation.accessAdmission()
                        .admissionFingerprint(),
                invocation.accessAdmission()
                        .admittedAt(),
                sourceDeadline);
    }

    private void validate(
            OnlineReadOnlyShadowBaselineCommand command,
            OnlineReadOnlyShadowBaselineObservation observed) {
        ReadOnlyShadowJobRequest.AccessGrant grant =
                command.accessGrant();
        if (!observed.scope().equals(command.scope())
                || !observed.executionId().equals(
                command.executionId())
                || !observed.requestId().equals(
                command.requestId())
                || !observed.commandFingerprint()
                .equals(command.commandFingerprint(mapper))
                || !observed.scenarioCaseRef().equals(
                command.scenarioCaseRef())
                || !observed.targetCapabilityRef().equals(
                command.targetCapabilityRef())
                || !observed.baselineBindingRef().equals(
                command.baselineBindingRef())
                || !observed.comparisonPolicyRef().equals(
                command.comparisonPolicyRef())
                || !observed.samplingGrantRef().equals(
                grant.samplingGrantRef())
                || !observed.egressAuthorityRef().equals(
                grant.egressAuthorityRef())
                || !observed.killSwitchRef().equals(
                grant.killSwitchRef())
                || !observed.idempotencyKeyFingerprint()
                .equals(
                        command.idempotencyKeyFingerprint(
                                mapper))
                || observed.accessMode()
                != OnlineReadOnlyShadowBaselineObservation
                .AccessMode.READ_ONLY
                || observed.startedAt().isBefore(
                command.admittedAt())
                || observed.completedAt().isAfter(
                command.deadlineAt())
                || !observed.deadlineAt().equals(
                command.deadlineAt())
                || !observed.workloadIdentityExpiresAt()
                .isAfter(observed.completedAt())
                || observed.issuedAt().isAfter(
                clock.instant().plusSeconds(60))) {
            throw failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .SOURCE_VERIFICATION_FAILED);
        }
    }

    private static ReadOnlyShadowConnectorObservation adapt(
            OnlineReadOnlyShadowBaselineObservation observed) {
        return new ReadOnlyShadowConnectorObservation(
                new ReadOnlyShadowComparison.SourceObservation(
                        ReadOnlyShadowComparison.SourceRole
                                .BASELINE,
                        observed.artifactRef(),
                        observed.scope(),
                        observed.targetCapabilityRef(),
                        observed.requestContextFingerprint(),
                        observed.semanticResultFingerprint(),
                        observed.completedAt(),
                        observed.evidenceClass(),
                        observed.evidenceComplete()),
                observed.comparisonPolicyRef(),
                observed.normalizedFactFingerprints(),
                observed.writeCredentialExposed(),
                observed.writeAttemptCount());
    }

    private static ReadOnlyShadowDataPlane.Failure failure(
            ReadOnlyShadowDataPlane.FailureReason reason) {
        return new ReadOnlyShadowDataPlane.Failure(reason);
    }
}
