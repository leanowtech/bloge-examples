package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable payload-free proof of one accepted continuous-assessment remediation.
 *
 * <p>The receipt binds the authenticated actor, exact caller command, reviewed quarantined
 * projection, and the appended lifecycle event. An independent consumer can therefore prove that
 * remediation reset only the retry streak and scheduling state without rewriting assessment
 * evidence, cumulative attempts, lease generation, or lifecycle history.</p>
 */
public record AuthoritativeOutcomeContinuousAssessmentRemediationReceipt(
        String schemaVersion,
        String receiptFingerprint,
        String commandFingerprint,
        CapabilitySnapshot.Scope scope,
        String projectionId,
        long remediationGeneration,
        AuthoritativeOutcomeContinuousAssessmentRemediationRequest command,
        AuthoritativeOutcomeContinuousAssessmentProjection previousProjection,
        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent lifecycleEvent
) {
    /** Current continuous-assessment remediation receipt version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentRemediationReceipt.v1";
    /** Canonical actor-bound command identity version. */
    public static final String COMMAND_BINDING_SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentRemediationCommandBinding.v1";
    /** Largest canonical receipt. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            1024 * 1024;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces immutable lineage and the only permitted quarantine recovery transition. */
    public AuthoritativeOutcomeContinuousAssessmentRemediationReceipt {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported continuous assessment remediation receipt schemaVersion");
        }
        receiptFingerprint = optionalFingerprint(
                receiptFingerprint, "receiptFingerprint");
        commandFingerprint = optionalFingerprint(
                commandFingerprint, "commandFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        projectionId = required(
                projectionId, "projectionId");
        if (remediationGeneration < 1) {
            throw new IllegalArgumentException(
                    "continuous assessment remediation generation is invalid");
        }
        command = Objects.requireNonNull(
                command, "command");
        previousProjection = Objects.requireNonNull(
                previousProjection, "previousProjection");
        lifecycleEvent = Objects.requireNonNull(
                lifecycleEvent, "lifecycleEvent");
        requireTransition(
                scope,
                projectionId,
                command,
                previousProjection,
                lifecycleEvent);
    }

    /** Seals the actor-bound command and complete immutable receipt. */
    public AuthoritativeOutcomeContinuousAssessmentRemediationReceipt seal(
            ObjectMapper mapper) {
        ObjectMapper exactMapper =
                Objects.requireNonNull(mapper, "mapper");
        String boundCommand =
                commandFingerprint(
                        exactMapper,
                        scope,
                        projectionId,
                        lifecycleEvent.actorFingerprint(),
                        command);
        AuthoritativeOutcomeContinuousAssessmentRemediationReceipt
                material = withFingerprints(
                "", boundCommand);
        return material.withFingerprints(
                ProtocolFingerprint.ofBounded(
                        exactMapper,
                        material,
                        MAXIMUM_CANONICAL_BYTES),
                boundCommand);
    }

    /** Verifies nested projections, lifecycle content address, command binding, and receipt seal. */
    public void verify(ObjectMapper mapper) {
        ObjectMapper exactMapper =
                Objects.requireNonNull(mapper, "mapper");
        previousProjection.verify(exactMapper);
        lifecycleEvent.verify(exactMapper);
        AuthoritativeOutcomeContinuousAssessmentRemediationReceipt
                sealed = seal(exactMapper);
        if (receiptFingerprint.isBlank()
                || commandFingerprint.isBlank()
                || !receiptFingerprint.equals(
                sealed.receiptFingerprint())
                || !commandFingerprint.equals(
                sealed.commandFingerprint())) {
            throw new IllegalArgumentException(
                    "continuous assessment remediation receipt fingerprint mismatch");
        }
    }

    /** Recomputes the actor-bound idempotency identity used by server and independent clients. */
    public static String commandFingerprint(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            String projectionId,
            String actorFingerprint,
            AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                    command) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new CommandBinding(
                        COMMAND_BINDING_SCHEMA_VERSION,
                        Objects.requireNonNull(scope, "scope"),
                        required(
                                projectionId,
                                "projectionId"),
                        fingerprint(
                                actorFingerprint,
                                "actorFingerprint"),
                        Objects.requireNonNull(
                                command, "command")),
                AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                        .MAXIMUM_CANONICAL_BYTES
                        + 64 * 1024);
    }

    private AuthoritativeOutcomeContinuousAssessmentRemediationReceipt
    withFingerprints(
            String receipt,
            String commandValue) {
        return new AuthoritativeOutcomeContinuousAssessmentRemediationReceipt(
                schemaVersion,
                receipt,
                commandValue,
                scope,
                projectionId,
                remediationGeneration,
                command,
                previousProjection,
                lifecycleEvent);
    }

    private static void requireTransition(
            CapabilitySnapshot.Scope scope,
            String projectionId,
            AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                    command,
            AuthoritativeOutcomeContinuousAssessmentProjection
                    previous,
            AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                    event) {
        AuthoritativeOutcomeContinuousAssessmentProjection
                current = event.projection();
        if (!scope.equals(previous.scope())
                || !scope.equals(current.scope())
                || !projectionId.equals(
                previous.projectionId())
                || !projectionId.equals(
                current.projectionId())
                || previous.status()
                != AuthoritativeOutcomeContinuousAssessmentProjection
                .Status.QUARANTINED
                || current.status()
                != AuthoritativeOutcomeContinuousAssessmentProjection
                .Status.QUEUED
                || event.transition()
                != AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                .Transition.REMEDIATION_ACCEPTED
                || event.actorFingerprint().isBlank()
                || event.eventOrdinal()
                != command.expectedLifecycleHeadOrdinal() + 1
                || !event.previousEventFingerprint().equals(
                command.expectedLifecycleHeadFingerprint())
                || !previous.recordFingerprint().equals(
                command.expectedProjectionFingerprint())
                || event.occurredAt().isBefore(
                previous.updatedAt())
                || !sameImmutableCoordinates(
                previous, current)
                || current.consecutiveFailures() != 0
                || current.attemptCount()
                != previous.attemptCount()
                || current.leaseEpoch()
                != previous.leaseEpoch()
                || !current.nextEligibleAt().equals(
                event.occurredAt())
                || !current.failureCode().isBlank()
                || current.terminalAt() != null
                || !current.leaseOwnerFingerprint().isBlank()
                || !current.leaseExpiresAt().equals(
                java.time.Instant.EPOCH)) {
            throw new IllegalArgumentException(
                    "continuous assessment remediation receipt transition is inconsistent");
        }
    }

    private static boolean sameImmutableCoordinates(
            AuthoritativeOutcomeContinuousAssessmentProjection previous,
            AuthoritativeOutcomeContinuousAssessmentProjection current) {
        return previous.populationRef().equals(
                current.populationRef())
                && previous.assessmentId().equals(
                current.assessmentId())
                && Objects.equals(
                previous.lastAssessmentRef(),
                current.lastAssessmentRef())
                && previous.observationSetFingerprint().equals(
                current.observationSetFingerprint())
                && previous.dispositionSetFingerprint().equals(
                current.dispositionSetFingerprint())
                && previous.currentThrough().equals(
                current.currentThrough())
                && previous.freshUntil().equals(
                current.freshUntil())
                && previous.createdAt().equals(
                current.createdAt());
    }

    private static String optionalFingerprint(
            String value, String field) {
        String exact = normalized(value);
        if (!exact.isBlank()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(
            String value, String field) {
        String exact = normalized(value);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String required(
            String value, String field) {
        String exact = normalized(value);
        if (exact.isBlank()) {
            throw new IllegalArgumentException(
                    field + " is required");
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record CommandBinding(
            String schemaVersion,
            CapabilitySnapshot.Scope scope,
            String projectionId,
            String actorFingerprint,
            AuthoritativeOutcomeContinuousAssessmentRemediationRequest
                    command
    ) {
    }
}
