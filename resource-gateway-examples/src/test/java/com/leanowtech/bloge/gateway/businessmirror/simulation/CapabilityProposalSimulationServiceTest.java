package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.gateway.businessmirror.authoring.CapabilityProposalDraftRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.compilation.BuiltInGraphAssetAuthority;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshotIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanCreateRequest;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunSummary;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityProposalSimulationServiceTest {
    private static final Instant AT = Instant.parse("2026-08-14T10:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void runsExactAcceptanceSuiteThroughMirrorAuthoritiesAndOmitsPayloadFromAggregate()
            throws Exception {
        Graph graph = graph();
        String graphFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        CapabilityClosure baseClosure = baseClosure();
        MirrorArtifactRef graphRef = ref("GRAPH_DRAFT", "built-in:refundGraph", '3');
        MirrorArtifactRef packageRef = ref(
                "DOMAIN_CAPABILITY_PACKAGE", "refund-package", '2');
        MirrorArtifactRef targetRef = baseClosure.snapshots().stream()
                .filter(value -> value.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference).findFirst().orElseThrow();
        StoredFixtureBundle fixture = fixture(graphFingerprint);
        StoredTestSuite suite = suite(graphFingerprint, fixture);
        StoredCapabilityProposalDraft proposal = proposal(
                packageRef, graphRef, fixture, suite);

        CapabilityProposalDraftRepository proposals = mock(CapabilityProposalDraftRepository.class);
        when(proposals.findRevision(SCOPE, proposal.proposalId(), proposal.revision()))
                .thenReturn(Optional.of(proposal));
        PackageCompilationFactRepository packages = mock(PackageCompilationFactRepository.class);
        PackageCompilationReceipt receipt = mock(PackageCompilationReceipt.class);
        DomainCapabilityPackageSnapshot packageSnapshot = mock(DomainCapabilityPackageSnapshot.class);
        when(receipt.snapshot()).thenReturn(packageSnapshot);
        when(packageSnapshot.artifactRef()).thenReturn(packageRef);
        when(packageSnapshot.dependencyManifest()).thenReturn(List.of(graphRef));
        when(packageSnapshot.capabilityClosureRef()).thenReturn(closureRef(baseClosure));
        when(packages.find(SCOPE, packageRef.id(), packageRef.revision()))
                .thenReturn(Optional.of(receipt));

        BuiltInGraphAssetAuthority graphAssets = mock(BuiltInGraphAssetAuthority.class);
        BuiltInGraphAssetAuthority.Snapshot graphAsset = new BuiltInGraphAssetAuthority.Snapshot(
                SCOPE, graph.name(), graphRef, ref("CONTRACT", "refund-contract", '4'),
                baseClosure.rootRef(), closureRef(baseClosure), List.of());
        when(graphAssets.resolve(SCOPE, graph.name())).thenReturn(graphAsset);
        when(graphAssets.resolveClosure(SCOPE, graph.name())).thenReturn(baseClosure);
        GatewayGraphService graphs = mock(GatewayGraphService.class);
        when(graphs.requireGraph(graph.name())).thenReturn(graph);
        TestSuiteRepository suites = mock(TestSuiteRepository.class);
        when(suites.find(any(), org.mockito.ArgumentMatchers.eq(suite.suiteId()),
                org.mockito.ArgumentMatchers.eq(suite.revision())))
                .thenReturn(Optional.of(suite));
        FixtureBundleRepository fixtures = mock(FixtureBundleRepository.class);
        when(fixtures.find(any(), org.mockito.ArgumentMatchers.eq(fixture.fixtureBundleId()),
                org.mockito.ArgumentMatchers.eq(fixture.revision())))
                .thenReturn(Optional.of(fixture));

        InMemorySimulationRepository simulations = new InMemorySimulationRepository();
        MirrorPlanIntegrationService plans = mock(MirrorPlanIntegrationService.class);
        MirrorPlan plan = mock(MirrorPlan.class);
        when(plan.planId()).thenReturn("proposal-plan");
        when(plan.planFingerprint()).thenReturn(fingerprint('d'));
        AtomicReference<MirrorArtifactRef> temporaryCapability = new AtomicReference<>();
        when(plans.create(any(), any())).thenAnswer(invocation -> {
            MirrorPlanCreateRequest command = invocation.getArgument(0);
            temporaryCapability.set(command.capabilityClosure().snapshots().stream()
                    .filter(value -> "SIMULATION_ONLY".equals(value.runtime().kind()))
                    .map(CapabilityClosureIntegrity::reference).findFirst().orElseThrow());
            return plan;
        });
        MirrorRunIntegrationService runs = mock(MirrorRunIntegrationService.class);
        MirrorRunSummary summary = mock(MirrorRunSummary.class);
        when(summary.runId()).thenReturn("proposal-run");
        when(runs.execute(any(), any())).thenReturn(summary);
        when(runs.evidence("proposal-run", identity())).thenAnswer(invocation ->
                evidenceBundle(temporaryCapability.get()));
        VisualEvidenceSigner signer = signer();
        Clock clock = Clock.fixed(AT.plusSeconds(5), ZoneOffset.UTC);
        CapabilityProposalSimulationService service = new CapabilityProposalSimulationService(
                proposals, packages, graphAssets, graphs, suites, fixtures, simulations,
                plans, runs, signer, mapper, clock);
        CapabilityProposalSimulationRequest command = new CapabilityProposalSimulationRequest("",
                proposal.draftFingerprint(), packageRef, graphRef, targetRef);

        StoredCapabilityProposalSimulation result = service.simulate(
                proposal.proposalId(), proposal.revision(), "simulation-1", command, identity());
        StoredCapabilityProposalSimulation replay = service.simulate(
                proposal.proposalId(), proposal.revision(), "simulation-1", command, identity());

        assertThat(result).isEqualTo(replay);
        assertThat(result.evidence().status())
                .isEqualTo(CapabilityProposalSimulationEvidence.Status.PASSED);
        assertThat(result.evidence().cases()).singleElement().satisfies(caseEvidence -> {
            assertThat(caseEvidence.proposalCallCount()).isEqualTo(1);
            assertThat(caseEvidence.resolverSources()).containsExactly("OWNER_SPECIFIED");
            assertThat(caseEvidence.matchedRuleRefs()).containsExactly("proposal-rule");
        });
        assertThat(result.proposalSnapshot().evidenceState())
                .isEqualTo(com.leanowtech.bloge.gateway.businessmirror.domain
                        .CapabilityProposalSnapshot.EvidenceState.SIMULATED);
        assertThat(mapper.writeValueAsString(result))
                .doesNotContain("business-payload-must-not-persist");
        verify(plans, times(1)).create(any(), any());
        verify(runs, times(1)).execute(any(), any());
        assertThat(simulations.renewals).isEqualTo(3);

        ArgumentCaptor<MirrorPlanCreateRequest> planCommand =
                ArgumentCaptor.forClass(MirrorPlanCreateRequest.class);
        verify(plans).create(planCommand.capture(), any());
        assertThat(planCommand.getValue().capabilityClosure()).isNotEqualTo(baseClosure);
        assertThat(planCommand.getValue().expectedGraphArtifactFingerprint())
                .isEqualTo(graphFingerprint);
    }

    private MirrorEvidenceBundle evidenceBundle(MirrorArtifactRef temporaryCapability) {
        MirrorResolution resolution = mock(MirrorResolution.class);
        when(resolution.capabilityRef()).thenReturn(temporaryCapability);
        when(resolution.source()).thenReturn(MirrorPlan.MirrorSource.OWNER_SPECIFIED);
        when(resolution.matchedRuleRefs()).thenReturn(List.of("proposal-rule"));
        when(resolution.limitations()).thenReturn(List.of());
        MirrorRunEvidence evidence = mock(MirrorRunEvidence.class);
        when(evidence.runId()).thenReturn("proposal-run");
        when(evidence.status()).thenReturn(MirrorRunEvidence.Status.PASSED);
        when(evidence.resolutions()).thenReturn(List.of(resolution));
        when(evidence.nodeTraces()).thenReturn(List.of());
        when(evidence.edgeTraces()).thenReturn(List.of());
        when(evidence.limitations()).thenReturn(List.of());
        MirrorEvidenceBundle bundle = mock(MirrorEvidenceBundle.class);
        when(bundle.evidence()).thenReturn(evidence);
        when(bundle.bundleFingerprint()).thenReturn(fingerprint('e'));
        return bundle;
    }

    private VisualEvidenceSigner signer() {
        VisualEvidenceSigner signer = mock(VisualEvidenceSigner.class);
        when(signer.seal(any(), any())).thenAnswer(invocation ->
                new VisualRunEvidenceSeal("", invocation.getArgument(0), "TEST", "key-1",
                        AT, "signature"));
        when(signer.verify(any(), any())).thenReturn(
                new VisualEvidenceSigner.Verification(true, "VERIFIED", ""));
        return signer;
    }

    private StoredCapabilityProposalDraft proposal(
            MirrorArtifactRef packageRef,
            MirrorArtifactRef graphRef,
            StoredFixtureBundle fixture,
            StoredTestSuite suite) {
        MirrorArtifactRef fixtureRef = new MirrorArtifactRef("FIXTURE_BUNDLE",
                fixture.fixtureBundleId(), fixture.revision(), fixture.fingerprint());
        MirrorArtifactRef suiteRef = new MirrorArtifactRef("TEST_SUITE",
                suite.suiteId(), suite.revision(), suite.fingerprint());
        CapabilityProposalDraft draft = new CapabilityProposalDraft("", "refund-proposal", 1,
                SCOPE, new CapabilityProposalDraft.BusinessIntent(
                "Refund lookup is missing", "Validate the complete refund journey",
                List.of(ref("SCENARIO_CASE", "refund-approved", '5')),
                List.of(packageRef), List.of(graphRef), "owner"),
                contract(), List.of(fixtureRef), List.of(suiteRef),
                new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                        ref("FIXTURE_RESOLVER_POLICY", "fixture-only", '6'),
                        false, false, false), List.of(), List.of(),
                AT.plus(Duration.ofDays(7)), provenance(),
                CapabilityProposalDraft.Lifecycle.DRAFT);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, draft, 4 * 1024 * 1024);
        return new StoredCapabilityProposalDraft("", fingerprint, draft, AT, AT, "owner");
    }

    private StoredTestSuite suite(
            String graphFingerprint, StoredFixtureBundle fixture) {
        TestSuite suite = new TestSuite("", "refund-suite", 1,
                new TestSuite.Target("GRAPH", "refundGraph", graphFingerprint), "INTERNAL",
                List.of(new TestSuite.TestCase("case-001", TestSuite.CaseType.GOLDEN,
                        Map.of("request", "business-payload-must-not-persist"),
                        new TestSuite.FixtureBundleRef(fixture.fixtureBundleId(),
                                fixture.revision(), fixture.fingerprint()),
                        List.of("proposal"), Map.of())),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of(), List.of(), 0, true),
                TestSuite.PromotionPolicy.defaults(), Map.of());
        String fingerprint = new TestSuiteProtocolCodec(mapper).fingerprint(suite);
        return new StoredTestSuite("", SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(), suite.suiteId(),
                suite.revision(), fingerprint, suite, AT, "owner");
    }

    private StoredFixtureBundle fixture(String graphFingerprint) {
        FixtureRule rule = new FixtureRule("", "proposal-rule",
                FixtureRule.Selector.node("lookupRefund"),
                FixtureRule.Behavior.returning(Map.of("approved", true)),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        FixtureBundle fixture = new FixtureBundle("", "refund-fixture", 1,
                graphFingerprint, "INTERNAL", AT, 42L, List.of(rule), List.of(), Map.of());
        String fingerprint = ProtocolFingerprint.of(mapper, fixture);
        return new StoredFixtureBundle("", SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(),
                fixture.fixtureBundleId(), fixture.revision(), fingerprint, fixture, AT, "owner");
    }

    private CapabilityClosure baseClosure() {
        CapabilitySnapshot child = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "operator:refundLookup", 1, "",
                        CapabilitySnapshot.Kind.EXTERNAL, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.OPERATOR,
                                "refundLookup", fingerprint('7')),
                        contract(), runtime("refundLookup"), List.of(), ownership(),
                        CapabilitySnapshot.Lifecycle.DRAFT, provenance(), AT));
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "graph:refundGraph", 1, "",
                        CapabilitySnapshot.Kind.COMPOSED, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                                "built-in:refundGraph", fingerprint('8')),
                        contract(), runtime("refundGraph"),
                        List.of(new CapabilitySnapshot.Dependency("lookupRefund",
                                CapabilityClosureIntegrity.reference(child), true, List.of())),
                        ownership(), CapabilitySnapshot.Lifecycle.DRAFT, provenance(), AT));
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, child), ""));
    }

    private static Graph graph() {
        Operator<Object, Object> operator = (input, context) -> input;
        return new GraphBuilder("refundGraph").node("lookupRefund", operator).build();
    }

    private static CapabilityContract contract() {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(), EffectContract.readOnly(List.of("refund/*")),
                CapabilityContract.Determinism.DETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.INTERNAL, false,
                        List.of("sg"), false),
                new CapabilityContract.SloContract(
                        Duration.ofSeconds(2), 0.99, 500L, "owner"));
    }

    private static CapabilitySnapshot.RuntimeBinding runtime(String id) {
        return new CapabilitySnapshot.RuntimeBinding(
                "TEST", id, fingerprint('9'), true, List.of());
    }

    private static CapabilitySnapshot.Ownership ownership() {
        return new CapabilitySnapshot.Ownership("owner", "team", "on-call");
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "proposal-test", null, null, null, null,
                List.of(), "", null, null, "");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(), "WORKLOAD", "tester",
                "", "MIRROR_REHEARSAL", "correlation-1", Set.of("mirror-operators"),
                "RESTRICTED", "");
    }

    private static MirrorArtifactRef closureRef(CapabilityClosure closure) {
        return new MirrorArtifactRef("CAPABILITY_CLOSURE", closure.rootRef().id(),
                closure.rootRef().revision(), closure.fingerprint());
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class InMemorySimulationRepository
            implements CapabilityProposalSimulationRepository {
        private State state;
        private int renewals;

        @Override
        public Claim claim(Registration registration, String leaseOwner, Duration leaseDuration) {
            if (state != null) {
                if (!state.registration().equals(registration)) {
                    throw new IllegalArgumentException("different command material");
                }
                return state.status() == Status.COMPLETED
                        ? new Claim(Outcome.COMPLETED, state, null, 0)
                        : new Claim(Outcome.IN_PROGRESS, state, null, 1);
            }
            Lease lease = new Lease(registration.scope(), registration.proposalId(),
                    registration.proposalRevision(), registration.simulationId(), leaseOwner, 1);
            state = new State(registration, Status.ACTIVE, leaseOwner, 1,
                    AT.plus(leaseDuration), null, "", AT, AT);
            return new Claim(Outcome.ACQUIRED, state, lease, 0);
        }

        @Override
        public boolean complete(Lease lease, StoredCapabilityProposalSimulation result) {
            state = new State(state.registration(), Status.COMPLETED, state.leaseOwner(),
                    state.leaseEpoch(), state.leaseExpiresAt(), result, "", AT, result.completedAt());
            return true;
        }

        @Override
        public boolean renew(Lease lease, Duration leaseDuration) {
            renewals++;
            state = new State(state.registration(), state.status(), state.leaseOwner(),
                    state.leaseEpoch(), state.leaseExpiresAt().plus(leaseDuration), state.result(),
                    state.lastFailureCode(), state.createdAt(), AT);
            return true;
        }

        @Override
        public boolean release(Lease lease, String failureCode) {
            return true;
        }

        @Override
        public Optional<State> find(
                CapabilitySnapshot.Scope scope, String proposalId, long proposalRevision) {
            return Optional.ofNullable(state).filter(value -> value.registration().scope().equals(scope))
                    .filter(value -> value.registration().proposalId().equals(proposalId))
                    .filter(value -> value.registration().proposalRevision() == proposalRevision);
        }
    }
}
