package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.businessmirror.authoring.CapabilityProposalDraftRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.compilation.BuiltInGraphAssetAuthority;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorExecutionRequest;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanCreateRequest;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunSummary;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundleIntegrity;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuiteIntegrity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authenticated application boundary for isolated Proposal acceptance simulation. */
public final class CapabilityProposalSimulationService {
    public static final Duration LEASE_DURATION = Duration.ofMinutes(30);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");

    private final CapabilityProposalDraftRepository proposals;
    private final PackageCompilationFactRepository packages;
    private final BuiltInGraphAssetAuthority graphAssets;
    private final GatewayGraphService graphs;
    private final TestSuiteRepository suites;
    private final FixtureBundleRepository fixtures;
    private final CapabilityProposalSimulationRepository simulations;
    private final CapabilityProposalSimulationCompiler compiler;
    private final MirrorPlanIntegrationService plans;
    private final MirrorRunIntegrationService runs;
    private final VisualEvidenceSigner signer;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CapabilityProposalSimulationService(
            CapabilityProposalDraftRepository proposals,
            PackageCompilationFactRepository packages,
            BuiltInGraphAssetAuthority graphAssets,
            GatewayGraphService graphs,
            TestSuiteRepository suites,
            FixtureBundleRepository fixtures,
            CapabilityProposalSimulationRepository simulations,
            MirrorPlanIntegrationService plans,
            MirrorRunIntegrationService runs,
            VisualEvidenceSigner signer,
            ObjectMapper mapper) {
        this(proposals, packages, graphAssets, graphs, suites, fixtures, simulations, plans,
                runs, signer, mapper, Clock.systemUTC());
    }

    /** Full constructor with deterministic time for application-service tests. */
    public CapabilityProposalSimulationService(
            CapabilityProposalDraftRepository proposals,
            PackageCompilationFactRepository packages,
            BuiltInGraphAssetAuthority graphAssets,
            GatewayGraphService graphs,
            TestSuiteRepository suites,
            FixtureBundleRepository fixtures,
            CapabilityProposalSimulationRepository simulations,
            MirrorPlanIntegrationService plans,
            MirrorRunIntegrationService runs,
            VisualEvidenceSigner signer,
            ObjectMapper mapper,
            Clock clock) {
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.graphAssets = Objects.requireNonNull(graphAssets, "graphAssets");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.suites = Objects.requireNonNull(suites, "suites");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.simulations = Objects.requireNonNull(simulations, "simulations");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.compiler = new CapabilityProposalSimulationCompiler(mapper);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Executes every exact direct acceptance suite, or returns the exact completed retry. */
    public StoredCapabilityProposalSimulation simulate(
            String proposalId,
            long proposalRevision,
            String simulationId,
            CapabilityProposalSimulationRequest request,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireIdentity(identity);
        String id = requireId(proposalId, "proposalId", identity);
        String runId = requireId(simulationId, "Idempotency-Key", identity);
        if (proposalRevision < 1 || request == null) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_REQUEST_INVALID",
                    "A positive Proposal revision and simulation command are required.", Map.of());
        }
        String requestFingerprint = ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", CapabilityProposalSimulationRequest.SCHEMA_VERSION,
                "proposalId", id,
                "proposalRevision", proposalRevision,
                "request", request));
        CapabilityProposalSimulationRepository.Registration registration =
                new CapabilityProposalSimulationRepository.Registration(
                        scope, runId, id, proposalRevision, requestFingerprint);
        CapabilityProposalSimulationRepository.Claim claim;
        try {
            claim = simulations.claim(registration,
                    "proposal-simulation-" + UUID.randomUUID(), LEASE_DURATION);
        } catch (IllegalArgumentException conflict) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_IDEMPOTENCY_CONFLICT",
                    "The Proposal revision or Idempotency-Key identifies different material.",
                    Map.of());
        }
        if (claim.outcome() == CapabilityProposalSimulationRepository.Outcome.IN_PROGRESS) {
            throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                    "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_IN_PROGRESS",
                    "An identical Proposal simulation is already in progress.",
                    identity.correlationId(), Map.of(
                    "retryAfterSeconds", claim.retryAfterSeconds())));
        }
        if (claim.outcome() == CapabilityProposalSimulationRepository.Outcome.COMPLETED) {
            return verified(claim.state().result(), identity);
        }

        try {
            StoredCapabilityProposalSimulation completed = execute(
                    registration, claim.lease(), claim.state().createdAt(), request, identity);
            if (!simulations.complete(claim.lease(), completed)) {
                throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_LEASE_LOST",
                        "Proposal simulation authority expired before evidence commit.", Map.of());
            }
            return completed;
        } catch (RuntimeException failure) {
            simulations.release(claim.lease(), failureCode(failure));
            throw failure;
        }
    }

    /** Reads one completed exact simulation result in the authenticated Scope. */
    public StoredCapabilityProposalSimulation find(
            String proposalId,
            long proposalRevision,
            String simulationId,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireIdentity(identity);
        String id = requireId(proposalId, "proposalId", identity);
        String runId = requireId(simulationId, "simulationId", identity);
        CapabilityProposalSimulationRepository.State state = simulations.find(
                        scope, id, proposalRevision)
                .filter(value -> value.registration().simulationId().equals(runId))
                .filter(value -> value.status()
                        == CapabilityProposalSimulationRepository.Status.COMPLETED)
                .orElseThrow(() -> notFound(identity, id, proposalRevision));
        return verified(state.result(), identity);
    }

    private StoredCapabilityProposalSimulation execute(
            CapabilityProposalSimulationRepository.Registration registration,
            CapabilityProposalSimulationRepository.Lease lease,
            Instant startedAt,
            CapabilityProposalSimulationRequest request,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = registration.scope();
        StoredCapabilityProposalDraft proposal = exactProposal(
                registration.proposalId(), registration.proposalRevision(),
                request.expectedProposalDraftFingerprint(), scope, identity);
        CapabilityProposalDraft draft = proposal.draft();
        Instant now = clock.instant();
        if (!now.isBefore(draft.expiresAt())) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_EXPIRED",
                    "The Capability Proposal has expired.", Map.of());
        }
        requireDeclared(draft, request, identity);
        String graphName = BuiltInGraphAssetAuthority.graphNameFromGraphId(request.graphRef().id());
        if (graphName.isBlank()) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_GRAPH_UNSUPPORTED",
                    "Proposal simulation v1 requires an exact built-in GraphDraft.", Map.of());
        }
        BuiltInGraphAssetAuthority.Snapshot graphAsset = graphAssets.resolve(scope, graphName);
        if (!graphAsset.graphRef().equals(request.graphRef())) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_GRAPH_STALE",
                    "The built-in GraphDraft differs from the reviewed Proposal graph.", Map.of());
        }
        CapabilityClosure baseClosure = graphAssets.resolveClosure(scope, graphName);
        requirePackage(request.packageRef(), request.graphRef(), graphAsset,
                baseClosure, scope, identity);

        Graph graph = graphs.requireGraph(graphName);
        String runtimeGraphFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        CapabilityProposalSimulationCompiler.Result overlay;
        try {
            overlay = compiler.compile(proposal, baseClosure, request.targetCapabilityRef(),
                    graphName, runtimeGraphFingerprint);
        } catch (IllegalArgumentException rejected) {
            throw conflict(identity, "RG.PROPOSAL.REAL_EXECUTION_FORBIDDEN",
                    "Proposal simulation admission rejected an unsafe or inconsistent closure.",
                    Map.of("reason", rejected.getMessage()));
        }

        List<StoredTestSuite> acceptanceSuites = exactSuites(
                draft, graphName, runtimeGraphFingerprint, scope, identity);
        List<CapabilityProposalSimulationEvidence.CaseEvidence> caseEvidence = new ArrayList<>();
        int suiteIndex = 0;
        for (StoredTestSuite storedSuite : acceptanceSuites) {
            TestSuite suite = (TestSuite) storedSuite.suite();
            List<MirrorEvidenceBundle> suiteRunEvidence = new ArrayList<>();
            int caseIndex = 0;
            for (TestSuite.TestCase testCase : suite.cases()) {
                renewLease(lease, identity);
                StoredFixtureBundle fixture = exactFixture(
                        draft, testCase, runtimeGraphFingerprint, scope, identity);
                String coordinate = stableCoordinate(
                        registration.requestFingerprint(), suiteIndex, caseIndex);
                Instant planExpiry = earlier(draft.expiresAt(), startedAt.plus(
                        MirrorPlanIntegrationService.MAXIMUM_PLAN_LIFETIME));
                MirrorPlan plan = plans.create(new MirrorPlanCreateRequest("",
                        "bm-proposal-plan-" + coordinate, graphName, runtimeGraphFingerprint,
                        overlay.simulatedClosure(), fixtureRef(fixture), 100_000,
                        Duration.ofMinutes(10), false, planExpiry), identity);
                MirrorRunSummary summary = runs.execute(new MirrorExecutionRequest("",
                        "bm-proposal-run-" + coordinate, plan.planId(), plan.planFingerprint(),
                        inputContext(testCase.input(), identity)), identity);
                MirrorEvidenceBundle bundle = runs.evidence(summary.runId(), identity);
                renewLease(lease, identity);
                suiteRunEvidence.add(bundle);
                caseEvidence.add(projectCase(storedSuite, testCase, fixture, plan, bundle,
                        overlay.temporaryCapabilityRef()));
                caseIndex++;
            }
            requireCoverage(suite, suiteRunEvidence, identity);
            suiteIndex++;
        }

        int proposalCalls = caseEvidence.stream()
                .mapToInt(CapabilityProposalSimulationEvidence.CaseEvidence::proposalCallCount)
                .sum();
        boolean casesPassed = caseEvidence.stream()
                .allMatch(value -> "PASSED".equals(value.runStatus()));
        boolean passed = casesPassed && proposalCalls > 0;
        Instant completedAt = clock.instant();
        if (!completedAt.isBefore(draft.expiresAt())) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_EXPIRED",
                    "The Capability Proposal expired before evidence commit.", Map.of());
        }
        List<String> limitations = new ArrayList<>(draft.limitations());
        limitations.add("SIMULATION_ONLY evidence does not prove a production implementation.");
        if (proposalCalls == 0) {
            limitations.add("The acceptance suite did not exercise the proposed capability.");
        }
        CapabilityProposalSimulationEvidence evidence = new CapabilityProposalSimulationEvidence(
                "", registration.simulationId(), "", scope,
                new MirrorArtifactRef("CAPABILITY_PROPOSAL_DRAFT", draft.proposalId(),
                        draft.revision(), proposal.draftFingerprint()),
                request.packageRef(), request.graphRef(), closureRef(overlay.baseClosure()),
                closureRef(overlay.simulatedClosure()), overlay.targetCapabilityRef(),
                overlay.temporaryCapabilityRef(), draft.businessAcceptanceSuiteRefs(),
                passed ? CapabilityProposalSimulationEvidence.Status.PASSED
                        : CapabilityProposalSimulationEvidence.Status.FAILED,
                caseEvidence, startedAt, completedAt, limitations,
                List.of("Production conformance and real-world outcome fidelity are not evaluated."))
                .seal(mapper);
        VisualRunEvidenceSeal attestation = signer.seal(evidence.fingerprint(),
                "proposal-simulation:" + scope.tenantId() + ":" + registration.simulationId());
        if (!attestation.signed()
                || !signer.verify(attestation, evidence.fingerprint()).valid()) {
            throw unavailable(identity, "RG.BUSINESS_MIRROR.PROPOSAL_EVIDENCE_SIGNER_UNAVAILABLE",
                    "Proposal simulation evidence could not be signed and verified.");
        }
        CapabilityProposalSnapshot snapshot = new CapabilityProposalSnapshot("", draft.proposalId(),
                draft.revision(), "", scope, draft.revision(), proposal.draftFingerprint(),
                draft.businessIntent(), draft.candidateContract(), draft.fixturePackRefs(),
                draft.businessAcceptanceSuiteRefs(), draft.simulationRuntimeBinding(), null,
                CapabilityProposalSnapshot.EvidenceState.SIMULATED,
                List.of(evidence.artifactRef()), draft.assumptions(), limitations,
                draft.expiresAt(), draft.provenance(), completedAt).seal(mapper);
        StoredCapabilityProposalSimulation result = new StoredCapabilityProposalSimulation("",
                registration.requestFingerprint(), evidence, attestation, snapshot, completedAt);
        renewLease(lease, identity);
        return verified(result, identity);
    }

    private void renewLease(
            CapabilityProposalSimulationRepository.Lease lease,
            IntegrationRequestContext identity) {
        if (!simulations.renew(lease, LEASE_DURATION)) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_LEASE_LOST",
                    "Proposal simulation authority expired during batch execution.", Map.of());
        }
    }

    private StoredCapabilityProposalDraft exactProposal(
            String proposalId,
            long revision,
            String expectedFingerprint,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        StoredCapabilityProposalDraft stored = proposals.findRevision(scope, proposalId, revision)
                .orElseThrow(() -> notFound(identity, proposalId, revision));
        String actual = VisualBundleFingerprint.fromCanonicalValue(
                mapper, stored.draft(), 4 * 1024 * 1024);
        if (!actual.equals(stored.draftFingerprint())
                || !actual.equals(expectedFingerprint)) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_DRAFT_STALE",
                    "The Proposal draft differs from the reviewed exact revision.", Map.of());
        }
        if (!stored.draft().readinessBlockers().isEmpty()) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_NOT_READY",
                    "The Proposal still has authoring readiness blockers.", Map.of(
                    "blockers", stored.draft().readinessBlockers()));
        }
        return stored;
    }

    private void requirePackage(
            MirrorArtifactRef packageRef,
            MirrorArtifactRef graphRef,
            BuiltInGraphAssetAuthority.Snapshot graphAsset,
            CapabilityClosure closure,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        PackageCompilationReceipt receipt = packages.find(
                        scope, packageRef.id(), packageRef.revision())
                .orElseThrow(() -> conflict(identity,
                        "RG.BUSINESS_MIRROR.PROPOSAL_PACKAGE_NOT_FOUND",
                        "The exact compiled Package was not found.", Map.of()));
        DomainCapabilityPackageSnapshot snapshot = receipt.snapshot();
        if (snapshot == null) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_PACKAGE_BLOCKED",
                    "The Package compilation is blocked and has no immutable snapshot.", Map.of());
        }
        snapshot.verify(mapper);
        if (!snapshot.artifactRef().equals(packageRef)
                || !snapshot.dependencyManifest().contains(graphRef)
                || !snapshot.capabilityClosureRef().equals(graphAsset.capabilityClosureRef())
                || !snapshot.capabilityClosureRef().equals(closureRef(closure))) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_PACKAGE_STALE",
                    "The Package does not bind the reviewed Graph and capability closure.", Map.of());
        }
    }

    private List<StoredTestSuite> exactSuites(
            CapabilityProposalDraft draft,
            String graphName,
            String graphFingerprint,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        if (draft.businessAcceptanceSuiteRefs().stream()
                .anyMatch(ref -> !"TEST_SUITE".equals(ref.kind()))) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_SCENARIO_PACK_UNAVAILABLE",
                    "Proposal simulation v1 requires direct TEST_SUITE references.", Map.of());
        }
        TestingArtifactScope testingScope = testingScope(scope);
        List<StoredTestSuite> result = new ArrayList<>();
        for (MirrorArtifactRef ref : draft.businessAcceptanceSuiteRefs()) {
            StoredTestSuite stored = suites.find(testingScope, ref.id(), ref.revision())
                    .map(value -> StoredTestSuiteIntegrity.verifiedSnapshot(
                            mapper, value, testingScope, ref.id(), ref.revision()))
                    .orElseThrow(() -> conflict(identity,
                            "RG.BUSINESS_MIRROR.PROPOSAL_SUITE_NOT_FOUND",
                            "An exact Proposal acceptance suite was not found.", Map.of()));
            if (!stored.fingerprint().equals(ref.fingerprint())
                    || !(stored.suite() instanceof TestSuite suite)
                    || !"GRAPH".equals(suite.target().kind())
                    || !graphName.equals(suite.target().id())
                    || !graphFingerprint.equals(suite.target().fingerprint())) {
                throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_SUITE_STALE",
                        "An acceptance suite does not target the reviewed runtime Graph.", Map.of());
            }
            result.add(stored);
        }
        result.sort(Comparator.comparing(StoredTestSuite::suiteId)
                .thenComparingLong(StoredTestSuite::revision));
        return List.copyOf(result);
    }

    private StoredFixtureBundle exactFixture(
            CapabilityProposalDraft draft,
            TestSuite.TestCase testCase,
            String graphFingerprint,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        TestSuite.FixtureBundleRef caseRef = testCase.fixtureBundleRef();
        MirrorArtifactRef ref = new MirrorArtifactRef("FIXTURE_BUNDLE",
                caseRef.fixtureBundleId(), caseRef.revision(), caseRef.fingerprint());
        if (!draft.fixturePackRefs().contains(ref)) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_FIXTURE_NOT_DECLARED",
                    "An acceptance case references a Fixture outside the Proposal pack.", Map.of());
        }
        TestingArtifactScope testingScope = testingScope(scope);
        StoredFixtureBundle stored = fixtures.find(
                        testingScope, ref.id(), ref.revision())
                .map(value -> StoredFixtureBundleIntegrity.verifiedSnapshot(
                        mapper, value, testingScope, ref.id(), ref.revision()))
                .orElseThrow(() -> conflict(identity,
                        "RG.BUSINESS_MIRROR.PROPOSAL_FIXTURE_NOT_FOUND",
                        "An exact Proposal Fixture was not found.", Map.of()));
        if (!stored.fingerprint().equals(ref.fingerprint())
                || !stored.bundle().targetFingerprint().equals(graphFingerprint)) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_FIXTURE_STALE",
                    "A Proposal Fixture does not target the reviewed runtime Graph.", Map.of());
        }
        requireIsolatedFixture(stored.bundle(), identity);
        return stored;
    }

    static void requireIsolatedFixture(
            FixtureBundle fixture, IntegrationRequestContext identity) {
        boolean unsafe = fixture.rules().stream().anyMatch(rule ->
                rule.behavior().kind() == FixtureRule.BehaviorKind.REAL
                        || rule.behavior().kind() == FixtureRule.BehaviorKind.SPY
                        || rule.behavior().kind() == FixtureRule.BehaviorKind.STREAM
                        || rule.consumption().onExhausted()
                        == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL
                        || rule.consumption().onUnmatched()
                        == FixtureRule.UnmatchedAction.ALLOW_REAL);
        if (unsafe) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.PROPOSAL.REAL_EXECUTION_FORBIDDEN",
                    "Proposal Fixtures cannot call, observe, or fall back to real dependencies.",
                    identity.correlationId(), Map.of()));
        }
    }

    private CapabilityProposalSimulationEvidence.CaseEvidence projectCase(
            StoredTestSuite storedSuite,
            TestSuite.TestCase testCase,
            StoredFixtureBundle fixture,
            MirrorPlan plan,
            MirrorEvidenceBundle bundle,
            MirrorArtifactRef proposalCapability) {
        MirrorRunEvidence evidence = bundle.evidence();
        List<MirrorResolution> resolutions = evidence.resolutions().stream()
                .filter(value -> value.capabilityRef().equals(proposalCapability)).toList();
        Set<String> limitations = new LinkedHashSet<>(evidence.limitations());
        resolutions.forEach(value -> limitations.addAll(value.limitations()));
        return new CapabilityProposalSimulationEvidence.CaseEvidence(testCase.caseId(),
                testCase.caseType(), new MirrorArtifactRef("TEST_SUITE", storedSuite.suiteId(),
                storedSuite.revision(), storedSuite.fingerprint()), fixtureRef(fixture),
                new MirrorArtifactRef("MIRROR_PLAN", plan.planId(), 1,
                        plan.planFingerprint()),
                new MirrorArtifactRef("MIRROR_EVIDENCE_BUNDLE", evidence.runId(), 1,
                        bundle.bundleFingerprint()), evidence.status().name(),
                resolutions.stream().map(value -> value.source().name()).distinct().toList(),
                resolutions.stream().flatMap(value -> value.matchedRuleRefs().stream())
                        .distinct().toList(), resolutions.size(), List.copyOf(limitations));
    }

    private static void requireCoverage(
            TestSuite suite,
            List<MirrorEvidenceBundle> evidence,
            IntegrationRequestContext identity) {
        Set<String> sites = new LinkedHashSet<>();
        Set<TestSuite.EdgeTransferRef> edges = new LinkedHashSet<>();
        evidence.forEach(bundle -> {
            bundle.evidence().nodeTraces().forEach(trace -> sites.add(trace.invocationSiteId()));
            bundle.evidence().edgeTraces().forEach(trace -> edges.add(
                    new TestSuite.EdgeTransferRef(
                            trace.fromInvocationSiteId(), trace.toInvocationSiteId())));
        });
        if (!sites.containsAll(suite.coveragePolicy().requiredInvocationSiteIds())
                || !edges.containsAll(suite.coveragePolicy().requiredEdgeTransfers())) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_SUITE_COVERAGE_FAILED",
                    "Proposal acceptance evidence does not satisfy required structural coverage.",
                    Map.of());
        }
    }

    private static void requireDeclared(
            CapabilityProposalDraft draft,
            CapabilityProposalSimulationRequest request,
            IntegrationRequestContext identity) {
        if (!draft.businessIntent().candidatePackageRefs().contains(request.packageRef())
                || !draft.businessIntent().candidateGraphRefs().contains(request.graphRef())) {
            throw conflict(identity, "RG.BUSINESS_MIRROR.PROPOSAL_DEPENDENCY_NOT_DECLARED",
                    "The simulation Package and Graph must be exact Proposal dependencies.", Map.of());
        }
    }

    private StoredCapabilityProposalSimulation verified(
            StoredCapabilityProposalSimulation result,
            IntegrationRequestContext identity) {
        try {
            Objects.requireNonNull(result, "result").verify(mapper, signer);
            if (!scope(identity).equals(result.evidence().scope())) {
                throw new IllegalArgumentException("simulation Scope mismatch");
            }
            return result;
        } catch (RuntimeException invalid) {
            throw unavailable(identity, "RG.BUSINESS_MIRROR.PROPOSAL_EVIDENCE_INVALID",
                    "Stored Proposal simulation evidence failed independent verification.");
        }
    }

    private Map<String, Object> inputContext(
            Object input, IntegrationRequestContext identity) {
        if (!(input instanceof Map<?, ?>)) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_CASE_INPUT_INVALID",
                    "Graph acceptance cases require an object input context.", Map.of());
        }
        try {
            return mapper.convertValue(input, new TypeReference<Map<String, Object>>() { });
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_CASE_INPUT_INVALID",
                    "Graph acceptance case input is not a valid JSON object.", Map.of());
        }
    }

    private static MirrorArtifactRef fixtureRef(StoredFixtureBundle fixture) {
        return new MirrorArtifactRef("FIXTURE_BUNDLE", fixture.fixtureBundleId(),
                fixture.revision(), fixture.fingerprint());
    }

    private static MirrorArtifactRef closureRef(CapabilityClosure closure) {
        return new MirrorArtifactRef("CAPABILITY_CLOSURE", closure.rootRef().id(),
                closure.rootRef().revision(), closure.fingerprint());
    }

    private static String stableCoordinate(
            String requestFingerprint, int suiteIndex, int caseIndex) {
        return requestFingerprint.substring("sha256:".length(), "sha256:".length() + 20)
                + "-s" + suiteIndex + "-c" + caseIndex;
    }

    private static TestingArtifactScope testingScope(CapabilitySnapshot.Scope scope) {
        return new TestingArtifactScope(scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region());
    }

    private static CapabilitySnapshot.Scope requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!MirrorPlanIntegrationService.AUTHORIZED_PURPOSE.equals(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_PURPOSE_FORBIDDEN",
                    "Proposal simulation requires the MIRROR_REHEARSAL workload purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!Set.of("test", "staging").contains(identity.environmentId().toLowerCase())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_ENVIRONMENT_FORBIDDEN",
                    "Proposal simulation is available only in test or staging.",
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
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_ID_INVALID",
                    field + " is invalid.", Map.of());
        }
        return exact;
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static String failureCode(RuntimeException failure) {
        if (failure instanceof IntegrationProblemException problem) {
            return problem.problem().code();
        }
        return "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_UNAVAILABLE";
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity, String proposalId, long revision) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.BUSINESS_MIRROR.PROPOSAL_SIMULATION_NOT_FOUND",
                "The Proposal revision or completed simulation was not found in this Scope.",
                identity.correlationId(), Map.of(
                "proposalId", proposalId, "revision", revision)));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }
}
