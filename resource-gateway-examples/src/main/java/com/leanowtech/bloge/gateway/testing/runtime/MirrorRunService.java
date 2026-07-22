package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.operator.ExecutionBudget;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Admits and executes a self-contained stateless mirror generation without recompilation.
 *
 * <p>The service verifies the public seal, authenticated scope and purpose, hard expiry, exact
 * graph/fixture/control identities, and external-only control coverage before creating the
 * independent BLOGE engine. The execution context receives the plan's logical deadline budget;
 * production credentials, interceptors, context carriers, and durable stores are never attached.</p>
 *
 * <p>This Stage 1 kernel intentionally exposes no Spring bean or HTTP endpoint. Deployment-level
 * egress isolation and resolver provenance remain release gates, so capability probes must continue
 * to report mirror serving as unavailable.</p>
 */
public class MirrorRunService {
    private static final Duration EVIDENCE_FINALIZATION_RESERVE = Duration.ZERO;

    private final ObjectMapper mapper;
    private final TestRunService testRunService;
    private final Clock clock;

    /**
     * Creates a mirror runtime over the shared isolated testing kernel.
     *
     * @param registry operator registry used only to construct the independent engine
     * @param mapper canonical protocol mapper
     * @param resourceRuntime optional descriptor protocol adapter for transport fixtures
     * @param clock server admission clock
     */
    public MirrorRunService(
            OperatorRegistry registry,
            ObjectMapper mapper,
            ResourceFixtureRuntime resourceRuntime,
            Clock clock) {
        this(mapper, new TestRunService(registry, mapper, resourceRuntime), clock);
    }

    /** Constructor for focused runtime and architecture tests. */
    public MirrorRunService(ObjectMapper mapper, TestRunService testRunService, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.testRunService = Objects.requireNonNull(testRunService, "testRunService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Executes one admitted mirror request against its exact compiled generation.
     *
     * @param request authenticated request and self-contained compiled plan
     * @return graph result plus shared semantically fingerprinted testing evidence
     * @throws MirrorRunRejectedException before scheduling when any immutable fact mismatches
     */
    public MirrorRunResult execute(MirrorRunRequest request) {
        Objects.requireNonNull(request, "request");
        CompiledMirrorPlan compiled = request.compiledPlan();
        MirrorPlan plan = compiled.plan();
        verifyPlan(plan);
        Instant admittedAt = clock.instant();
        validateAdmission(request, plan, admittedAt);
        validateRuntimeGeneration(compiled);

        GraphContext context = new GraphContext(request.context().asMap());
        context.bindExecutionBudget(ExecutionBudget.until(
                plan.executionServices().logicalClock().plus(plan.policy().timeout()),
                EVIDENCE_FINALIZATION_RESERVE));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mirrorRequestId", request.requestId());
        metadata.put("mirrorPlanId", plan.planId());
        metadata.put("mirrorPlanFingerprint", plan.planFingerprint());
        metadata.put("capabilityClosureFingerprint", plan.capabilityClosureFingerprint());
        metadata.put("rootCapability", plan.rootCapability());
        metadata.put("mirrorScope", plan.scope());

        TestExecutionRequest executionRequest = new TestExecutionRequest(
                compiled.graph(), context, compiled.fixtureBundle(),
                plan.policy().authorizedPurpose(),
                compiled.executionControl().effectivePlan().targetFingerprint(),
                TestExecutionRequest.FixtureSource.STORED, metadata,
                true, compiled.executionControl().replayPayloads(), ResolvedTestSecrets.empty());
        TestExecutionResult execution;
        try {
            execution = testRunService.executeCompiled(executionRequest,
                    compiled.executionControl());
        } catch (RuntimeException failure) {
            throw reject("RG.MIRROR.RUNTIME_GENERATION_REJECTED",
                    "Compiled mirror generation failed shared-kernel admission.");
        }
        if (execution.plan() == null || !plan.executionControlFingerprint()
                .equals(execution.plan().planFingerprint())) {
            throw reject("RG.MIRROR.RUNTIME_GENERATION_DRIFT",
                    "Executed control generation differs from the sealed mirror plan.");
        }
        return new MirrorRunResult(plan, admittedAt, execution);
    }

    /** @return structural isolation facts for architecture tests and future capability probes */
    public IndependentTestEngineFactory.Configuration engineConfiguration() {
        return testRunService.engineConfiguration();
    }

    private void verifyPlan(MirrorPlan plan) {
        try {
            MirrorPlanIntegrity.verify(mapper, plan);
        } catch (IllegalArgumentException invalid) {
            throw reject("RG.MIRROR.PLAN_INTEGRITY_REJECTED",
                    "Mirror plan failed exact integrity verification.");
        }
    }

    private static void validateAdmission(
            MirrorRunRequest request,
            MirrorPlan plan,
            Instant admittedAt) {
        if (!request.authorizedScope().equals(plan.scope())) {
            throw reject("RG.MIRROR.RUN_SCOPE_MISMATCH",
                    "Authenticated scope does not match the mirror plan.");
        }
        if (!request.authorizedPurpose().equals(plan.policy().authorizedPurpose())) {
            throw reject("RG.MIRROR.RUN_PURPOSE_MISMATCH",
                    "Authenticated purpose does not match the mirror plan.");
        }
        if (admittedAt.isBefore(plan.compiledAt())) {
            throw reject("RG.MIRROR.RUN_NOT_YET_VALID",
                    "Server time precedes the mirror compilation instant.");
        }
        if (!admittedAt.isBefore(plan.expiresAt())) {
            throw reject("RG.MIRROR.RUN_EXPIRED", "Mirror plan has expired.");
        }
    }

    private void validateRuntimeGeneration(CompiledMirrorPlan compiled) {
        MirrorPlan plan = compiled.plan();
        CompiledExecutionControl control = compiled.executionControl();
        EffectiveExecutionPlan effective = control.effectivePlan();
        String fixtureFingerprint = ProtocolFingerprint.of(mapper, compiled.fixtureBundle());
        if (!fixtureFingerprint.equals(plan.fixtureBundleRef().fingerprint())
                || !fixtureFingerprint.equals(effective.fixtureBundleFingerprint())) {
            throw reject("RG.MIRROR.FIXTURE_GENERATION_DRIFT",
                    "Runtime fixture differs from the sealed mirror generation.");
        }
        CapabilitySnapshot root = plan.capabilityClosure().stream()
                .filter(snapshot -> CapabilityClosureIntegrity.reference(snapshot)
                        .equals(plan.rootCapability()))
                .findFirst().orElseThrow(() -> reject("RG.MIRROR.ROOT_CAPABILITY_MISSING",
                        "Root capability is absent from the sealed closure."));
        if (!root.source().sourceFingerprint().equals(effective.targetFingerprint())
                || !(root.capabilityId().equals("graph:" + compiled.graph().name())
                || root.source().sourceRef().equals(compiled.graph().name()))) {
            throw reject("RG.MIRROR.GRAPH_GENERATION_DRIFT",
                    "Runtime graph differs from the sealed mirror generation.");
        }

        Set<String> externalSites = plan.externalBindings().stream()
                .map(MirrorPlan.ExternalBinding::invocationSiteId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!control.controls().keySet().equals(externalSites)) {
            throw reject("RG.MIRROR.EXTERNAL_CONTROL_COVERAGE_DRIFT",
                    "Runtime controls must exactly cover sealed external invocation sites.");
        }
        for (String siteId : externalSites) {
            EffectiveExecutionPlan.ResolvedSite site = effective.resolvedSites().stream()
                    .filter(candidate -> candidate.invocationSiteId().equals(siteId))
                    .findFirst().orElseThrow(() -> reject(
                            "RG.MIRROR.EXTERNAL_CONTROL_PROJECTION_MISSING",
                            "An external site is absent from the effective execution plan."));
            if (site.resolution() == EffectiveExecutionPlan.Resolution.REAL) {
                throw reject("RG.MIRROR.REAL_EXTERNAL_CONTROL_FORBIDDEN",
                        "A mirror external site resolved to a real operator.");
            }
        }
        validateFrozenGraphInventory(compiled.graph(), control.inventory());
        if (control.inventory().entries().size() > plan.policy().maximumInvocations()) {
            throw reject("RG.MIRROR.INVOCATION_BUDGET_TOO_SMALL",
                    "Static invocation inventory exceeds the mirror run budget.");
        }
    }

    private static void validateFrozenGraphInventory(
            com.leanowtech.bloge.core.model.Graph graph,
            InvocationInventory inventory) {
        Set<com.leanowtech.bloge.core.model.Graph> rootGraphs = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        inventory.entries().stream()
                .filter(entry -> "/root".equals(entry.site().graphPath()))
                .forEach(entry -> rootGraphs.add(entry.graph()));
        if (rootGraphs.size() != 1 || !rootGraphs.contains(graph)) {
            throw reject("RG.MIRROR.RUNTIME_INVENTORY_DRIFT",
                    "Frozen invocation inventory does not belong to the exact runtime graph.");
        }
        Set<String> structuralIds = new HashSet<>();
        if (inventory.entries().stream()
                .anyMatch(entry -> !structuralIds.add(entry.engineStructuralId()))) {
            throw reject("RG.MIRROR.RUNTIME_INVENTORY_DUPLICATE",
                    "Frozen invocation inventory contains duplicate structural identities.");
        }
    }

    private static MirrorRunRejectedException reject(String code, String diagnostic) {
        return new MirrorRunRejectedException(code, List.of(diagnostic));
    }
}
