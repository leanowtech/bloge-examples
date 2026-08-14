package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.businessmirror.authoring.CapabilityProposalDraftRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationEvidence;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationRepository;
import com.leanowtech.bloge.gateway.businessmirror.simulation.StoredCapabilityProposalSimulation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuiteIntegrity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authenticated application boundary for exact same-suite implementation conformance. */
public final class CapabilityImplementationConformanceService {
    public static final String AUTHORIZED_PURPOSE = "CAPABILITY_CONFORMANCE";
    public static final Duration LEASE_DURATION = Duration.ofMinutes(30);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");

    private final CapabilityProposalDraftRepository proposals;
    private final CapabilityProposalSimulationRepository simulations;
    private final CapabilityImplementationBindingService bindingService;
    private final CapabilityImplementationConformanceRepository conformances;
    private final CapabilityImplementationRuntimePort runtime;
    private final TestSuiteRepository suites;
    private final MirrorPlanIntegrationService plans;
    private final MirrorEvidenceRepository mirrorEvidence;
    private final OperatorRegistry operators;
    private final ResourceFixtureRuntime resourceRuntime;
    private final VisualEvidenceSigner signer;
    private final ObjectMapper mapper;
    private final CapabilityImplementationConformancePlanCompiler compiler;
    private final Clock clock;

    public CapabilityImplementationConformanceService(
            CapabilityProposalDraftRepository proposals,
            CapabilityProposalSimulationRepository simulations,
            CapabilityImplementationBindingService bindingService,
            CapabilityImplementationConformanceRepository conformances,
            CapabilityImplementationRuntimePort runtime,
            TestSuiteRepository suites,
            MirrorPlanIntegrationService plans,
            MirrorEvidenceRepository mirrorEvidence,
            OperatorRegistry operators,
            ResourceFixtureRuntime resourceRuntime,
            VisualEvidenceSigner signer,
            ObjectMapper mapper) {
        this(proposals, simulations, bindingService, conformances, runtime, suites, plans,
                mirrorEvidence, operators, resourceRuntime, signer, mapper, Clock.systemUTC());
    }

    /** Full constructor with deterministic wall time for service tests. */
    public CapabilityImplementationConformanceService(
            CapabilityProposalDraftRepository proposals,
            CapabilityProposalSimulationRepository simulations,
            CapabilityImplementationBindingService bindingService,
            CapabilityImplementationConformanceRepository conformances,
            CapabilityImplementationRuntimePort runtime,
            TestSuiteRepository suites,
            MirrorPlanIntegrationService plans,
            MirrorEvidenceRepository mirrorEvidence,
            OperatorRegistry operators,
            ResourceFixtureRuntime resourceRuntime,
            VisualEvidenceSigner signer,
            ObjectMapper mapper,
            Clock clock) {
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.simulations = Objects.requireNonNull(simulations, "simulations");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.conformances = Objects.requireNonNull(conformances, "conformances");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.suites = Objects.requireNonNull(suites, "suites");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.mirrorEvidence = Objects.requireNonNull(mirrorEvidence, "mirrorEvidence");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.resourceRuntime = resourceRuntime;
        this.signer = Objects.requireNonNull(signer, "signer");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.compiler = new CapabilityImplementationConformancePlanCompiler(mapper);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Executes the original acceptance Cases against one exact implementation binding. */
    public StoredCapabilityImplementationConformance conform(
            String proposalId,
            long proposalRevision,
            String conformanceId,
            CapabilityImplementationConformanceRequest request,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireRunIdentity(identity);
        String proposal = requireId(proposalId, "proposalId", identity);
        String id = requireId(conformanceId, "Idempotency-Key", identity);
        if (proposalRevision < 1 || request == null) {
            throw badRequest(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_REQUEST_INVALID",
                    "A positive Proposal revision and conformance command are required.");
        }
        String requestFingerprint = ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", CapabilityImplementationConformanceRequest.SCHEMA_VERSION,
                "proposalId", proposal,
                "proposalRevision", proposalRevision,
                "conformanceId", id,
                "request", request));
        CapabilityImplementationConformanceRepository.Registration registration =
                new CapabilityImplementationConformanceRepository.Registration(
                        scope, id, proposal, proposalRevision,
                        request.implementationBindingRef(), requestFingerprint);
        CapabilityImplementationConformanceRepository.Claim claim;
        try {
            claim = conformances.claim(registration,
                    "implementation-conformance-" + UUID.randomUUID(), LEASE_DURATION);
        } catch (IllegalArgumentException conflict) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_IDEMPOTENCY_CONFLICT",
                    "The binding or Idempotency-Key identifies different material.");
        }
        if (claim.outcome()
                == CapabilityImplementationConformanceRepository.Outcome.IN_PROGRESS) {
            throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_IN_PROGRESS",
                    "An identical implementation conformance run is already in progress.",
                    identity.correlationId(), Map.of(
                    "retryAfterSeconds", claim.retryAfterSeconds())));
        }
        if (claim.outcome()
                == CapabilityImplementationConformanceRepository.Outcome.COMPLETED) {
            return verified(claim.state().result(), identity);
        }
        try {
            StoredCapabilityImplementationConformance completed = execute(
                    registration, claim.lease(), claim.state().createdAt(), request, identity);
            if (!conformances.complete(claim.lease(), completed)) {
                throw conflict(identity,
                        "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_LEASE_LOST",
                        "Conformance authority expired before the report was committed.");
            }
            return completed;
        } catch (RuntimeException failure) {
            conformances.release(claim.lease(), failureCode(failure));
            throw failure;
        }
    }

    /** Reads by exact binding coordinates without an unbounded conformance-id scan. */
    public StoredCapabilityImplementationConformance findByBinding(
            String bindingId,
            long bindingRevision,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireReadIdentity(identity);
        CapabilityImplementationConformanceRepository.State state = conformances.find(
                        scope, requireId(bindingId, "bindingId", identity), bindingRevision)
                .filter(value -> value.status()
                        == CapabilityImplementationConformanceRepository.Status.COMPLETED)
                .orElseThrow(() -> notFound(identity));
        return verified(state.result(), identity);
    }

    private StoredCapabilityImplementationConformance execute(
            CapabilityImplementationConformanceRepository.Registration registration,
            CapabilityImplementationConformanceRepository.Lease lease,
            Instant startedAt,
            CapabilityImplementationConformanceRequest request,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = registration.scope();
        StoredCapabilityImplementationBinding storedBinding = bindingService.find(
                request.implementationBindingRef().id(), identity);
        CapabilityImplementationBinding binding = storedBinding.binding();
        if (!binding.artifactRef().equals(request.implementationBindingRef())
                || !binding.proposalDraftRef().id().equals(registration.proposalId())
                || binding.proposalDraftRef().revision() != registration.proposalRevision()
                || !binding.simulationEvidenceRef().equals(request.simulationEvidenceRef())
                || !binding.scope().equals(scope)) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_BINDING_STALE",
                    "The implementation binding does not close the requested Proposal and simulation.");
        }
        Instant now = clock.instant();
        if (!now.isBefore(binding.expiresAt())) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_BINDING_EXPIRED",
                    "The implementation binding has expired.");
        }
        requireRuntime(binding, identity);
        StoredCapabilityProposalDraft proposal = exactProposal(
                registration.proposalId(), registration.proposalRevision(),
                request.expectedProposalDraftFingerprint(), scope, identity);
        StoredCapabilityProposalSimulation simulation = exactSimulation(
                registration, binding, identity);

        List<CapabilityImplementationConformanceReport.CaseComparison> comparisons =
                new ArrayList<>();
        for (CapabilityProposalSimulationEvidence.CaseEvidence baselineCase
                : simulation.evidence().cases()) {
            renew(lease, identity);
            requireRuntime(binding, identity);
            StoredTestSuite storedSuite = exactSuite(baselineCase.suiteRef(), scope, identity);
            TestSuite suite = (TestSuite) storedSuite.suite();
            TestSuite.TestCase testCase = suite.cases().stream()
                    .filter(value -> value.caseId().equals(baselineCase.caseId()))
                    .filter(value -> value.caseType() == baselineCase.caseType())
                    .findFirst().orElseThrow(() -> conflict(identity,
                            "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_CASE_STALE",
                            "The accepted Suite no longer contains the exact baseline Case."));
            requireCaseFixture(testCase, baselineCase.fixtureRef(), identity);
            String coordinate = baselineCase.suiteRef().fingerprint() + ":" + testCase.caseId();
            try (CompiledMirrorPlan baseline = plans.materializeForConformance(
                    baselineCase.mirrorPlanRef(), identity)) {
                if (!baseline.plan().fixtureBundleRef().equals(baselineCase.fixtureRef())) {
                    throw conflict(identity,
                            "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_FIXTURE_STALE",
                            "The baseline MirrorPlan does not bind the accepted Case Fixture.");
                }
                MirrorEvidenceBundle baselineBundle = exactMirrorEvidence(
                        baselineCase, baseline, scope, identity);
                CapabilityImplementationConformancePlanCompiler.Result compiled;
                try {
                    compiled = compiler.compile(baseline,
                            simulation.evidence().temporaryCapabilityRef(), binding,
                            registration.conformanceId(), coordinate);
                } catch (IllegalArgumentException unsafe) {
                    throw conflict(identity,
                            "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_PLAN_REJECTED",
                            "The baseline cannot safely isolate an implementation-only target.");
                }
                ConformanceOperatorRegistry registry = new ConformanceOperatorRegistry(
                        operators, compiled.targetOperatorRefs(), compiled.runtimeCoordinates(),
                        binding, runtime, registration.conformanceId(), coordinate, clock);
                TestRunService runner = new TestRunService(registry, mapper, resourceRuntime);
                var executionControl = compiled.bindTargetOperator(registry.targetOperator());
                TestExecutionResult result = runner.executeCompiled(new TestExecutionRequest(
                        baseline.graph(), new GraphContext(inputContext(testCase.input(), identity)),
                        compiled.fixture(), AUTHORIZED_PURPOSE,
                        compiled.fixture().targetFingerprint(),
                        TestExecutionRequest.FixtureSource.STORED,
                        Map.of("proposalDraftRef", binding.proposalDraftRef(),
                                "implementationBindingRef", binding.artifactRef(),
                                "suiteRef", baselineCase.suiteRef(),
                                "caseId", baselineCase.caseId()),
                        false, executionControl.replayPayloads(),
                        ResolvedTestSecrets.empty()), executionControl);
                comparisons.add(compare(baselineCase, baselineBundle, result.evidence(), registry,
                        compiled.targetSiteIds()));
            }
            renew(lease, identity);
        }

        Instant completedAt = clock.instant();
        if (!completedAt.isBefore(binding.expiresAt())
                || !completedAt.isBefore(proposal.draft().expiresAt())) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_EXPIRED",
                    "Proposal or implementation binding expired before report commit.");
        }
        boolean passed = comparisons.stream().allMatch(value -> value.comparison()
                == CapabilityImplementationConformanceReport.Comparison.MATCH);
        List<String> limitations = new ArrayList<>(proposal.draft().limitations());
        limitations.add("Same-suite conformance proves declared assertions and semantic result identity only.");
        CapabilityImplementationConformanceReport report =
                new CapabilityImplementationConformanceReport("", registration.conformanceId(),
                        "", scope, binding.proposalDraftRef(), binding.simulationEvidenceRef(),
                        binding.artifactRef(), binding.targetCapabilityRef(),
                        simulation.evidence().graphRef(),
                        simulation.evidence().acceptanceSuiteRefs(),
                        passed ? CapabilityImplementationConformanceReport.Status.PASSED
                                : CapabilityImplementationConformanceReport.Status.FAILED,
                        comparisons, startedAt, completedAt, limitations,
                        List.of("Undeclared business semantics and production outcome fidelity are not evaluated."))
                        .seal(mapper);
        VisualRunEvidenceSeal attestation = signer.seal(report.fingerprint(),
                "proposal-implementation-conformance:" + scope.tenantId() + ":"
                        + registration.conformanceId());
        if (!attestation.signed()
                || !signer.verify(attestation, report.fingerprint()).valid()) {
            throw unavailable(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_SIGNER_UNAVAILABLE",
                    "The implementation conformance report could not be signed and verified.");
        }
        CapabilityProposalSnapshot snapshot = proposalSnapshot(
                proposal, binding, report, completedAt);
        StoredCapabilityImplementationConformance result =
                new StoredCapabilityImplementationConformance("",
                        registration.requestFingerprint(), report, attestation, snapshot,
                        completedAt);
        renew(lease, identity);
        return verified(result, identity);
    }

    private CapabilityImplementationConformanceReport.CaseComparison compare(
            CapabilityProposalSimulationEvidence.CaseEvidence baselineCase,
            MirrorEvidenceBundle baselineBundle,
            TestRunEvidence implementation,
            ConformanceOperatorRegistry registry,
            Set<String> compiledTargetSites) {
        List<MirrorResolution> baselineResolutions = baselineBundle.evidence().resolutions().stream()
                .filter(value -> compiledTargetSites.contains(value.invocationSiteId()))
                .toList();
        Set<String> baselineSites = baselineResolutions.stream()
                .map(MirrorResolution::invocationSiteId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> implementationSites = registry.invokedSiteIds();
        List<String> mismatches = new ArrayList<>();
        if (!"PASSED".equals(baselineCase.runStatus())
                || baselineBundle.evidence().status()
                != com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence.Status.PASSED) {
            mismatches.add("BASELINE_NOT_PASSED");
        }
        if (implementation.status() != TestRunEvidence.Status.PASSED) {
            mismatches.add("IMPLEMENTATION_NOT_PASSED");
        }
        String baselineBehavior = CapabilityImplementationBehaviorFingerprint.baseline(
                mapper, baselineBundle.evidence());
        String implementationBehavior = CapabilityImplementationBehaviorFingerprint.implementation(
                mapper, implementation);
        if (!baselineBehavior.equals(implementationBehavior)) {
            mismatches.add("OBSERVABLE_BEHAVIOR_MISMATCH");
        }
        if (baselineCase.proposalCallCount() != registry.totalCalls()) {
            mismatches.add("TARGET_CALL_COUNT_MISMATCH");
        }
        if (baselineCase.proposalCallCount() != baselineResolutions.size()) {
            mismatches.add("BASELINE_CALL_COUNT_DRIFT");
        }
        if (!Set.copyOf(implementationSites).equals(baselineSites)) {
            mismatches.add("TARGET_INVOCATION_SITE_MISMATCH");
        }
        if (implementation.assertionResults().stream()
                .anyMatch(value -> !value.passed())) {
            mismatches.add("ASSERTION_VERDICT_MISMATCH");
        }
        CapabilityImplementationConformanceReport.ImplementationEvidence evidence =
                new CapabilityImplementationConformanceReport.ImplementationEvidence("",
                        implementation.runId(), "", implementation.status().name(),
                        implementation.semanticResultFingerprint(), implementation.planFingerprint(),
                        implementation.fixtureBundleFingerprint(),
                        ProtocolFingerprint.of(mapper, implementation.assertionResults()),
                        ProtocolFingerprint.of(mapper, implementation.nodeTrace()),
                        ProtocolFingerprint.of(mapper, implementation.edgeTrace()),
                        implementation.startedAt(), implementation.completedAt()).seal(mapper);
        List<String> sites = new ArrayList<>(baselineSites);
        sites.addAll(implementationSites);
        return new CapabilityImplementationConformanceReport.CaseComparison(
                baselineCase.caseId(), baselineCase.caseType(), baselineCase.suiteRef(),
                baselineCase.fixtureRef(), baselineCase.mirrorEvidenceBundleRef(), evidence,
                baselineCase.runStatus(), baselineBundle.evidence().semanticResultFingerprint(),
                baselineBehavior, implementationBehavior, mismatches.isEmpty()
                ? CapabilityImplementationConformanceReport.Comparison.MATCH
                : CapabilityImplementationConformanceReport.Comparison.MISMATCH,
                baselineCase.proposalCallCount(), registry.totalCalls(), sites, mismatches,
                baselineCase.limitations());
    }

    private StoredCapabilityProposalDraft exactProposal(
            String proposalId,
            long revision,
            String expectedFingerprint,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        StoredCapabilityProposalDraft stored = proposals.findRevision(scope, proposalId, revision)
                .orElseThrow(() -> notFound(identity));
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, stored.draft(), 4 * 1024 * 1024);
        if (!fingerprint.equals(stored.draftFingerprint())
                || !fingerprint.equals(expectedFingerprint)
                || !stored.draft().readinessBlockers().isEmpty()) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_PROPOSAL_STALE",
                    "The Proposal differs from the reviewed ready revision.");
        }
        if (!clock.instant().isBefore(stored.draft().expiresAt())) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_PROPOSAL_EXPIRED",
                    "The Proposal has expired.");
        }
        return stored;
    }

    private StoredCapabilityProposalSimulation exactSimulation(
            CapabilityImplementationConformanceRepository.Registration registration,
            CapabilityImplementationBinding binding,
            IntegrationRequestContext identity) {
        CapabilityProposalSimulationRepository.State state = simulations.find(
                        registration.scope(), registration.proposalId(),
                        registration.proposalRevision())
                .filter(value -> value.status()
                        == CapabilityProposalSimulationRepository.Status.COMPLETED)
                .orElseThrow(() -> conflict(identity,
                        "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_SIMULATION_REQUIRED",
                        "The exact completed simulation is unavailable."));
        StoredCapabilityProposalSimulation stored = state.result();
        try {
            stored.verify(mapper, signer);
        } catch (RuntimeException invalid) {
            throw unavailable(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_SIMULATION_INVALID",
                    "The Proposal simulation failed independent verification.");
        }
        CapabilityProposalSimulationEvidence evidence = stored.evidence();
        if (!evidence.artifactRef().equals(binding.simulationEvidenceRef())
                || evidence.status() != CapabilityProposalSimulationEvidence.Status.PASSED
                || !evidence.proposalDraftRef().equals(binding.proposalDraftRef())
                || !evidence.targetCapabilityRef().equals(binding.targetCapabilityRef())) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_SIMULATION_STALE",
                    "Simulation, Proposal target, or implementation binding has drifted.");
        }
        return stored;
    }

    private StoredTestSuite exactSuite(
            MirrorArtifactRef ref,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        TestingArtifactScope testingScope = testingScope(scope);
        try {
            StoredTestSuite stored = suites.find(testingScope, ref.id(), ref.revision())
                    .map(value -> StoredTestSuiteIntegrity.verifiedSnapshot(
                            mapper, value, testingScope, ref.id(), ref.revision()))
                    .orElseThrow(() -> notFound(identity));
            if (!stored.fingerprint().equals(ref.fingerprint())
                    || !(stored.suite() instanceof TestSuite)) {
                throw new IllegalArgumentException("Suite fingerprint mismatch");
            }
            return stored;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_SUITE_STALE",
                    "An exact acceptance Suite is unavailable or invalid.");
        }
    }

    private MirrorEvidenceBundle exactMirrorEvidence(
            CapabilityProposalSimulationEvidence.CaseEvidence baselineCase,
            CompiledMirrorPlan baseline,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        MirrorArtifactRef ref = baselineCase.mirrorEvidenceBundleRef();
        try {
            MirrorEvidenceBundle bundle = mirrorEvidence.find(scope, ref.id())
                    .orElseThrow(() -> notFound(identity));
            if (!bundle.bundleFingerprint().equals(ref.fingerprint())
                    || !bundle.evidence().planId().equals(baseline.plan().planId())
                    || !bundle.evidence().planFingerprint()
                    .equals(baseline.plan().planFingerprint())
                    || !bundle.evidence().fixtureBundleRef().equals(baselineCase.fixtureRef())) {
                throw new IllegalArgumentException("baseline evidence identity mismatch");
            }
            return bundle;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException invalid) {
            throw unavailable(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_BASELINE_INVALID",
                    "The baseline Mirror evidence is unavailable or failed verification.");
        }
    }

    private void requireRuntime(
            CapabilityImplementationBinding binding,
            IntegrationRequestContext identity) {
        try {
            CapabilityImplementationRuntimePort.Descriptor descriptor = runtime.describe(
                            binding.scope(), binding.runtimePortRef())
                    .orElseThrow();
            CapabilityImplementationBindingService.requireExactDescriptor(binding, descriptor);
            if (!clock.instant().isBefore(binding.expiresAt())) {
                throw new IllegalArgumentException("binding expired");
            }
        } catch (RuntimeException drift) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_RUNTIME_DRIFT",
                    "The runtime descriptor is unavailable, expired, or differs from the binding.");
        }
    }

    private CapabilityProposalSnapshot proposalSnapshot(
            StoredCapabilityProposalDraft proposal,
            CapabilityImplementationBinding binding,
            CapabilityImplementationConformanceReport report,
            Instant completedAt) {
        CapabilityProposalDraft draft = proposal.draft();
        return new CapabilityProposalSnapshot("", draft.proposalId(), draft.revision(), "",
                draft.scope(), draft.revision(), proposal.draftFingerprint(), draft.businessIntent(),
                draft.candidateContract(), draft.fixturePackRefs(),
                draft.businessAcceptanceSuiteRefs(), draft.simulationRuntimeBinding(),
                binding.artifactRef(), report.status()
                == CapabilityImplementationConformanceReport.Status.PASSED
                ? CapabilityProposalSnapshot.EvidenceState.CONFORMANT
                : CapabilityProposalSnapshot.EvidenceState.IMPLEMENTED,
                List.of(binding.simulationEvidenceRef(), report.artifactRef()),
                draft.assumptions(), report.limitations(), draft.expiresAt(), draft.provenance(),
                completedAt).seal(mapper);
    }

    private StoredCapabilityImplementationConformance verified(
            StoredCapabilityImplementationConformance value,
            IntegrationRequestContext identity) {
        try {
            Objects.requireNonNull(value, "value").verify(mapper, signer);
            if (!scope(identity).equals(value.report().scope())) {
                throw new IllegalArgumentException("conformance Scope mismatch");
            }
            return value;
        } catch (RuntimeException invalid) {
            throw unavailable(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_INVALID",
                    "Stored implementation conformance failed independent verification.");
        }
    }

    private void renew(
            CapabilityImplementationConformanceRepository.Lease lease,
            IntegrationRequestContext identity) {
        if (!conformances.renew(lease, LEASE_DURATION)) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_LEASE_LOST",
                    "Conformance authority expired during acceptance execution.");
        }
    }

    private static void requireCaseFixture(
            TestSuite.TestCase testCase,
            MirrorArtifactRef expected,
            IntegrationRequestContext identity) {
        TestSuite.FixtureBundleRef actual = testCase.fixtureBundleRef();
        if (!expected.id().equals(actual.fixtureBundleId())
                || expected.revision() != actual.revision()
                || !expected.fingerprint().equals(actual.fingerprint())) {
            throw conflict(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_CASE_FIXTURE_STALE",
                    "The acceptance Case Fixture differs from the simulated baseline.");
        }
    }

    private Map<String, Object> inputContext(
            Object input, IntegrationRequestContext identity) {
        if (!(input instanceof Map<?, ?>)) {
            throw badRequest(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_INPUT_INVALID",
                    "Graph acceptance Cases require an object input context.");
        }
        try {
            return mapper.convertValue(input, new TypeReference<Map<String, Object>>() { });
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_INPUT_INVALID",
                    "The acceptance Case input is not a valid JSON object.");
        }
    }

    private static TestingArtifactScope testingScope(CapabilitySnapshot.Scope scope) {
        return new TestingArtifactScope(scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region());
    }

    private static CapabilitySnapshot.Scope requireRunIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!AUTHORIZED_PURPOSE.equals(identity.purpose())
                || !Set.of("test", "staging")
                .contains(identity.environmentId().toLowerCase())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_FORBIDDEN",
                    "Implementation conformance requires CAPABILITY_CONFORMANCE in test or staging.",
                    identity.correlationId(), Map.of()));
        }
        return scope(identity);
    }

    private static CapabilitySnapshot.Scope requireReadIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!Set.of(AUTHORIZED_PURPOSE, "GOVERNANCE_EVIDENCE_INGESTION")
                .contains(identity.purpose())
                || !Set.of("test", "staging")
                .contains(identity.environmentId().toLowerCase())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_READ_FORBIDDEN",
                    "Conformance reads require an authorized test or staging workload.",
                    identity.correlationId(), Map.of()));
        }
        return scope(identity);
    }

    private static CapabilitySnapshot.Scope scope(IntegrationRequestContext identity) {
        return new CapabilitySnapshot.Scope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static String requireId(
            String value, String field, IntegrationRequestContext identity) {
        String exact = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw badRequest(identity,
                    "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_ID_INVALID",
                    field + " is invalid.");
        }
        return exact;
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof IntegrationProblemException problem) {
            return problem.problem().code();
        }
        return "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_UNAVAILABLE";
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.BUSINESS_MIRROR.IMPLEMENTATION_CONFORMANCE_NOT_FOUND",
                "The exact implementation conformance artifact was not found in this Scope.",
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }
}
