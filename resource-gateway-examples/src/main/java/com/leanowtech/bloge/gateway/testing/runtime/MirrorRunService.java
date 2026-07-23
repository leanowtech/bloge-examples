package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.operator.ExecutionBudget;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationRunTrust;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationRunTrustAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

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
 * graph/fixture/control identities, external-only control coverage, and the static invocation
 * floor before creating the independent BLOGE engine. The execution context receives the plan's
 * logical deadline budget, while a separate resolver-side occurrence budget fails dynamic
 * foreach, loop, nested, streaming, or compensation expansion before operator execution;
 * production credentials, interceptors, context carriers, and durable stores are never attached.</p>
 *
 * <p>The class itself is framework-neutral. Resource Gateway exposes it only when the isolated
 * test/staging composition has also assembled protected API admission, durable request fencing,
 * exact artifact rehydration, and atomic evidence persistence. Deployment-level egress
 * attestation remains a certification gate and is reported as an evidence limitation; it does
 * not make the protected exploratory serving surface disappear.</p>
 */
public class MirrorRunService {
    private static final Duration EVIDENCE_FINALIZATION_RESERVE = Duration.ZERO;

    private final ObjectMapper mapper;
    private final TestRunService testRunService;
    private final Clock clock;
    private final MirrorRunEvidenceProjector evidenceProjector;
    private final MirrorEvidenceIntegrityService evidenceIntegrity;
    private final MirrorDeploymentIsolationRunTrustAuthority deploymentTrust;

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
        this(registry, mapper, resourceRuntime, clock, VisualEvidenceSigner.unavailable());
    }

    /**
     * Creates a mirror runtime with an explicit evidence-signing authority.
     *
     * <p>No in-memory or local-development key is installed implicitly. A caller that intends to
     * execute a mirror must provide a governed signer; an unavailable signer causes terminal
     * evidence finalization to fail closed after the isolated execution.</p>
     *
     * @param registry operator registry used only to construct the independent engine
     * @param mapper canonical protocol mapper
     * @param resourceRuntime optional descriptor protocol adapter for transport fixtures
     * @param clock server admission clock
     * @param evidenceSigner governed mirror evidence signing authority
     */
    public MirrorRunService(
            OperatorRegistry registry,
            ObjectMapper mapper,
            ResourceFixtureRuntime resourceRuntime,
            Clock clock,
            VisualEvidenceSigner evidenceSigner) {
        this(registry, mapper, resourceRuntime, clock,
                new MirrorEvidenceIntegrityService(mapper, evidenceSigner, Clock.systemUTC()));
    }

    /**
     * Creates a mirror runtime that shares one integrity boundary with durable evidence storage.
     *
     * @param registry operator registry used only to construct the independent engine
     * @param mapper canonical protocol mapper
     * @param resourceRuntime optional descriptor protocol adapter for transport fixtures
     * @param clock server admission clock
     * @param evidenceIntegrity shared detached-signature integrity boundary
     */
    public MirrorRunService(
            OperatorRegistry registry,
            ObjectMapper mapper,
            ResourceFixtureRuntime resourceRuntime,
            Clock clock,
            MirrorEvidenceIntegrityService evidenceIntegrity) {
        this(mapper, new TestRunService(registry, mapper, resourceRuntime), clock,
                new MirrorRunEvidenceProjector(mapper), evidenceIntegrity,
                MirrorDeploymentIsolationRunTrustAuthority.unavailable());
    }

    /**
     * Creates a runtime with explicit evidence integrity and deployment trust authorities.
     *
     * @param registry operator registry used only to construct the independent engine
     * @param mapper canonical protocol mapper
     * @param resourceRuntime optional descriptor protocol adapter for transport fixtures
     * @param clock server admission clock
     * @param evidenceIntegrity shared detached-signature integrity boundary
     * @param deploymentTrust deployment-owned certification authority
     */
    public MirrorRunService(
            OperatorRegistry registry,
            ObjectMapper mapper,
            ResourceFixtureRuntime resourceRuntime,
            Clock clock,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            MirrorDeploymentIsolationRunTrustAuthority deploymentTrust) {
        this(mapper, new TestRunService(registry, mapper, resourceRuntime), clock,
                new MirrorRunEvidenceProjector(mapper), evidenceIntegrity, deploymentTrust);
    }

    /**
     * Creates an admission-test runtime that intentionally has no signing authority.
     *
     * @param mapper canonical protocol mapper
     * @param testRunService isolated shared testing kernel
     * @param clock server admission clock
     */
    public MirrorRunService(ObjectMapper mapper, TestRunService testRunService, Clock clock) {
        this(mapper, testRunService, clock, new MirrorRunEvidenceProjector(mapper),
                new MirrorEvidenceIntegrityService(mapper, VisualEvidenceSigner.unavailable(),
                        Clock.systemUTC()),
                MirrorDeploymentIsolationRunTrustAuthority.unavailable());
    }

    /**
     * Full constructor for focused runtime, failure-injection, and architecture tests.
     *
     * @param mapper canonical protocol mapper
     * @param testRunService isolated shared testing kernel
     * @param clock server admission clock
     * @param evidenceProjector payload-free execution projector
     * @param evidenceIntegrity detached-signature integrity boundary
     */
    public MirrorRunService(
            ObjectMapper mapper,
            TestRunService testRunService,
            Clock clock,
            MirrorRunEvidenceProjector evidenceProjector,
            MirrorEvidenceIntegrityService evidenceIntegrity) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.testRunService = Objects.requireNonNull(testRunService, "testRunService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evidenceProjector = Objects.requireNonNull(evidenceProjector, "evidenceProjector");
        this.evidenceIntegrity = Objects.requireNonNull(evidenceIntegrity, "evidenceIntegrity");
        this.deploymentTrust = MirrorDeploymentIsolationRunTrustAuthority.unavailable();
    }

    /**
     * Full constructor with an explicit deployment-trust authority.
     *
     * @param mapper canonical protocol mapper
     * @param testRunService isolated shared testing kernel
     * @param clock server admission clock
     * @param evidenceProjector payload-free execution projector
     * @param evidenceIntegrity detached-signature integrity boundary
     * @param deploymentTrust deployment-owned certification authority
     */
    public MirrorRunService(
            ObjectMapper mapper,
            TestRunService testRunService,
            Clock clock,
            MirrorRunEvidenceProjector evidenceProjector,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            MirrorDeploymentIsolationRunTrustAuthority deploymentTrust) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.testRunService = Objects.requireNonNull(testRunService, "testRunService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evidenceProjector = Objects.requireNonNull(evidenceProjector, "evidenceProjector");
        this.evidenceIntegrity = Objects.requireNonNull(evidenceIntegrity, "evidenceIntegrity");
        this.deploymentTrust = Objects.requireNonNull(deploymentTrust, "deploymentTrust");
    }

    /**
     * Executes one admitted mirror request against its exact compiled generation.
     *
     * @param request authenticated request and self-contained compiled plan
     * @return graph result, sealed resolution provenance, and a verified payload-free evidence bundle
     * @throws MirrorRunRejectedException when admission, runtime generation, provenance, projection,
     *                                    or signing fails; evidence failures occur after isolated execution
     */
    public MirrorRunResult execute(MirrorRunRequest request) {
        Objects.requireNonNull(request, "request");
        ResolvedCorpusPayloads.GenerationLease lease;
        try {
            lease = request.compiledPlan().executionControl()
                    .corpusPayloads().acquireLease();
        } catch (TestControlException closed) {
            if ("MIRROR_GENERATION_CLOSED".equals(closed.code())) {
                throw reject("RG.MIRROR.RUNTIME_GENERATION_CLOSED",
                        "Compiled mirror payload generation is no longer available.");
            }
            throw closed;
        }
        try (lease) {
            return executeLeased(request);
        }
    }

    private MirrorRunResult executeLeased(MirrorRunRequest request) {
        CompiledMirrorPlan compiled = request.compiledPlan();
        MirrorPlan plan = compiled.plan();
        verifyPlan(plan);
        Instant admittedAt = clock.instant();
        validateAdmission(request, plan, admittedAt);
        validateDeploymentTrust(request, plan);
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
        MirrorResolutionJournal resolutionJournal = new MirrorResolutionJournal(
                mapper, plan, compiled.executionControl().replayPayloads());
        MirrorInvocationBudget invocationBudget = new MirrorInvocationBudget(
                plan.policy().maximumInvocations());
        try {
            execution = testRunService.executeCompiled(executionRequest,
                    compiled.executionControl(), resolutionJournal, invocationBudget);
        } catch (RuntimeException failure) {
            throw reject("RG.MIRROR.RUNTIME_GENERATION_REJECTED",
                    "Compiled mirror generation failed shared-kernel admission.");
        }
        if (execution.plan() == null || !plan.executionControlFingerprint()
                .equals(execution.plan().planFingerprint())) {
            throw reject("RG.MIRROR.RUNTIME_GENERATION_DRIFT",
                    "Executed control generation differs from the sealed mirror plan.");
        }
        List<MirrorResolution> resolutions;
        try {
            resolutions = resolutionJournal.complete(execution.evidence().runId());
        } catch (RuntimeException failure) {
            throw reject("RG.MIRROR.RESOLUTION_EVIDENCE_REJECTED",
                    "Mirror resolution evidence could not be sealed for this run.");
        }
        MirrorRunEvidence evidence;
        try {
            MirrorDeploymentIsolationRunTrust.Binding trustBinding =
                    request.deploymentTrust() == null ? null : deploymentTrust.confirm(
                            request.deploymentTrust(), execution.evidence().startedAt(),
                            execution.evidence().completedAt());
            evidence = trustBinding == null
                    ? evidenceProjector.project(request, execution, resolutions,
                    engineConfiguration(), invocationBudget.snapshot())
                    : evidenceProjector.project(request, execution, resolutions,
                    engineConfiguration(), invocationBudget.snapshot(), trustBinding);
        } catch (MirrorDeploymentIsolationRunTrustAuthority.TrustException failure) {
            throw reject("RG.MIRROR.DEPLOYMENT_TRUST_CHANGED",
                    "Deployment isolation trust changed before evidence confirmation.");
        } catch (RuntimeException failure) {
            throw reject("RG.MIRROR.RUN_EVIDENCE_REJECTED",
                    "Mirror run evidence could not prove a complete payload-free execution closure.");
        }
        MirrorEvidenceIntegrityService.SealResult sealed = evidenceIntegrity.seal(evidence);
        if (!sealed.verified()) {
            String code = MirrorEvidenceIntegrityService.SIGNER_UNAVAILABLE
                    .equals(sealed.failureCode())
                    ? "RG.MIRROR.EVIDENCE_SIGNER_UNAVAILABLE"
                    : "RG.MIRROR.EVIDENCE_INTEGRITY_REJECTED";
            throw reject(code,
                    "Mirror run evidence could not be signed and independently verified.");
        }
        return new MirrorRunResult(plan, admittedAt, execution, resolutions, sealed.bundle());
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

    private static void validateDeploymentTrust(
            MirrorRunRequest request, MirrorPlan plan) {
        boolean present = request.deploymentTrust() != null;
        if (plan.policy().certificationRequired() != present) {
            throw reject("RG.MIRROR.DEPLOYMENT_TRUST_REQUIRED",
                    "Certification-required plans require exact deployment trust admission.");
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
