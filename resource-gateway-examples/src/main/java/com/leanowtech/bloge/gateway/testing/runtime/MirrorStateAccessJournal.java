package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionPayload;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionProtocolIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidenceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpec;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpecIntegrity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concurrent run-scoped journal that closes Session resolver observations into state evidence.
 *
 * <p>The constructor freezes the complete state-backed binding closure from the sealed plan and
 * Session payload. Runtime callbacks can add only payload-free access facts for those bindings.
 * Completion is one-shot, rejects duplicate invocation coordinates, and returns a canonically
 * sealed {@link MirrorStateRunEvidence} value.</p>
 */
public final class MirrorStateAccessJournal
        implements MirrorStateAccessObserver {
    private final ObjectMapper mapper;
    private final MirrorPlan plan;
    private final MirrorResolver.SessionContext sessionContext;
    private final Map<String, MirrorStateRunEvidence.StatefulBinding>
            bindingsBySite;
    private final ConcurrentHashMap<Coordinate,
            MirrorStateRunEvidence.StateAccess> accesses =
            new ConcurrentHashMap<>();
    private final AtomicBoolean completed = new AtomicBoolean();

    /**
     * Creates one exact state journal before the independent engine starts.
     *
     * @param mapper canonical protocol mapper
     * @param plan sealed mirror plan
     * @param sessionContext immutable Session state head and site bindings
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
        LinkedHashMap<String,
                MirrorStateRunEvidence.StatefulBinding> bindings =
                new LinkedHashMap<>();
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
            List<StateReadSpec> matches = payload.stateReadSpecs().stream()
                    .filter(spec -> spec.targetCapabilityRef().equals(
                            capability))
                    .toList();
            if (matches.size() != 1
                    || matches.getFirst().lifecycle()
                    != CapabilitySnapshot.Lifecycle.ACTIVE) {
                throw new IllegalArgumentException(
                        "state journal requires one active read spec per stateful site");
            }
            StateReadSpec spec = matches.getFirst();
            StateReadSpecIntegrity.verify(
                    mapper, spec, payload.stateModel());
            MirrorStateRunEvidence.StatefulBinding stateful =
                    new MirrorStateRunEvidence.StatefulBinding(
                            binding.invocationSiteId(),
                            binding.graphPath(), capability,
                            StateReadSpecIntegrity.reference(spec));
            if (bindings.put(
                    binding.invocationSiteId(), stateful) != null) {
                throw new IllegalArgumentException(
                        "state journal binding sites must be unique");
            }
        }
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "state journal requires a state-backed plan binding");
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
        if (completed.get()) {
            throw new IllegalStateException(
                    "mirror state access journal is already complete");
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(spec, "spec");
        MirrorStateRunEvidence.StatefulBinding binding =
                bindingsBySite.get(request.site().invocationSiteId());
        if (binding == null
                || !binding.stateReadSpecRef().equals(
                StateReadSpecIntegrity.reference(spec))
                || request.sessionContext() == null
                || !request.sessionContext().payload().fingerprint()
                .equals(sessionContext.payload().fingerprint())) {
            throw new IllegalArgumentException(
                    "state access observation differs from the frozen journal binding");
        }
        String errorCode =
                outcome == MirrorStateRunEvidence.AccessOutcome.TOMBSTONED
                        ? MirrorStateRunEvidence.MirrorSessionStateError
                        .ENTITY_TOMBSTONED : "";
        MirrorStateRunEvidence.StateAccess access =
                new MirrorStateRunEvidence.StateAccess(
                        request.site().invocationSiteId(),
                        request.site().graphPath(),
                        request.site().correlationKey(),
                        request.occurrence(), request.attempt(),
                        binding.capabilityRef(),
                        binding.stateReadSpecRef(),
                        request.requestFingerprint(),
                        businessKeyFingerprint, outcome,
                        stateRecordFingerprint,
                        projectedOutputFingerprint, errorCode);
        Coordinate coordinate = Coordinate.from(access);
        if (accesses.putIfAbsent(coordinate, access) != null) {
            throw new IllegalStateException(
                    "duplicate mirror state access coordinate");
        }
    }

    /**
     * Closes and seals the complete payload-free state evidence value.
     *
     * @param runId terminal mirror run identity
     * @return sealed state evidence
     */
    public MirrorStateRunEvidence complete(String runId) {
        if (!completed.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "mirror state access journal is already complete");
        }
        SessionStateSpace state = sessionContext.payload().state();
        MirrorArtifactRef sessionStateRef = new MirrorArtifactRef(
                "SESSION_STATE", state.sessionId(),
                Math.addExact(state.stateRevision(), 1),
                state.fingerprint());
        MirrorStateRunEvidence evidence =
                new MirrorStateRunEvidence(
                        MirrorStateRunEvidence.SCHEMA_VERSION, "",
                        runId, plan.planFingerprint(),
                        sessionStateRef,
                        StateModelIntegrity.reference(
                                sessionContext.payload().stateModel()),
                        state.stateRevision(),
                        state.worldFingerprint(),
                        state.logicalClock(),
                        MirrorStateRunEvidence.Mode.READ_ONLY_SNAPSHOT,
                        List.copyOf(bindingsBySite.values()),
                        List.copyOf(accesses.values()), List.of());
        return MirrorStateRunEvidenceIntegrity.seal(mapper, evidence);
    }

    /** @return number of unique state accesses observed so far */
    public int size() {
        return accesses.size();
    }

    private record Coordinate(
            String invocationSiteId,
            String correlationKey,
            int occurrence,
            int attempt
    ) {
        private static Coordinate from(
                MirrorStateRunEvidence.StateAccess access) {
            return new Coordinate(
                    access.invocationSiteId(),
                    access.correlationKey(),
                    access.occurrence(), access.attempt());
        }
    }
}
