package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionPayload;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionProtocolIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidenceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateTransitionRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateWriteOutcomeRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateWriteOutcomeRunEvidenceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpec;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpecIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concurrent run-scoped journal that closes Session reads and writes into signed state evidence.
 *
 * <p>The constructor freezes every state-backed plan site against the admitted Session payload.
 * Read-only runs retain the immutable v1 evidence shape. Runs with one or more virtual-write
 * bindings emit v3 evidence that binds initial/final Session heads, every read to its observed
 * revision, and every write delegate attempt to one committed, replayed, rejected, pre-commit
 * failed, or commit-outcome-unknown terminal record. Completion is one-shot and rejects duplicate
 * invocation coordinates or any runtime/spec drift.</p>
 */
public final class MirrorStateAccessJournal
        implements MirrorStateAccessObserver {
    private final ObjectMapper mapper;
    private final MirrorPlan plan;
    private final MirrorResolver.SessionContext sessionContext;
    private final Map<String, Binding> bindingsBySite;
    private final ConcurrentHashMap<Coordinate, ReadObservation>
            reads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Coordinate,
            WriteObservation> writeAttempts =
            new ConcurrentHashMap<>();
    private final AtomicBoolean completed = new AtomicBoolean();

    /**
     * Creates one exact state journal before the independent engine starts.
     *
     * @param mapper canonical protocol mapper
     * @param plan sealed mirror plan
     * @param sessionContext admitted Session head, exact site bindings, and optional write bridge
     */
    public MirrorStateAccessJournal(
            ObjectMapper mapper,
            MirrorPlan plan,
            MirrorResolver.SessionContext sessionContext) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.sessionContext = Objects.requireNonNull(
                sessionContext, "sessionContext");
        if (!plan.planFingerprint().equals(
                sessionContext.planFingerprint())) {
            throw new IllegalArgumentException(
                    "state journal plan differs from the Session context");
        }
        MirrorSessionPayload payload = sessionContext.payload();
        MirrorSessionProtocolIntegrity.verify(mapper, payload);
        if (!plan.scope().equals(payload.state().scope())
                || !plan.stateModelRefs().contains(
                payload.state().stateModelRef())) {
            throw new IllegalArgumentException(
                    "state journal Session scope or state model is not admitted");
        }
        LinkedHashMap<String, Binding> bindings =
                new LinkedHashMap<>();
        boolean writeBinding = false;
        for (MirrorPlan.ExternalBinding binding
                : plan.externalBindings()) {
            if (!binding.resolverOrder().contains(
                    MirrorPlan.MirrorSource.SESSION_STATE)) {
                continue;
            }
            MirrorArtifactRef capability =
                    sessionContext.capabilitiesBySite().get(
                            binding.invocationSiteId());
            if (!binding.capabilityRef().equals(capability)) {
                throw new IllegalArgumentException(
                        "state journal capability binding differs from the plan");
            }
            List<StateReadSpec> reads = payload.stateReadSpecs()
                    .stream()
                    .filter(spec -> spec.targetCapabilityRef()
                            .equals(capability))
                    .toList();
            List<WriteEffectSpec> writes = payload.writeEffects()
                    .stream()
                    .filter(effect -> effect.targetCapabilityRef()
                            .equals(capability))
                    .toList();
            if (reads.size() + writes.size() != 1) {
                throw new IllegalArgumentException(
                        "state journal requires one exact interaction spec per site");
            }
            Binding projected;
            if (!reads.isEmpty()) {
                StateReadSpec spec = reads.getFirst();
                if (spec.lifecycle()
                        != CapabilitySnapshot.Lifecycle.ACTIVE) {
                    throw new IllegalArgumentException(
                            "state journal read spec must be active");
                }
                StateReadSpecIntegrity.verify(
                        mapper, spec, payload.stateModel());
                projected = new Binding(
                        binding, capability, spec, null);
            } else {
                WriteEffectSpec effect = writes.getFirst();
                if (effect.lifecycle()
                        != CapabilitySnapshot.Lifecycle.ACTIVE
                        || sessionContext.runSession() == null) {
                    throw new IllegalArgumentException(
                            "state journal write effect requires an active run session");
                }
                WriteEffectSpecIntegrity.verify(
                        mapper, effect, payload.stateModel());
                projected = new Binding(
                        binding, capability, null, effect);
                writeBinding = true;
            }
            if (bindings.put(
                    binding.invocationSiteId(),
                    projected) != null) {
                throw new IllegalArgumentException(
                        "state journal binding sites must be unique");
            }
        }
        if (bindings.isEmpty()
                || writeBinding
                != (sessionContext.runSession() != null)) {
            throw new IllegalArgumentException(
                    "state journal run mode differs from its binding closure");
        }
        this.bindingsBySite = Map.copyOf(bindings);
    }

    @Override
    public void observed(
            MirrorResolver.Request request,
            StateReadSpec spec,
            String businessKeyFingerprint,
            MirrorStateRunEvidence.AccessOutcome outcome,
            String stateRecordFingerprint,
            String projectedOutputFingerprint) {
        MirrorSessionPayload payload =
                requireContext(request).currentPayload();
        SessionStateSpace state = payload.state();
        observedAt(
                request, spec, stateRef(state),
                state.stateRevision(),
                state.worldFingerprint(),
                state.logicalClock(),
                businessKeyFingerprint, outcome,
                stateRecordFingerprint,
                projectedOutputFingerprint);
    }

    @Override
    public void observedAt(
            MirrorResolver.Request request,
            StateReadSpec spec,
            MirrorArtifactRef observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String businessKeyFingerprint,
            MirrorStateRunEvidence.AccessOutcome outcome,
            String stateRecordFingerprint,
            String projectedOutputFingerprint) {
        ensureOpen();
        MirrorResolver.SessionContext context =
                requireContext(request);
        Objects.requireNonNull(spec, "spec");
        Binding binding = bindingsBySite.get(
                request.site().invocationSiteId());
        if (binding == null || binding.readSpec() == null
                || !StateReadSpecIntegrity.reference(spec).equals(
                StateReadSpecIntegrity.reference(
                        binding.readSpec()))
                || !observedStateRef.id().equals(
                context.payload().state().sessionId())) {
            throw new IllegalArgumentException(
                    "state read observation differs from its frozen journal binding");
        }
        String errorCode =
                outcome
                == MirrorStateRunEvidence.AccessOutcome.TOMBSTONED
                        ? MirrorStateRunEvidence
                        .MirrorSessionStateError
                        .ENTITY_TOMBSTONED : "";
        ReadObservation observation = new ReadObservation(
                request.site().invocationSiteId(),
                request.site().graphPath(),
                request.site().correlationKey(),
                request.occurrence(), request.attempt(),
                request.requestFingerprint(),
                binding, observedStateRef,
                observedStateRevision,
                observedWorldFingerprint,
                observedLogicalClock,
                businessKeyFingerprint, outcome,
                stateRecordFingerprint,
                projectedOutputFingerprint, errorCode);
        Coordinate coordinate = Coordinate.from(request);
        if (reads.putIfAbsent(
                coordinate, observation) != null
                || writeAttempts.containsKey(coordinate)) {
            throw new IllegalStateException(
                    "duplicate mirror state interaction coordinate");
        }
    }

    @Override
    public void transitioned(
            MirrorResolver.Request request,
            WriteEffectSpec spec,
            MirrorStateTransitionObservation transition) {
        ensureOpen();
        requireContext(request);
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(transition, "transition");
        Binding binding = bindingsBySite.get(
                request.site().invocationSiteId());
        if (binding == null || binding.writeEffect() == null
                || !WriteEffectSpecIntegrity.reference(spec)
                .equals(transition.writeEffectRef())
                || !WriteEffectSpecIntegrity.reference(
                binding.writeEffect()).equals(
                transition.writeEffectRef())
                || !transition.initialStateRef().id().equals(
                sessionContext.payload().state().sessionId())) {
            throw new IllegalArgumentException(
                    "state transition differs from its frozen journal binding");
        }
        Coordinate coordinate = Coordinate.from(request);
        WriteObservation observation =
                new WriteObservation(
                        request.requestFingerprint(),
                        MirrorStateWriteAttemptObservation
                                .succeeded(transition));
        if (writeAttempts.putIfAbsent(
                coordinate, observation) != null
                || reads.containsKey(coordinate)) {
            throw new IllegalStateException(
                    "duplicate mirror state interaction coordinate");
        }
    }

    @Override
    public void writeFailed(
            MirrorResolver.Request request,
            WriteEffectSpec spec,
            MirrorStateWriteAttemptObservation failure) {
        ensureOpen();
        requireContext(request);
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(failure, "failure");
        Binding binding = bindingsBySite.get(
                request.site().invocationSiteId());
        if (binding == null || binding.writeEffect() == null
                || !WriteEffectSpecIntegrity.reference(spec)
                .equals(failure.writeEffectRef())
                || !WriteEffectSpecIntegrity.reference(
                binding.writeEffect()).equals(
                failure.writeEffectRef())
                || !failure.observedStateRef().id().equals(
                sessionContext.payload().state().sessionId())
                || failure.outcome()
                == MirrorStateWriteOutcomeRunEvidence
                .WriteOutcome.COMMITTED
                || failure.outcome()
                == MirrorStateWriteOutcomeRunEvidence
                .WriteOutcome.REPLAYED) {
            throw new IllegalArgumentException(
                    "state write failure differs from its frozen journal binding");
        }
        Coordinate coordinate = Coordinate.from(request);
        WriteObservation observation =
                new WriteObservation(
                        request.requestFingerprint(),
                        failure);
        if (writeAttempts.putIfAbsent(
                coordinate, observation) != null
                || reads.containsKey(coordinate)) {
            throw new IllegalStateException(
                    "duplicate mirror state interaction coordinate");
        }
    }

    /**
     * Closes and seals the complete payload-free state evidence value.
     *
     * @param runId terminal mirror run identity
     * @return sealed read-only v1 or failure-aware read/write v3 evidence
     */
    public MirrorStateEvidence complete(String runId) {
        if (!completed.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "mirror state access journal is already complete");
        }
        return sessionContext.runSession() == null
                ? completeReadOnly(runId)
                : completeReadWrite(runId);
    }

    /** @return number of unique state reads and writes observed so far */
    public int size() {
        return reads.size() + writeAttempts.size();
    }

    private MirrorStateRunEvidence completeReadOnly(
            String runId) {
        SessionStateSpace state =
                sessionContext.payload().state();
        List<MirrorStateRunEvidence.StatefulBinding> bindings =
                bindingsBySite.values().stream()
                        .map(binding ->
                                new MirrorStateRunEvidence
                                        .StatefulBinding(
                                        binding.planBinding()
                                                .invocationSiteId(),
                                        binding.planBinding()
                                                .graphPath(),
                                        binding.capabilityRef(),
                                        StateReadSpecIntegrity
                                                .reference(
                                                        binding
                                                                .readSpec())))
                        .toList();
        List<MirrorStateRunEvidence.StateAccess> accesses =
                reads.values().stream()
                        .map(ReadObservation::toV1)
                        .toList();
        return MirrorStateRunEvidenceIntegrity.seal(
                mapper, new MirrorStateRunEvidence(
                        MirrorStateRunEvidence.SCHEMA_VERSION,
                        "", runId, plan.planFingerprint(),
                        stateRef(state),
                        StateModelIntegrity.reference(
                                sessionContext.payload()
                                        .stateModel()),
                        state.stateRevision(),
                        state.worldFingerprint(),
                        state.logicalClock(),
                        MirrorStateRunEvidence.Mode
                                .READ_ONLY_SNAPSHOT,
                        bindings, accesses, List.of()));
    }

    private MirrorStateWriteOutcomeRunEvidence completeReadWrite(
            String runId) {
        MirrorSessionPayload initial =
                sessionContext.payload();
        MirrorSessionPayload terminal =
                sessionContext.runSession().currentPayload();
        SessionStateSpace initialState = initial.state();
        SessionStateSpace finalState = terminal.state();
        List<MirrorStateTransitionRunEvidence.StatefulBinding>
                bindings = bindingsBySite.values().stream()
                .map(Binding::toV2).toList();
        List<MirrorStateTransitionRunEvidence.StateAccess>
                accesses = reads.values().stream()
                .map(ReadObservation::toV2).toList();
        List<MirrorStateWriteOutcomeRunEvidence.StateWriteAttempt>
                writes = writeAttempts.entrySet().stream()
                .map(entry -> toV3(
                        entry.getKey(),
                        bindingsBySite.get(
                                entry.getKey()
                                        .invocationSiteId()),
                        entry.getValue()))
                .toList();
        List<String> limitations = writes.stream()
                .anyMatch(attempt -> attempt.outcome()
                        == MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMIT_OUTCOME_UNKNOWN)
                ? List.of(
                MirrorStateWriteOutcomeRunEvidence
                        .UNKNOWN_OUTCOME_LIMITATION)
                : List.of();
        return MirrorStateWriteOutcomeRunEvidenceIntegrity.seal(
                mapper,
                new MirrorStateWriteOutcomeRunEvidence(
                        MirrorStateWriteOutcomeRunEvidence
                                .SCHEMA_VERSION,
                        "", runId, plan.planFingerprint(),
                        stateRef(initialState),
                        stateRef(finalState),
                        StateModelIntegrity.reference(
                                initial.stateModel()),
                        initialState.stateRevision(),
                        finalState.stateRevision(),
                        initialState.worldFingerprint(),
                        finalState.worldFingerprint(),
                        initialState.logicalClock(),
                        finalState.logicalClock(),
                        MirrorStateWriteOutcomeRunEvidence.Mode
                                .SERIALIZABLE_READ_WRITE_OUTCOMES,
                        bindings, accesses, writes,
                        limitations));
    }

    private static MirrorStateWriteOutcomeRunEvidence
            .StateWriteAttempt toV3(
            Coordinate coordinate,
            Binding binding,
            WriteObservation observation) {
        MirrorStateWriteAttemptObservation value =
                observation.attempt();
        return new MirrorStateWriteOutcomeRunEvidence
                .StateWriteAttempt(
                coordinate.invocationSiteId(),
                binding.planBinding().graphPath(),
                coordinate.correlationKey(),
                coordinate.occurrence(),
                coordinate.attempt(),
                binding.capabilityRef(),
                value.writeEffectRef(),
                value.observedStateRef(),
                value.observedStateRevision(),
                value.observedWorldFingerprint(),
                value.observedLogicalClock(),
                observation.requestFingerprint(),
                value.outcome(), value.stage(),
                value.stateDisposition(),
                value.retryable(), value.errorCode(),
                value.errorType(),
                value.failureFingerprint(),
                value.protocolTransition(
                        coordinate.invocationSiteId(),
                        binding.planBinding().graphPath(),
                        coordinate.correlationKey(),
                        coordinate.occurrence(),
                        coordinate.attempt(),
                        binding.capabilityRef(),
                        observation.requestFingerprint()));
    }

    private MirrorResolver.SessionContext requireContext(
            MirrorResolver.Request request) {
        Objects.requireNonNull(request, "request");
        MirrorResolver.SessionContext current =
                request.sessionContext();
        if (current == null
                || current != sessionContext) {
            throw new IllegalArgumentException(
                    "state observation differs from the run Session context");
        }
        return current;
    }

    private void ensureOpen() {
        if (completed.get()) {
            throw new IllegalStateException(
                    "mirror state access journal is already complete");
        }
    }

    private static MirrorArtifactRef stateRef(
            SessionStateSpace state) {
        return new MirrorArtifactRef(
                "SESSION_STATE", state.sessionId(),
                Math.addExact(state.stateRevision(), 1),
                state.fingerprint());
    }

    private record Binding(
            MirrorPlan.ExternalBinding planBinding,
            MirrorArtifactRef capabilityRef,
            StateReadSpec readSpec,
            WriteEffectSpec writeEffect
    ) {
        private MirrorStateTransitionRunEvidence
                .StatefulBinding toV2() {
            return new MirrorStateTransitionRunEvidence
                    .StatefulBinding(
                    planBinding.invocationSiteId(),
                    planBinding.graphPath(),
                    capabilityRef,
                    readSpec == null
                            ? MirrorStateTransitionRunEvidence
                            .Interaction.WRITE
                            : MirrorStateTransitionRunEvidence
                            .Interaction.READ,
                    readSpec == null ? null
                            : StateReadSpecIntegrity
                            .reference(readSpec),
                    writeEffect == null ? null
                            : WriteEffectSpecIntegrity
                            .reference(writeEffect));
        }
    }

    private record ReadObservation(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            String requestFingerprint,
            Binding binding,
            MirrorArtifactRef observedStateRef,
            long observedStateRevision,
            String observedWorldFingerprint,
            Instant observedLogicalClock,
            String businessKeyFingerprint,
            MirrorStateRunEvidence.AccessOutcome outcome,
            String stateRecordFingerprint,
            String projectedOutputFingerprint,
            String errorCode
    ) {
        private MirrorStateRunEvidence.StateAccess toV1() {
            return new MirrorStateRunEvidence.StateAccess(
                    invocationSiteId, graphPath,
                    correlationKey,
                    occurrence, attempt,
                    binding.capabilityRef(),
                    StateReadSpecIntegrity.reference(
                            binding.readSpec()),
                    requestFingerprint,
                    businessKeyFingerprint, outcome,
                    stateRecordFingerprint,
                    projectedOutputFingerprint, errorCode);
        }

        private MirrorStateTransitionRunEvidence
                .StateAccess toV2() {
            return new MirrorStateTransitionRunEvidence
                    .StateAccess(
                    invocationSiteId, graphPath,
                    correlationKey,
                    occurrence, attempt,
                    binding.capabilityRef(),
                    StateReadSpecIntegrity.reference(
                            binding.readSpec()),
                    observedStateRef,
                    observedStateRevision,
                    observedWorldFingerprint,
                    observedLogicalClock,
                    requestFingerprint,
                    businessKeyFingerprint,
                    MirrorStateTransitionRunEvidence
                            .AccessOutcome.valueOf(
                                    outcome.name()),
                    stateRecordFingerprint,
                    projectedOutputFingerprint, errorCode);
        }
    }

    private record WriteObservation(
            String requestFingerprint,
            MirrorStateWriteAttemptObservation attempt
    ) {
        private WriteObservation {
            requestFingerprint = Objects.requireNonNull(
                    requestFingerprint,
                    "requestFingerprint");
            attempt = Objects.requireNonNull(
                    attempt, "attempt");
        }
    }

    private record Coordinate(
            String invocationSiteId,
            String correlationKey,
            int occurrence,
            int attempt
    ) {
        private static Coordinate from(
                MirrorResolver.Request request) {
            return new Coordinate(
                    request.site().invocationSiteId(),
                    request.site().correlationKey(),
                    request.occurrence(),
                    request.attempt());
        }
    }
}
