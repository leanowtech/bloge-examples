package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated synchronous orchestrator for a bounded durable recovery signal sequence.
 *
 * <p>The service first reserves a payload-free fingerprint covering the complete ordered signal
 * program. It then derives stable child keys, replays or commits one recovery step, and after each
 * new suspension uses the normal owner-claim boundary to freshly authorize and acquire the exact
 * released checkpoint. A lost response can therefore restart the loop from index zero without
 * applying a committed signal twice. This is a bounded HTTP orchestration primitive, not a remote
 * worker supervisor or an unbounded background scheduler.</p>
 */
public final class DurableTestRecoverySequenceService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int MAX_SIGNALS = 16;
    private static final int MAX_SIGNAL_BYTES = 256 * 1024;
    private static final int MAX_SEQUENCE_BYTES = 1024 * 1024;

    private final DurableTestExecutionCheckpointRepository checkpoints;
    private final DurableTestOwnerClaimService ownerClaims;
    private final DurableTestTerminalRecoveryService recoverySteps;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;

    /**
     * Creates the fail-closed bounded sequence orchestrator.
     *
     * @param checkpoints payload-free outer intent reservation authority
     * @param ownerClaims exact intermediate checkpoint re-authorization and claim boundary
     * @param recoverySteps one-signal atomic recovery-step boundary
     * @param securityEvents transaction-capable semantic audit sink
     * @param objectMapper canonical request and signal fingerprint mapper
     */
    public DurableTestRecoverySequenceService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestOwnerClaimService ownerClaims,
            DurableTestTerminalRecoveryService recoverySteps,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.ownerClaims = Objects.requireNonNull(ownerClaims, "ownerClaims");
        this.recoverySteps = Objects.requireNonNull(recoverySteps, "recoverySteps");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Advances through the supplied signal program until terminal or signal exhaustion.
     *
     * <p>Every signal and intermediate owner claim has a deterministic child idempotency key. If
     * any child fails after earlier steps committed, retrying the unchanged outer request replays
     * those children and continues at the first uncommitted boundary.</p>
     *
     * @param runId path-bound durable run identity
     * @param request exact initial fence and complete ordered signal program
     * @param identity freshly authenticated workload authority
     * @return ordered payload-free steps and the final stable boundary
     */
    public DurableTestRecoverySequenceResponse advance(
            String runId,
            DurableTestRecoverySequenceRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        try {
            validateRequest(runId, request, identity);
        } catch (IntegrationProblemException invalid) {
            rejected(identity, safeRunId(runId), invalid.problem().code(), "");
            throw invalid;
        }
        String normalizedRunId = runId.trim();
        List<String> signalFingerprints;
        try {
            signalFingerprints = signalFingerprints(request, identity);
        } catch (IntegrationProblemException invalid) {
            rejected(identity, normalizedRunId, invalid.problem().code(),
                    request.clientRequestId());
            throw invalid;
        }
        String requestFingerprint = requestFingerprint(
                normalizedRunId, request, signalFingerprints, identity);
        DurableTestExecutionCheckpointRepository.RecoverySequenceCommand command =
                new DurableTestExecutionCheckpointRepository.RecoverySequenceCommand(
                        request.clientRequestId(), requestFingerprint,
                        new DurableTestExecutionCheckpoint.Scope(
                                identity.tenantId(), identity.organizationId(),
                                identity.projectId(), identity.environmentId(),
                                identity.actorId()),
                        normalizedRunId, request.signals().size());
        DurableTestExecutionCheckpointRepository.RecoverySequenceReservation reservation =
                reserve(command, boundAllowedAudit(
                        identity, normalizedRunId, request.clientRequestId(),
                        request.signals().size()), identity);

        String childNamespace = DurableTestRecoveryCommandKeys.sequenceNamespace(
                objectMapper, identity.tenantId(), identity.environmentId(),
                request.clientRequestId());
        DurableTestRecoverySequenceRequest.Fence currentFence = request.expectedFence();
        String currentCheckpointFingerprint = request.expectedCheckpointFingerprint();
        List<DurableTestRecoveryStepResponse> committedSteps = new ArrayList<>();
        boolean allReplayed = reservation.idempotentReplay();
        for (int index = 0; index < request.signals().size(); index++) {
            DurableTestRecoverySequenceRequest.Signal signal = request.signals().get(index);
            DurableTestRecoveryStepRequest stepRequest = new DurableTestRecoveryStepRequest(
                    "", DurableTestRecoveryCommandKeys.sequenceStep(childNamespace, index),
                    new DurableTestRecoveryStepRequest.Fence(
                            currentFence.ownerId(), currentFence.leaseEpoch(),
                            currentFence.revision()),
                    currentCheckpointFingerprint,
                    new DurableTestRecoveryStepRequest.Signal(signal.nodeId(), signal.data()));
            DurableTestRecoveryStepResponse step = recoverySteps.advance(
                    normalizedRunId, stepRequest, identity);
            requireStep(step, normalizedRunId, identity);
            committedSteps.add(step);
            allReplayed &= step.idempotentReplay();
            if ("TERMINAL".equals(step.status())) {
                break;
            }
            if (index + 1 < request.signals().size()) {
                DurableTestOwnerClaimResponse claim = ownerClaims.claim(
                        normalizedRunId,
                        new DurableTestOwnerClaimRequest(
                                "", DurableTestRecoveryCommandKeys.sequenceClaim(
                                childNamespace, index + 1),
                                new DurableTestOwnerClaimRequest.Fence(
                                        step.ownerId(), step.leaseEpoch(), step.revision()),
                                step.checkpointFingerprint()),
                        identity);
                requireClaim(claim, normalizedRunId, identity);
                allReplayed &= claim.idempotentReplay();
                currentFence = new DurableTestRecoverySequenceRequest.Fence(
                        claim.ownerId(), claim.leaseEpoch(), claim.revision());
                currentCheckpointFingerprint = claim.checkpointFingerprint();
            }
        }
        DurableTestRecoveryStepResponse last = committedSteps.getLast();
        return new DurableTestRecoverySequenceResponse(
                "", normalizedRunId, last.outcome(), last.status(),
                "TERMINAL".equals(last.status()) ? "TERMINAL" : "SIGNALS_EXHAUSTED",
                request.signals().size(), committedSteps.size(), committedSteps, allReplayed);
    }

    private DurableTestExecutionCheckpointRepository.RecoverySequenceReservation reserve(
            DurableTestExecutionCheckpointRepository.RecoverySequenceCommand command,
            TestRuntimeTransactionMutation boundAudit,
            IntegrationRequestContext identity) {
        try {
            return checkpoints.reserveRecoverySequenceIdempotently(command, boundAudit);
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            if (conflict.reason()
                    == DurableTestExecutionCheckpointConflictException.Reason
                    .REPLAY_WINDOW_EXPIRED) {
                rejected(identity, command.runId(),
                        "RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED",
                        command.clientRequestId());
                throw new IntegrationProblemException(IntegrationProblem.conflict(
                        "RG.TEST.DURABLE_RECOVERY_SEQUENCE_REPLAY_WINDOW_EXPIRED",
                        "Exact recovery-sequence replay expired while its request key "
                                + "remains reserved.",
                        identity.correlationId(), Map.of()));
            }
            if (conflict.reason()
                    == DurableTestExecutionCheckpointConflictException.Reason
                    .IDEMPOTENCY_CONFLICT) {
                rejected(identity, command.runId(),
                        "RG.TEST.DURABLE_RECOVERY_SEQUENCE_IDEMPOTENCY_CONFLICT",
                        command.clientRequestId());
                throw new IntegrationProblemException(IntegrationProblem.conflict(
                        "RG.TEST.DURABLE_RECOVERY_SEQUENCE_IDEMPOTENCY_CONFLICT",
                        "clientRequestId already identifies a different complete recovery sequence.",
                        identity.correlationId(), Map.of()));
            }
            throw unavailable(identity, "RG.TEST.DURABLE_RECOVERY_SEQUENCE_INTEGRITY_FAILED",
                    "The durable recovery-sequence reservation failed integrity verification.");
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
    }

    private List<String> signalFingerprints(
            DurableTestRecoverySequenceRequest request,
            IntegrationRequestContext identity) {
        try {
            ProtocolFingerprint.ofBounded(
                    objectMapper, request.signals(), MAX_SEQUENCE_BYTES);
            return request.signals().stream()
                    .map(signal -> ProtocolFingerprint.ofBounded(
                            objectMapper, signal.data(), MAX_SIGNAL_BYTES))
                    .toList();
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_RECOVERY_SEQUENCE_TOO_LARGE",
                    "Recovery sequence permits at most 256 KiB per signal and 1 MiB in total.",
                    identity.correlationId(), Map.of()));
        }
    }

    private String requestFingerprint(
            String runId,
            DurableTestRecoverySequenceRequest request,
            List<String> signalFingerprints,
            IntegrationRequestContext identity) {
        List<Map<String, String>> signalProgram = new ArrayList<>();
        for (int index = 0; index < request.signals().size(); index++) {
            signalProgram.add(Map.of(
                    "nodeId", request.signals().get(index).nodeId(),
                    "dataFingerprint", signalFingerprints.get(index)));
        }
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion",
                        "bloge.durableTestRecoverySequenceAuthorizedIntent.v1"),
                Map.entry("runId", runId),
                Map.entry("clientRequestId", request.clientRequestId()),
                Map.entry("expectedFence", request.expectedFence()),
                Map.entry("expectedCheckpointFingerprint",
                        request.expectedCheckpointFingerprint()),
                Map.entry("signals", signalProgram),
                Map.entry("tenantId", identity.tenantId()),
                Map.entry("organizationId", identity.organizationId()),
                Map.entry("projectId", identity.projectId()),
                Map.entry("environmentId", identity.environmentId()),
                Map.entry("region", identity.region()),
                Map.entry("actorType", identity.actorType()),
                Map.entry("actorId", identity.actorId()),
                Map.entry("delegatedBy", identity.delegatedBy()),
                Map.entry("delegationGrantId", identity.delegationGrantId()),
                Map.entry("purpose", identity.purpose()),
                Map.entry("clearance", identity.clearance()),
                Map.entry("groups", identity.groups().stream().sorted().toList())));
    }

    private static void requireStep(
            DurableTestRecoveryStepResponse step,
            String runId,
            IntegrationRequestContext identity) {
        if (step == null || !runId.equals(step.runId())) {
            throw unavailable(identity, "RG.TEST.DURABLE_RECOVERY_SEQUENCE_RESULT_INVALID",
                    "A recovery-sequence child step returned an invalid scoped result.");
        }
    }

    private static void requireClaim(
            DurableTestOwnerClaimResponse claim,
            String runId,
            IntegrationRequestContext identity) {
        if (claim == null || !runId.equals(claim.runId())
                || !"RESUMING".equals(claim.status())) {
            throw unavailable(identity, "RG.TEST.DURABLE_RECOVERY_SEQUENCE_CLAIM_INVALID",
                    "An intermediate owner claim returned an invalid scoped result.");
        }
    }

    private TestRuntimeTransactionMutation boundAllowedAudit(
            IntegrationRequestContext identity,
            String runId,
            String clientRequestId,
            int signalCount) {
        try {
            TestRuntimeTransactionMutation mutation = securityEvents.boundAppend(
                    new TestSecurityEvent(
                            0, Instant.now(), identity.correlationId(), identity.tenantId(),
                            identity.environmentId(), identity.actorId(),
                            "DURABLE_RECOVERY_SEQUENCE", "ALLOWED",
                            "RG.TEST.DURABLE_RECOVERY_SEQUENCE_AUTHORIZED",
                            Map.of("runId", runId, "clientRequestId", clientRequestId,
                                    "signalCount", signalCount)));
            if (mutation == null) {
                throw new IllegalStateException("Security audit did not provide a bound mutation");
            }
            return mutation;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable recovery sequence is unavailable because its audit cannot commit.");
        }
    }

    private void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(
                identity.environmentId().toLowerCase(Locale.ROOT))) {
            rejected(identity, "", "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN", "");
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN",
                    "Durable recovery sequences are available only in test or staging.",
                    identity.correlationId(), Map.of()));
        }
    }

    private void rejected(
            IntegrationRequestContext identity,
            String runId,
            String reasonCode,
            String clientRequestId) {
        Map<String, Object> facts = clientRequestId == null || clientRequestId.isBlank()
                ? Map.of("runId", normalized(runId))
                : Map.of("runId", normalized(runId),
                        "clientRequestId", clientRequestId);
        try {
            securityEvents.append(new TestSecurityEvent(
                    0, Instant.now(), identity.correlationId(), identity.tenantId(),
                    identity.environmentId(), identity.actorId(),
                    "DURABLE_RECOVERY_SEQUENCE", "REJECTED", reasonCode, facts));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable recovery sequence is unavailable because its audit cannot commit.");
        }
    }

    private static void validateRequest(
            String runId,
            DurableTestRecoverySequenceRequest request,
            IntegrationRequestContext identity) {
        boolean valid = request != null
                && DurableTestRecoverySequenceRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                && IDENTIFIER.matcher(normalized(runId)).matches()
                && IDENTIFIER.matcher(request.clientRequestId()).matches()
                && request.expectedFence() != null
                && IDENTIFIER.matcher(request.expectedFence().ownerId()).matches()
                && request.expectedFence().leaseEpoch() > 0
                && request.expectedFence().revision() >= 0
                && FINGERPRINT.matcher(
                request.expectedCheckpointFingerprint()).matches()
                && !request.signals().isEmpty()
                && request.signals().size() <= MAX_SIGNALS
                && request.signals().stream().allMatch(signal -> signal != null
                && IDENTIFIER.matcher(signal.nodeId()).matches()
                && signal.data() != null);
        if (!valid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_RECOVERY_SEQUENCE_REQUEST_INVALID",
                    "Recovery sequence requires an exact initial fence and one to sixteen ordered signals.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeRunId(String value) {
        String normalized = normalized(value);
        return IDENTIFIER.matcher(normalized).matches() ? normalized : "";
    }
}
