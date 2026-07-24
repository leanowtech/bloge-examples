package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationRunTrust;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateRunEvidenceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateTransitionRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateTransitionRunEvidenceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateModelIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.StateReadSpecIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects shared test-kernel evidence into the portable payload-free mirror evidence protocol.
 *
 * <p>The projector hashes each node input/output, attempt input/output, edge value, and detached
 * request context independently. It also proves an exact closure between external delegate
 * attempts and sealed {@link MirrorResolution} values before a signing authority may see the
 * result. Protected runs also cross-check their payload-free occurrence-budget snapshot and expose
 * exhaustion as a stable evidence limitation. Internal test metadata, diagnostics, assertions,
 * and business payloads are never copied into the portable protocol.</p>
 */
public final class MirrorRunEvidenceProjector {
    /** Maximum canonical business value admitted to one payload fingerprint. */
    public static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024 * 1024;
    /** Current honest limitation until a deployment isolation attestation is integrated. */
    public static final String DEPLOYMENT_EGRESS_NOT_ATTESTED =
            "DEPLOYMENT_EGRESS_NOT_ATTESTED";
    /** Current limitation when the shared test kernel already classified evidence as exploratory. */
    public static final String SHARED_TEST_EVIDENCE_EXPLORATORY =
            "SHARED_TEST_EVIDENCE_EXPLORATORY";

    private final ObjectMapper mapper;

    /** @param mapper canonical protocol mapper used for every payload fingerprint */
    public MirrorRunEvidenceProjector(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Creates one complete payload-free value from an admitted mirror execution.
     *
     * <p>This compatibility overload accepts only legacy, non-budgeted evidence. Budgeted evidence
     * must use the overload that supplies the run-scoped snapshot so callers cannot bypass the
     * terminal counter cross-check.</p>
     *
     * @param request exact authenticated mirror request
     * @param execution terminal shared-kernel result
     * @param resolutions sealed resolver provenance completed with the terminal run id
     * @param engineConfiguration structural independent-engine facts
     * @return unsigned portable evidence ready for the integrity boundary
     */
    public MirrorRunEvidence project(
            MirrorRunRequest request,
            TestExecutionResult execution,
            List<MirrorResolution> resolutions,
            IndependentTestEngineFactory.Configuration engineConfiguration) {
        if (execution != null && execution.evidence() != null
                && execution.evidence().metadata()
                .containsKey(MirrorInvocationBudget.EVIDENCE_METADATA_KEY)) {
            throw new IllegalArgumentException(
                    "runtime invocation budget snapshot is required for budgeted mirror evidence");
        }
        return projectInternal(
                request, execution, resolutions, engineConfiguration,
                null, null, null);
    }

    /**
     * Creates portable evidence and verifies the protected runtime's occurrence-budget snapshot.
     *
     * @param request exact authenticated mirror request
     * @param execution terminal shared-kernel result
     * @param resolutions sealed resolver provenance completed with the terminal run id
     * @param engineConfiguration structural independent-engine facts
     * @param invocationBudget payload-free runtime occurrence counters
     * @return unsigned portable evidence ready for the integrity boundary
     */
    public MirrorRunEvidence project(
            MirrorRunRequest request,
            TestExecutionResult execution,
            List<MirrorResolution> resolutions,
            IndependentTestEngineFactory.Configuration engineConfiguration,
            MirrorInvocationBudget.Snapshot invocationBudget) {
        return projectInternal(request, execution, resolutions, engineConfiguration,
                Objects.requireNonNull(invocationBudget, "invocationBudget"),
                null, null);
    }

    /**
     * Creates portable evidence with invocation-budget and double-observed deployment trust.
     *
     * @param request exact authenticated mirror request
     * @param execution terminal shared-kernel result
     * @param resolutions sealed resolver provenance
     * @param engineConfiguration structural independent-engine facts
     * @param invocationBudget payload-free runtime occurrence counters
     * @param deploymentTrust exact trust binding confirmed after execution
     * @return unsigned v2 evidence ready for the integrity boundary
     */
    public MirrorRunEvidence project(
            MirrorRunRequest request,
            TestExecutionResult execution,
            List<MirrorResolution> resolutions,
            IndependentTestEngineFactory.Configuration engineConfiguration,
            MirrorInvocationBudget.Snapshot invocationBudget,
            MirrorDeploymentIsolationRunTrust.Binding deploymentTrust) {
        return projectInternal(request, execution, resolutions, engineConfiguration,
                Objects.requireNonNull(invocationBudget, "invocationBudget"),
                Objects.requireNonNull(deploymentTrust, "deploymentTrust"),
                null);
    }

    /**
     * Creates v3 or v4 portable evidence with one complete payload-free Session closure.
     *
     * @param request exact authenticated stateful mirror request
     * @param execution terminal shared-kernel result
     * @param resolutions sealed resolver provenance
     * @param engineConfiguration structural independent-engine facts
     * @param invocationBudget payload-free runtime occurrence counters
     * @param deploymentTrust exact trust binding confirmed after execution, or {@code null}
     * @param stateEvidence sealed payload-free Session state evidence
     * @return unsigned v3 read-only or v4 read/write evidence ready for the integrity boundary
     */
    public MirrorRunEvidence projectStateful(
            MirrorRunRequest request,
            TestExecutionResult execution,
            List<MirrorResolution> resolutions,
            IndependentTestEngineFactory.Configuration engineConfiguration,
            MirrorInvocationBudget.Snapshot invocationBudget,
            MirrorDeploymentIsolationRunTrust.Binding deploymentTrust,
            MirrorStateEvidence stateEvidence) {
        return projectInternal(
                request, execution, resolutions, engineConfiguration,
                Objects.requireNonNull(invocationBudget, "invocationBudget"),
                deploymentTrust,
                Objects.requireNonNull(stateEvidence, "stateEvidence"));
    }

    private MirrorRunEvidence projectInternal(
            MirrorRunRequest request,
            TestExecutionResult execution,
            List<MirrorResolution> resolutions,
            IndependentTestEngineFactory.Configuration engineConfiguration,
            MirrorInvocationBudget.Snapshot invocationBudget,
            MirrorDeploymentIsolationRunTrust.Binding deploymentTrust,
            MirrorStateEvidence stateEvidence) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(execution, "execution");
        TestRunEvidence source = Objects.requireNonNull(execution.evidence(), "execution.evidence");
        MirrorPlan plan = request.compiledPlan().plan();
        validateSource(plan, execution, source);
        if (invocationBudget != null) {
            validateInvocationBudget(plan, source, invocationBudget);
        }
        List<MirrorResolution> exactResolutions = resolutions == null
                ? List.of() : List.copyOf(resolutions);
        validateResolutionClosure(plan, source.nodeTrace(), exactResolutions);
        validateStateClosure(
                request, source.nodeTrace(), exactResolutions,
                stateEvidence);
        IndependentTestEngineFactory.Configuration configuration = Objects.requireNonNull(
                engineConfiguration, "engineConfiguration");

        List<String> isolationLimitations = deploymentTrust == null
                ? List.of(DEPLOYMENT_EGRESS_NOT_ATTESTED) : List.of();
        MirrorRunEvidence.IsolationFacts isolation = new MirrorRunEvidence.IsolationFacts(
                MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                configuration.interceptorTypes(), configuration.listenerTypes(),
                configuration.durableStores(), configuration.productionContextCarriers(),
                configuration.productionExtensionListeners(),
                plan.policy().realExternalCallsAllowed(),
                plan.policy().externalCredentialsAllowed(),
                plan.policy().networkEgressAllowed(), deploymentTrust != null,
                deploymentTrust == null ? null : deploymentTrust.attestationRef(),
                deploymentTrust, isolationLimitations);
        Set<String> limitations = new LinkedHashSet<>(isolationLimitations);
        if (source.evidenceClass() == TestRunEvidence.EvidenceClass.EXPLORATORY) {
            limitations.add(SHARED_TEST_EVIDENCE_EXPLORATORY);
        }
        if (invocationBudget != null && invocationBudget.exhausted()) {
            limitations.add(MirrorInvocationBudget.EXHAUSTED_LIMITATION);
        }
        MirrorRunEvidence.EvidenceClass evidenceClass = deploymentTrust != null
                && source.evidenceClass() == TestRunEvidence.EvidenceClass.CERTIFIABLE
                ? MirrorRunEvidence.EvidenceClass.CERTIFIABLE
                : MirrorRunEvidence.EvidenceClass.EXPLORATORY;
        String schemaVersion = switch (stateEvidence) {
            case null -> MirrorRunEvidence.SCHEMA_VERSION;
            case MirrorStateRunEvidence ignored ->
                    MirrorRunEvidence.STATEFUL_SCHEMA_VERSION;
            case MirrorStateTransitionRunEvidence ignored ->
                    MirrorRunEvidence.READ_WRITE_SCHEMA_VERSION;
        };
        return new MirrorRunEvidence(schemaVersion, source.runId(), request.requestId(),
                fingerprint(request.context().asMap()), plan.planId(), plan.planFingerprint(),
                plan.capabilityClosureFingerprint(), plan.executionControlFingerprint(),
                plan.rootCapability(), plan.fixtureBundleRef(), projectBindings(plan), plan.scope(),
                plan.policy().authorizedPurpose(), MirrorRunEvidence.Status.valueOf(source.status().name()),
                evidenceClass,
                source.semanticResultFingerprint(), source.startedAt(), source.completedAt(),
                projectNodes(source.nodeTrace()), projectEdges(source.edgeTrace()), exactResolutions,
                stateEvidence, isolation, List.copyOf(limitations));
    }

    private void validateStateClosure(
            MirrorRunRequest request,
            List<TestRunEvidence.NodeTrace> nodes,
            List<MirrorResolution> resolutions,
            MirrorStateEvidence stateEvidence) {
        if (stateEvidence == null) {
            if (request.sessionContext() != null) {
                throw new IllegalArgumentException(
                        "Session-backed execution requires state evidence");
            }
            return;
        }
        switch (stateEvidence) {
            case MirrorStateRunEvidence readOnly ->
                    validateReadOnlyStateClosure(
                            request, nodes, resolutions, readOnly);
            case MirrorStateTransitionRunEvidence readWrite ->
                    validateReadWriteStateClosure(
                            request, nodes, resolutions, readWrite);
        }
    }

    private void validateReadOnlyStateClosure(
            MirrorRunRequest request,
            List<TestRunEvidence.NodeTrace> nodes,
            List<MirrorResolution> resolutions,
            MirrorStateRunEvidence stateEvidence) {
        MirrorResolver.SessionContext context =
                Objects.requireNonNull(
                        request.sessionContext(), "sessionContext");
        MirrorStateRunEvidenceIntegrity.verify(mapper, stateEvidence);
        var state = context.payload().state();
        if (!request.compiledPlan().plan().planFingerprint()
                .equals(stateEvidence.planFingerprint())
                || !state.fingerprint().equals(
                stateEvidence.sessionStateRef().fingerprint())
                || state.stateRevision() != stateEvidence.stateRevision()
                || !state.worldFingerprint().equals(
                stateEvidence.worldFingerprint())
                || !state.logicalClock().equals(
                stateEvidence.logicalClock())
                || !StateModelIntegrity.reference(
                context.payload().stateModel()).equals(
                stateEvidence.stateModelRef())) {
            throw new IllegalArgumentException(
                    "state evidence differs from the frozen Session head");
        }

        Map<String, MirrorStateRunEvidence.StatefulBinding>
                stateBindings = new LinkedHashMap<>();
        stateEvidence.statefulBindings().forEach(binding ->
                stateBindings.put(binding.invocationSiteId(), binding));
        Map<String, MirrorPlan.ExternalBinding> expectedBindings =
                new LinkedHashMap<>();
        for (MirrorPlan.ExternalBinding binding
                : request.compiledPlan().plan().externalBindings()) {
            if (binding.resolverOrder().contains(
                    MirrorPlan.MirrorSource.SESSION_STATE)) {
                expectedBindings.put(
                        binding.invocationSiteId(), binding);
            }
        }
        if (!expectedBindings.keySet().equals(
                stateBindings.keySet())) {
            throw new IllegalArgumentException(
                    "state evidence binding closure differs from the plan");
        }
        expectedBindings.forEach((site, binding) -> {
            MirrorStateRunEvidence.StatefulBinding stateBinding =
                    stateBindings.get(site);
            if (!binding.capabilityRef().equals(
                    stateBinding.capabilityRef())
                    || !binding.graphPath().equals(
                    stateBinding.graphPath())
                    || context.payload().stateReadSpecs().stream()
                    .filter(spec -> spec.targetCapabilityRef().equals(
                            binding.capabilityRef()))
                    .map(StateReadSpecIntegrity::reference)
                    .noneMatch(stateBinding.stateReadSpecRef()::equals)) {
                throw new IllegalArgumentException(
                        "state evidence binding differs from its exact read spec");
            }
        });

        Map<Coordinate, AttemptProjection> statefulAttempts =
                new LinkedHashMap<>();
        for (TestRunEvidence.NodeTrace node : nodes) {
            if (!stateBindings.containsKey(
                    node.invocationSiteId())) {
                continue;
            }
            for (TestRunEvidence.AttemptTrace attempt
                    : node.attempts()) {
                Coordinate coordinate = new Coordinate(
                        node.invocationSiteId(),
                        node.correlationKey(),
                        node.occurrence(), attempt.attempt());
                statefulAttempts.put(coordinate,
                        new AttemptProjection(
                                expectedBindings.get(
                                        node.invocationSiteId()),
                                fingerprint(attempt.input()),
                                fingerprint(attempt.output())));
            }
        }
        Map<Coordinate, MirrorResolution> resolutionByCoordinate =
                new LinkedHashMap<>();
        resolutions.forEach(resolution ->
                resolutionByCoordinate.put(
                        Coordinate.from(resolution), resolution));
        Map<Coordinate, MirrorStateRunEvidence.StateAccess>
                accessByCoordinate = new LinkedHashMap<>();
        stateEvidence.accesses().forEach(access -> {
            Coordinate coordinate = new Coordinate(
                    access.invocationSiteId(),
                    access.correlationKey(),
                    access.occurrence(), access.attempt());
            if (accessByCoordinate.put(coordinate, access)
                    != null) {
                throw new IllegalArgumentException(
                        "state evidence contains duplicate access coordinates");
            }
        });
        if (!statefulAttempts.keySet().equals(
                accessByCoordinate.keySet())) {
            throw new IllegalArgumentException(
                    "state evidence access closure differs from executed stateful attempts");
        }
        statefulAttempts.forEach((coordinate, attempt) -> {
            MirrorStateRunEvidence.StateAccess access =
                    accessByCoordinate.get(coordinate);
            MirrorResolution resolution =
                    resolutionByCoordinate.get(coordinate);
            if (resolution == null
                    || !attempt.requestFingerprint().equals(
                    access.requestFingerprint())
                    || !access.requestFingerprint().equals(
                    resolution.requestFingerprint())) {
                throw new IllegalArgumentException(
                        "state access request differs from its delegate attempt");
            }
            switch (access.outcome()) {
                case LIVE_ENTITY -> {
                    if (resolution.source()
                            != MirrorPlan.MirrorSource.SESSION_STATE
                            || !access.projectedOutputFingerprint().equals(
                            resolution.outputFingerprint())
                            || !attempt.outputFingerprint().equals(
                            access.projectedOutputFingerprint())) {
                        throw new IllegalArgumentException(
                                "live state access differs from its final resolution");
                    }
                    requireStateArtifacts(
                            stateEvidence, access, resolution);
                }
                case TOMBSTONED -> {
                    if (resolution.source()
                            != MirrorPlan.MirrorSource.SESSION_STATE
                            || resolution.error() == null
                            || !access.errorCode().equals(
                            resolution.error().code())) {
                        throw new IllegalArgumentException(
                                "tombstoned state access differs from its terminal resolution");
                    }
                    requireStateArtifacts(
                            stateEvidence, access, resolution);
                }
                case ABSENT -> {
                    if (resolution.source()
                            == MirrorPlan.MirrorSource.SESSION_STATE) {
                        throw new IllegalArgumentException(
                                "absent state access cannot select the Session resolver");
                    }
                }
            }
        });
    }

    private static void requireStateArtifacts(
            MirrorStateRunEvidence stateEvidence,
            MirrorStateRunEvidence.StateAccess access,
            MirrorResolution resolution) {
        if (!resolution.matchedArtifactRefs().contains(
                stateEvidence.sessionStateRef())
                || !resolution.matchedArtifactRefs().contains(
                stateEvidence.stateModelRef())
                || !resolution.matchedArtifactRefs().contains(
                access.stateReadSpecRef())) {
            throw new IllegalArgumentException(
                    "Session state resolution lacks exact state provenance");
        }
    }

    private void validateReadWriteStateClosure(
            MirrorRunRequest request,
            List<TestRunEvidence.NodeTrace> nodes,
            List<MirrorResolution> resolutions,
            MirrorStateTransitionRunEvidence stateEvidence) {
        MirrorResolver.SessionContext context =
                Objects.requireNonNull(
                        request.sessionContext(), "sessionContext");
        if (context.runSession() == null) {
            throw new IllegalArgumentException(
                    "read/write state evidence requires a run-scoped write boundary");
        }
        MirrorStateTransitionRunEvidenceIntegrity.verify(
                mapper, stateEvidence);
        var initial = context.payload().state();
        var terminal = context.currentPayload().state();
        if (!request.compiledPlan().plan().planFingerprint()
                .equals(stateEvidence.planFingerprint())
                || !initial.fingerprint().equals(
                stateEvidence.sessionStateRef().fingerprint())
                || initial.stateRevision()
                != stateEvidence.stateRevision()
                || !initial.worldFingerprint().equals(
                stateEvidence.worldFingerprint())
                || !initial.logicalClock().equals(
                stateEvidence.logicalClock())
                || !terminal.fingerprint().equals(
                stateEvidence.finalSessionStateRef().fingerprint())
                || terminal.stateRevision()
                != stateEvidence.finalStateRevision()
                || !terminal.worldFingerprint().equals(
                stateEvidence.finalWorldFingerprint())
                || !terminal.logicalClock().equals(
                stateEvidence.finalLogicalClock())
                || !StateModelIntegrity.reference(
                context.payload().stateModel()).equals(
                stateEvidence.stateModelRef())) {
            throw new IllegalArgumentException(
                    "state transition evidence differs from the run Session heads");
        }

        Map<String, MirrorStateTransitionRunEvidence.StatefulBinding>
                stateBindings = new LinkedHashMap<>();
        stateEvidence.statefulBindings().forEach(binding ->
                stateBindings.put(
                        binding.invocationSiteId(), binding));
        Map<String, MirrorPlan.ExternalBinding> expectedBindings =
                new LinkedHashMap<>();
        request.compiledPlan().plan().externalBindings()
                .stream()
                .filter(binding -> binding.resolverOrder()
                        .contains(MirrorPlan.MirrorSource
                                .SESSION_STATE))
                .forEach(binding -> expectedBindings.put(
                        binding.invocationSiteId(), binding));
        if (!expectedBindings.keySet().equals(
                stateBindings.keySet())) {
            throw new IllegalArgumentException(
                    "state transition binding closure differs from the plan");
        }
        expectedBindings.forEach((site, binding) -> {
            MirrorStateTransitionRunEvidence.StatefulBinding
                    stateBinding = stateBindings.get(site);
            boolean exactIdentity =
                    binding.capabilityRef().equals(
                            stateBinding.capabilityRef())
                            && binding.graphPath().equals(
                            stateBinding.graphPath());
            boolean exactLowering =
                    switch (stateBinding.interaction()) {
                        case READ ->
                                context.payload().stateReadSpecs()
                                        .stream()
                                        .filter(spec -> spec
                                                .targetCapabilityRef()
                                                .equals(binding
                                                        .capabilityRef()))
                                        .map(StateReadSpecIntegrity
                                                ::reference)
                                        .anyMatch(
                                                stateBinding
                                                        .stateReadSpecRef()
                                                        ::equals);
                        case WRITE ->
                                context.payload().writeEffects()
                                        .stream()
                                        .filter(effect -> effect
                                                .targetCapabilityRef()
                                                .equals(binding
                                                        .capabilityRef()))
                                        .map(WriteEffectSpecIntegrity
                                                ::reference)
                                        .anyMatch(
                                                stateBinding
                                                        .writeEffectRef()
                                                        ::equals);
                    };
            if (!exactIdentity || !exactLowering) {
                throw new IllegalArgumentException(
                        "state transition binding differs from its exact interaction spec");
            }
        });

        Map<Coordinate, AttemptProjection> statefulAttempts =
                statefulAttempts(
                        nodes, stateBindings.keySet(),
                        expectedBindings);
        Map<Coordinate, MirrorResolution> resolutionByCoordinate =
                new LinkedHashMap<>();
        resolutions.forEach(resolution -> {
            Coordinate coordinate =
                    Coordinate.from(resolution);
            if (resolutionByCoordinate.put(
                    coordinate, resolution) != null) {
                throw new IllegalArgumentException(
                        "state transition resolution coordinates must be unique");
            }
        });
        Map<Coordinate, MirrorStateTransitionRunEvidence.StateAccess>
                reads = new LinkedHashMap<>();
        stateEvidence.accesses().forEach(access -> {
            Coordinate coordinate = coordinate(
                    access.invocationSiteId(),
                    access.correlationKey(),
                    access.occurrence(), access.attempt());
            if (reads.put(coordinate, access) != null) {
                throw new IllegalArgumentException(
                        "state transition evidence contains duplicate read coordinates");
            }
        });
        Map<Coordinate, MirrorStateTransitionRunEvidence.StateTransition>
                writes = new LinkedHashMap<>();
        stateEvidence.transitions().forEach(transition -> {
            Coordinate coordinate = coordinate(
                    transition.invocationSiteId(),
                    transition.correlationKey(),
                    transition.occurrence(),
                    transition.attempt());
            if (writes.put(coordinate, transition) != null
                    || reads.containsKey(coordinate)) {
                throw new IllegalArgumentException(
                        "state transition evidence contains duplicate interaction coordinates");
            }
        });
        Set<Coordinate> interactions =
                new LinkedHashSet<>(reads.keySet());
        interactions.addAll(writes.keySet());
        if (!statefulAttempts.keySet().equals(interactions)) {
            throw new IllegalArgumentException(
                    "state interaction closure differs from executed stateful attempts");
        }

        statefulAttempts.forEach((coordinate, attempt) -> {
            MirrorStateTransitionRunEvidence.StatefulBinding
                    binding = stateBindings.get(
                    coordinate.invocationSiteId());
            MirrorResolution resolution =
                    resolutionByCoordinate.get(coordinate);
            if (resolution == null
                    || !attempt.requestFingerprint().equals(
                    resolution.requestFingerprint())) {
                throw new IllegalArgumentException(
                        "state interaction differs from its delegate resolution");
            }
            switch (binding.interaction()) {
                case READ -> validateReadInteraction(
                        attempt, resolution,
                        Objects.requireNonNull(
                                reads.get(coordinate),
                                "state read observation"),
                        stateEvidence);
                case WRITE -> validateWriteInteraction(
                        attempt, resolution,
                        Objects.requireNonNull(
                                writes.get(coordinate),
                                "state transition observation"),
                        stateEvidence);
            }
        });
    }

    private Map<Coordinate, AttemptProjection> statefulAttempts(
            List<TestRunEvidence.NodeTrace> nodes,
            Set<String> statefulSites,
            Map<String, MirrorPlan.ExternalBinding> bindings) {
        Map<Coordinate, AttemptProjection> attempts =
                new LinkedHashMap<>();
        for (TestRunEvidence.NodeTrace node : nodes) {
            if (!statefulSites.contains(
                    node.invocationSiteId())) {
                continue;
            }
            MirrorPlan.ExternalBinding binding =
                    bindings.get(node.invocationSiteId());
            if (!binding.graphPath().equals(
                    node.graphPath())) {
                throw new IllegalArgumentException(
                        "stateful node graph path differs from its binding");
            }
            for (TestRunEvidence.AttemptTrace attempt
                    : node.attempts()) {
                Coordinate coordinate = coordinate(
                        node.invocationSiteId(),
                        node.correlationKey(),
                        node.occurrence(), attempt.attempt());
                AttemptProjection projection =
                        new AttemptProjection(
                                binding,
                                fingerprint(attempt.input()),
                                fingerprint(attempt.output()));
                if (attempts.put(
                        coordinate, projection) != null) {
                    throw new IllegalArgumentException(
                            "stateful delegate attempt coordinates must be unique");
                }
            }
        }
        return attempts;
    }

    private static Coordinate coordinate(
            String invocationSiteId,
            String correlationKey,
            int occurrence,
            int attempt) {
        return new Coordinate(
                invocationSiteId, correlationKey,
                occurrence, attempt);
    }

    private static void validateReadInteraction(
            AttemptProjection attempt,
            MirrorResolution resolution,
            MirrorStateTransitionRunEvidence.StateAccess access,
            MirrorStateTransitionRunEvidence stateEvidence) {
        if (!attempt.requestFingerprint().equals(
                access.requestFingerprint())) {
            throw new IllegalArgumentException(
                    "state read differs from its exact request");
        }
        switch (access.outcome()) {
            case LIVE_ENTITY -> {
                if (resolution.source()
                        != MirrorPlan.MirrorSource.SESSION_STATE
                        || !hasReadStateProvenance(
                        resolution, access, stateEvidence)
                        || !access.projectedOutputFingerprint()
                        .equals(resolution.outputFingerprint())
                        || !attempt.outputFingerprint().equals(
                        access.projectedOutputFingerprint())) {
                    throw new IllegalArgumentException(
                            "live state read differs from its final resolution");
                }
            }
            case TOMBSTONED -> {
                if (resolution.source()
                        != MirrorPlan.MirrorSource.SESSION_STATE
                        || !hasReadStateProvenance(
                        resolution, access, stateEvidence)
                        || resolution.error() == null
                        || !access.errorCode().equals(
                        resolution.error().code())) {
                    throw new IllegalArgumentException(
                            "tombstoned state read differs from its terminal resolution");
                }
            }
            case ABSENT -> {
                if (resolution.source()
                        == MirrorPlan.MirrorSource.SESSION_STATE) {
                    throw new IllegalArgumentException(
                            "absent state read cannot select the Session resolver");
                }
            }
        }
    }

    private static boolean hasReadStateProvenance(
            MirrorResolution resolution,
            MirrorStateTransitionRunEvidence.StateAccess access,
            MirrorStateTransitionRunEvidence stateEvidence) {
        return resolution.matchedArtifactRefs().contains(
                access.observedStateRef())
                && resolution.matchedArtifactRefs().contains(
                stateEvidence.stateModelRef())
                && resolution.matchedArtifactRefs().contains(
                access.stateReadSpecRef());
    }

    private static void validateWriteInteraction(
            AttemptProjection attempt,
            MirrorResolution resolution,
            MirrorStateTransitionRunEvidence.StateTransition transition,
            MirrorStateTransitionRunEvidence stateEvidence) {
        if (!attempt.requestFingerprint().equals(
                transition.requestFingerprint())
                || resolution.source()
                != MirrorPlan.MirrorSource.SESSION_STATE
                || resolution.status()
                != MirrorResolution.Status.RESOLVED
                || !transition.responseFingerprint().equals(
                resolution.outputFingerprint())
                || !attempt.outputFingerprint().equals(
                transition.responseFingerprint())
                || !resolution.matchedArtifactRefs().contains(
                transition.finalStateRef())
                || !resolution.matchedArtifactRefs().contains(
                stateEvidence.stateModelRef())
                || !resolution.matchedArtifactRefs().contains(
                transition.writeEffectRef())
                || !resolution.matchedRuleRefs().contains(
                "transaction-receipt:"
                        + transition.receiptFingerprint())) {
            throw new IllegalArgumentException(
                    "virtual write differs from its exact transition receipt");
        }
    }

    private static void validateInvocationBudget(
            MirrorPlan plan,
            TestRunEvidence source,
            MirrorInvocationBudget.Snapshot budget) {
        if (budget.maximumInvocations() != plan.policy().maximumInvocations()) {
            throw new IllegalArgumentException(
                    "runtime invocation budget differs from the sealed mirror plan");
        }
        Object metadata = source.metadata().get(MirrorInvocationBudget.EVIDENCE_METADATA_KEY);
        if (!(metadata instanceof Map<?, ?> values)
                || integer(values.get("maximumInvocations")) != budget.maximumInvocations()
                || integer(values.get("admittedInvocations")) != budget.admittedInvocations()
                || integer(values.get("rejectedInvocations")) != budget.rejectedInvocations()) {
            throw new IllegalArgumentException(
                    "shared test evidence differs from the runtime invocation budget");
        }
        if (budget.exhausted() && source.status() == TestRunEvidence.Status.PASSED) {
            throw new IllegalArgumentException(
                    "an exhausted invocation budget cannot produce passed evidence");
        }
    }

    private static int integer(Object value) {
        return value instanceof Integer integer ? integer : Integer.MIN_VALUE;
    }

    private static List<MirrorRunEvidence.ExternalBinding> projectBindings(MirrorPlan plan) {
        return plan.externalBindings().stream().map(binding ->
                new MirrorRunEvidence.ExternalBinding(binding.parentCapabilityRef(),
                        binding.dependencyNodeId(), binding.capabilityRef(),
                        binding.invocationSiteId(), binding.graphPath())).toList();
    }

    private void validateSource(
            MirrorPlan plan, TestExecutionResult execution, TestRunEvidence source) {
        if (execution.plan() == null
                || !plan.executionControlFingerprint().equals(execution.plan().planFingerprint())
                || !plan.executionControlFingerprint().equals(source.planFingerprint())
                || !plan.fixtureBundleRef().fingerprint().equals(source.fixtureBundleFingerprint())
                || !plan.policy().authorizedPurpose().equals(source.executionPurpose())
                || source.semanticResultFingerprint().isBlank()
                || !TestSemanticResultFingerprint.matches(mapper, source)) {
            throw new IllegalArgumentException(
                    "shared test evidence does not match the exact mirror generation");
        }
    }

    private void validateResolutionClosure(
            MirrorPlan plan,
            List<TestRunEvidence.NodeTrace> nodes,
            List<MirrorResolution> resolutions) {
        Map<String, MirrorPlan.ExternalBinding> bindings = new LinkedHashMap<>();
        for (MirrorPlan.ExternalBinding binding : plan.externalBindings()) {
            bindings.put(binding.invocationSiteId(), binding);
        }
        Map<Coordinate, AttemptProjection> expected = new LinkedHashMap<>();
        for (TestRunEvidence.NodeTrace node : nodes) {
            MirrorPlan.ExternalBinding binding = bindings.get(node.invocationSiteId());
            if (binding == null) {
                continue;
            }
            if (!binding.graphPath().equals(node.graphPath())) {
                throw new IllegalArgumentException(
                        "external node evidence graph path differs from its mirror binding");
            }
            for (TestRunEvidence.AttemptTrace attempt : node.attempts()) {
                Coordinate coordinate = new Coordinate(node.invocationSiteId(),
                        node.correlationKey(), node.occurrence(), attempt.attempt());
                AttemptProjection projection = new AttemptProjection(binding,
                        fingerprint(attempt.input()), fingerprint(attempt.output()));
                if (expected.put(coordinate, projection) != null) {
                    throw new IllegalArgumentException(
                            "external node evidence contains duplicate attempt coordinates");
                }
            }
        }
        Map<Coordinate, MirrorResolution> actual = new LinkedHashMap<>();
        for (MirrorResolution resolution : resolutions) {
            Coordinate coordinate = Coordinate.from(resolution);
            if (actual.put(coordinate, resolution) != null) {
                throw new IllegalArgumentException(
                        "mirror resolution evidence contains duplicate attempt coordinates");
            }
        }
        if (!expected.keySet().equals(actual.keySet())) {
            throw new IllegalArgumentException(
                    "external delegate attempts and mirror resolutions must form an exact closure");
        }
        expected.forEach((coordinate, attempt) -> {
            MirrorResolution resolution = actual.get(coordinate);
            if (!attempt.binding().capabilityRef().equals(resolution.capabilityRef())
                    || !attempt.binding().graphPath().equals(resolution.graphPath())
                    || !attempt.requestFingerprint().equals(resolution.requestFingerprint())) {
                throw new IllegalArgumentException(
                        "mirror resolution identity differs from its external delegate attempt");
            }
            if (!resolution.outputFingerprint().isBlank()
                    && !attempt.outputFingerprint().equals(resolution.outputFingerprint())) {
                throw new IllegalArgumentException(
                        "mirror resolution output differs from its external delegate attempt");
            }
        });
    }

    private List<MirrorRunEvidence.NodeTrace> projectNodes(
            List<TestRunEvidence.NodeTrace> values) {
        List<MirrorRunEvidence.NodeTrace> result = new ArrayList<>(values.size());
        for (TestRunEvidence.NodeTrace value : values) {
            List<MirrorRunEvidence.AttemptTrace> attempts = value.attempts().stream()
                    .map(attempt -> new MirrorRunEvidence.AttemptTrace(attempt.attempt(),
                            attempt.status(), attempt.fidelity(), fingerprint(attempt.input()),
                            fingerprint(attempt.output()), attempt.errorCode(), attempt.durationMs()))
                    .toList();
            result.add(new MirrorRunEvidence.NodeTrace(value.nodeId(), value.operatorRef(),
                    value.status(), value.fidelity(), fingerprint(value.input()),
                    fingerprint(value.output()), value.errorCode(), value.durationMs(),
                    value.invocationSiteId(), value.graphPath(), value.correlationKey(),
                    value.occurrence(), value.graphOccurrence(), attempts));
        }
        return result;
    }

    private List<MirrorRunEvidence.EdgeTrace> projectEdges(
            List<TestRunEvidence.EdgeTrace> values) {
        return values.stream().map(value -> new MirrorRunEvidence.EdgeTrace(value.edgeId(),
                value.status(), fingerprint(value.value()), value.graphPath(),
                value.correlationKey(), value.graphOccurrence(), value.fromInvocationSiteId(),
                value.toInvocationSiteId())).toList();
    }

    private String fingerprint(Object value) {
        return ProtocolFingerprint.ofBounded(mapper, value, MAXIMUM_PAYLOAD_BYTES);
    }

    private record Coordinate(
            String invocationSiteId,
            String correlationKey,
            int occurrence,
            int attempt
    ) {
        private static Coordinate from(MirrorResolution resolution) {
            return new Coordinate(resolution.invocationSiteId(), resolution.correlationKey(),
                    resolution.occurrence(), resolution.attempt());
        }
    }

    private record AttemptProjection(
            MirrorPlan.ExternalBinding binding,
            String requestFingerprint,
            String outputFingerprint
    ) {
    }
}
