package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free, promotion-blocking receipt for one durable recovery terminal transition.
 *
 * <p>Version 1 deliberately records {@value #EVIDENCE_STATUS} with at least one bounded gap code.
 * The durable checkpoint currently retains replay cursors but not the complete pre-checkpoint
 * node, edge, and attempt trace, so this receipt proves an atomic control outcome without claiming
 * complete correctness evidence. It contains no signal, fixture, request, response, credential,
 * provider seed, or engine-checkpoint payload.</p>
 *
 * @param schemaVersion terminal-receipt protocol version
 * @param authorization exact dependency authorization used by the recovery owner
 * @param scope immutable governed execution scope
 * @param runId governed durable run identity
 * @param engineExecutionId exact BLOGE execution identity
 * @param sourceDispatchFingerprint exact consumed worker handoff
 * @param sourceCheckpointFingerprint exact pre-terminal control checkpoint
 * @param terminalCheckpointFingerprint exact terminal control checkpoint
 * @param ownerId recovery owner that committed the terminal transition
 * @param leaseEpoch exact ownership generation
 * @param sourceRevision source control revision
 * @param terminalRevision one-revision terminal successor
 * @param executionOutcome normalized terminal engine outcome
 * @param terminalFixtureStateFingerprint final fixture-consumption state identity
 * @param terminalProviderStateFingerprint final deterministic-provider state identity
 * @param terminalEngineStateFingerprint final BLOGE aggregate identity
 * @param evidenceStatus fixed promotion-blocking evidence status
 * @param evidenceGapCodes sorted, explicit reasons complete run evidence is unavailable
 * @param completedAt database-authority terminal time
 * @param receiptFingerprint canonical fingerprint of every preceding field
 */
public record DurableTestRecoveryTerminalReceipt(
        String schemaVersion,
        DurableTestRecoveryAuthorization authorization,
        DurableTestExecutionCheckpoint.Scope scope,
        String runId,
        String engineExecutionId,
        String sourceDispatchFingerprint,
        String sourceCheckpointFingerprint,
        String terminalCheckpointFingerprint,
        String ownerId,
        long leaseEpoch,
        long sourceRevision,
        long terminalRevision,
        ExecutionOutcome executionOutcome,
        String terminalFixtureStateFingerprint,
        String terminalProviderStateFingerprint,
        String terminalEngineStateFingerprint,
        String evidenceStatus,
        List<String> evidenceGapCodes,
        Instant completedAt,
        String receiptFingerprint
) {
    /** Current promotion-blocking terminal-receipt protocol. */
    public static final String SCHEMA_VERSION = "bloge.durableTestRecoveryTerminalReceipt.v1";
    /** Evidence status mandated until complete pre-checkpoint trace closure is durable. */
    public static final String EVIDENCE_STATUS = "EVIDENCE_INCOMPLETE";
    private static final int MAX_CANONICAL_BYTES = 128 * 1024;
    private static final int MAX_GAPS = 32;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern GAP_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Normalized terminal BLOGE outcomes accepted by the first recovery-receipt protocol. */
    public enum ExecutionOutcome {
        /** BLOGE completed the recovered graph. */
        COMPLETED,
        /** BLOGE reached a normal failed terminal lifecycle. */
        FAILED,
        /** BLOGE failed while applying recovery. */
        FAILED_RECOVERY,
        /** A governed cancellation reached BLOGE terminal state. */
        CANCELLED,
        /** A governed termination reached BLOGE terminal state. */
        TERMINATED
    }

    /** Rejects incomplete identities, non-terminal revisions, and implicit evidence completeness. */
    public DurableTestRecoveryTerminalReceipt {
        schemaVersion = required(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported durable recovery terminal receipt version");
        }
        authorization = Objects.requireNonNull(authorization, "authorization");
        scope = Objects.requireNonNull(scope, "scope");
        runId = identifier(runId, "runId");
        engineExecutionId = identifier(engineExecutionId, "engineExecutionId");
        sourceDispatchFingerprint = fingerprint(
                sourceDispatchFingerprint, "sourceDispatchFingerprint");
        sourceCheckpointFingerprint = fingerprint(
                sourceCheckpointFingerprint, "sourceCheckpointFingerprint");
        terminalCheckpointFingerprint = fingerprint(
                terminalCheckpointFingerprint, "terminalCheckpointFingerprint");
        ownerId = identifier(ownerId, "ownerId");
        executionOutcome = Objects.requireNonNull(executionOutcome, "executionOutcome");
        terminalFixtureStateFingerprint = fingerprint(
                terminalFixtureStateFingerprint, "terminalFixtureStateFingerprint");
        terminalProviderStateFingerprint = fingerprint(
                terminalProviderStateFingerprint, "terminalProviderStateFingerprint");
        terminalEngineStateFingerprint = fingerprint(
                terminalEngineStateFingerprint, "terminalEngineStateFingerprint");
        evidenceStatus = required(evidenceStatus, "evidenceStatus");
        if (!EVIDENCE_STATUS.equals(evidenceStatus)) {
            throw new IllegalArgumentException(
                    "Durable recovery terminal receipt v1 requires incomplete evidence");
        }
        evidenceGapCodes = normalizedGaps(evidenceGapCodes);
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        receiptFingerprint = receiptFingerprint == null ? "" : receiptFingerprint.trim();
        if (leaseEpoch <= 0 || sourceRevision < 0
                || sourceRevision == Long.MAX_VALUE
                || terminalRevision != sourceRevision + 1) {
            throw new IllegalArgumentException(
                    "Terminal receipt requires one exact positive fenced revision advance");
        }
        if (!receiptFingerprint.isEmpty()
                && !FINGERPRINT.matcher(receiptFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "receiptFingerprint must be empty or a canonical SHA-256 fingerprint");
        }
    }

    /**
     * Issues a promotion-blocking receipt for one exact terminal checkpoint.
     *
     * @param objectMapper canonical protocol mapper
     * @param sourceDispatch committed source worker handoff
     * @param terminalCheckpoint verified terminal checkpoint produced from that handoff
     * @param executionOutcome normalized BLOGE terminal outcome
     * @param evidenceGapCodes explicit reasons full correctness evidence is unavailable
     * @return sealed payload-free terminal receipt
     */
    public static DurableTestRecoveryTerminalReceipt issue(
            ObjectMapper objectMapper,
            DurableTestRecoveryDispatch sourceDispatch,
            DurableTestExecutionCheckpoint terminalCheckpoint,
            ExecutionOutcome executionOutcome,
            List<String> evidenceGapCodes) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        DurableTestRecoveryDispatch source = Objects.requireNonNull(
                sourceDispatch, "sourceDispatch");
        source.requireValid(mapper);
        DurableTestExecutionCheckpoint terminal = Objects.requireNonNull(
                terminalCheckpoint, "terminalCheckpoint");
        requireTerminalAgreement(mapper, source, terminal);
        var lifecycle = terminal.lifecycle();
        DurableTestRecoveryTerminalReceipt material =
                new DurableTestRecoveryTerminalReceipt(
                        SCHEMA_VERSION, source.authorization(), terminal.scope(),
                        terminal.runId(), terminal.engineExecutionId(),
                        source.dispatchFingerprint(), source.checkpointFingerprint(),
                        terminal.checkpointFingerprint(), lifecycle.ownerId(),
                        lifecycle.leaseEpoch(), source.revision(), lifecycle.revision(),
                        executionOutcome,
                        terminal.fixtureConsumptionState().stateFingerprint(),
                        terminal.executionServiceState().snapshotFingerprint(),
                        terminal.engineState().closureFingerprint(), EVIDENCE_STATUS,
                        evidenceGapCodes, lifecycle.updatedAt(), "");
        String sealed = ProtocolFingerprint.ofBounded(
                mapper, material.fingerprintMaterial(), MAX_CANONICAL_BYTES);
        return material.withReceiptFingerprint(sealed);
    }

    /**
     * Verifies receipt content identity and exact source/result agreement.
     *
     * @param objectMapper canonical protocol mapper
     * @param sourceDispatch expected committed source worker handoff
     * @param terminalCheckpoint expected terminal checkpoint
     */
    public void requireValid(
            ObjectMapper objectMapper,
            DurableTestRecoveryDispatch sourceDispatch,
            DurableTestExecutionCheckpoint terminalCheckpoint) {
        requireValid(objectMapper);
        requireTerminalAgreement(objectMapper, sourceDispatch, terminalCheckpoint);
        if (!agreesWith(sourceDispatch, terminalCheckpoint)) {
            throw new IllegalArgumentException(
                    "Durable recovery terminal receipt does not match its source and result");
        }
    }

    /**
     * Verifies nested authorization and aggregate terminal-receipt content identity.
     *
     * @param objectMapper canonical protocol mapper
     */
    public void requireValid(ObjectMapper objectMapper) {
        authorization.requireValid(objectMapper);
        String actual = ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(objectMapper, "objectMapper"),
                fingerprintMaterial(), MAX_CANONICAL_BYTES);
        if (!actual.equals(receiptFingerprint)) {
            throw new IllegalArgumentException(
                    "Invalid durable recovery terminal receipt fingerprint");
        }
    }

    /**
     * Tests exact source dispatch and terminal checkpoint agreement.
     *
     * @param sourceDispatch candidate source handoff
     * @param terminalCheckpoint candidate terminal checkpoint
     * @return whether both values exactly match this receipt
     */
    public boolean agreesWith(
            DurableTestRecoveryDispatch sourceDispatch,
            DurableTestExecutionCheckpoint terminalCheckpoint) {
        if (sourceDispatch == null || terminalCheckpoint == null) {
            return false;
        }
        var lifecycle = terminalCheckpoint.lifecycle();
        return authorization.equals(sourceDispatch.authorization())
                && scope.equals(terminalCheckpoint.scope())
                && runId.equals(sourceDispatch.runId())
                && runId.equals(terminalCheckpoint.runId())
                && engineExecutionId.equals(sourceDispatch.engineExecutionId())
                && engineExecutionId.equals(terminalCheckpoint.engineExecutionId())
                && sourceDispatchFingerprint.equals(sourceDispatch.dispatchFingerprint())
                && sourceCheckpointFingerprint.equals(sourceDispatch.checkpointFingerprint())
                && terminalCheckpointFingerprint.equals(
                terminalCheckpoint.checkpointFingerprint())
                && ownerId.equals(sourceDispatch.ownerId())
                && ownerId.equals(lifecycle.ownerId())
                && leaseEpoch == sourceDispatch.leaseEpoch()
                && leaseEpoch == lifecycle.leaseEpoch()
                && sourceRevision == sourceDispatch.revision()
                && terminalRevision == lifecycle.revision()
                && terminalFixtureStateFingerprint.equals(
                terminalCheckpoint.fixtureConsumptionState().stateFingerprint())
                && terminalProviderStateFingerprint.equals(
                terminalCheckpoint.executionServiceState().snapshotFingerprint())
                && terminalEngineStateFingerprint.equals(
                terminalCheckpoint.engineState().closureFingerprint())
                && completedAt.equals(lifecycle.updatedAt())
                && lifecycle.status() == DurableTestExecutionCheckpoint.Status.TERMINAL;
    }

    /**
     * Projects canonical material covered by {@link #receiptFingerprint()}.
     *
     * @return payload-free terminal evidence material
     */
    public Map<String, Object> fingerprintMaterial() {
        return Map.ofEntries(
                Map.entry("schemaVersion", schemaVersion),
                Map.entry("authorization", authorization),
                Map.entry("scope", scope),
                Map.entry("runId", runId),
                Map.entry("engineExecutionId", engineExecutionId),
                Map.entry("sourceDispatchFingerprint", sourceDispatchFingerprint),
                Map.entry("sourceCheckpointFingerprint", sourceCheckpointFingerprint),
                Map.entry("terminalCheckpointFingerprint", terminalCheckpointFingerprint),
                Map.entry("ownerId", ownerId),
                Map.entry("leaseEpoch", leaseEpoch),
                Map.entry("sourceRevision", sourceRevision),
                Map.entry("terminalRevision", terminalRevision),
                Map.entry("executionOutcome", executionOutcome),
                Map.entry("terminalFixtureStateFingerprint", terminalFixtureStateFingerprint),
                Map.entry("terminalProviderStateFingerprint", terminalProviderStateFingerprint),
                Map.entry("terminalEngineStateFingerprint", terminalEngineStateFingerprint),
                Map.entry("evidenceStatus", evidenceStatus),
                Map.entry("evidenceGapCodes", evidenceGapCodes),
                Map.entry("completedAt", completedAt));
    }

    private DurableTestRecoveryTerminalReceipt withReceiptFingerprint(String value) {
        return new DurableTestRecoveryTerminalReceipt(
                schemaVersion, authorization, scope, runId, engineExecutionId,
                sourceDispatchFingerprint, sourceCheckpointFingerprint,
                terminalCheckpointFingerprint, ownerId, leaseEpoch, sourceRevision,
                terminalRevision, executionOutcome, terminalFixtureStateFingerprint,
                terminalProviderStateFingerprint, terminalEngineStateFingerprint,
                evidenceStatus, evidenceGapCodes, completedAt, value);
    }

    private static void requireTerminalAgreement(
            ObjectMapper objectMapper,
            DurableTestRecoveryDispatch source,
            DurableTestExecutionCheckpoint terminal) {
        source.requireValid(objectMapper);
        var lifecycle = terminal.lifecycle();
        if (!source.scope().equals(terminal.scope())
                || !source.runId().equals(terminal.runId())
                || !source.engineExecutionId().equals(terminal.engineExecutionId())
                || !source.ownerId().equals(lifecycle.ownerId())
                || source.leaseEpoch() != lifecycle.leaseEpoch()
                || source.revision() == Long.MAX_VALUE
                || lifecycle.revision() != source.revision() + 1
                || lifecycle.status() != DurableTestExecutionCheckpoint.Status.TERMINAL
                || lifecycle.updatedAt().isAfter(lifecycle.leaseExpiresAt())) {
            throw new IllegalArgumentException(
                    "Terminal checkpoint does not succeed the recovery dispatch fence");
        }
        var dependencies = terminal.dependencies();
        var authorization = source.authorization();
        var target = dependencies.target();
        String replayFingerprint = ProtocolFingerprint.of(
                objectMapper, dependencies.plan().replayDependencies());
        if (target == null
                || !authorization.targetFingerprint().equals(target.fingerprint())
                || !authorization.planFingerprint().equals(
                dependencies.plan().planFingerprint())
                || !authorization.fixtureFingerprint().equals(
                dependencies.fixture().fingerprint())
                || !authorization.replayClosureFingerprint().equals(replayFingerprint)
                || !authorization.authorityFingerprint().equals(
                dependencies.identitySnapshot().fingerprint())
                || !authorization.authorizedPurpose().equals(
                dependencies.plan().authorizedPurpose())
                || !authorization.sideEffectPolicy().equals(
                dependencies.sideEffectPolicy())) {
            throw new IllegalArgumentException(
                    "Terminal checkpoint does not match recovery authorization");
        }
    }

    private static List<String> normalizedGaps(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_GAPS) {
            throw new IllegalArgumentException(
                    "At least one bounded evidence gap is required");
        }
        List<String> normalized = values.stream()
                .map(value -> required(value, "evidence gap").toUpperCase(
                        java.util.Locale.ROOT))
                .peek(value -> {
                    if (!GAP_CODE.matcher(value).matches()) {
                        throw new IllegalArgumentException(
                                "Evidence gap must be a bounded stable code");
                    }
                })
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (normalized.isEmpty() || normalized.size() != values.size()) {
            throw new IllegalArgumentException(
                    "Evidence gaps must be non-empty and unique");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static String identifier(String value, String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded stable identifier");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
