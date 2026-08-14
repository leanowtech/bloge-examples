package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.businessmirror.authoring.CapabilityProposalDraftRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationEvidence;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationRepository;
import com.leanowtech.bloge.gateway.businessmirror.simulation.StoredCapabilityProposalSimulation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorResolution;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunEvidence;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRepository;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteProtocolCodec;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityImplementationConformanceServiceTest {
    private static final Instant AT = Instant.parse("2026-08-14T10:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger implementationCalls = new AtomicInteger();
    private Graph graph;
    private DefaultOperatorRegistry operators;
    private FixtureBundle fixture;
    private MirrorArtifactRef fixtureRef;
    private MirrorArtifactRef suiteRef;
    private MirrorArtifactRef proposalRef;
    private MirrorArtifactRef simulationRef;
    private MirrorArtifactRef temporaryCapability;
    private MirrorArtifactRef targetCapability;
    private CapabilityImplementationBinding binding;
    private CapabilityImplementationRuntimePort runtime;
    private StoredCapabilityProposalDraft proposal;
    private StoredTestSuite suite;
    private String targetSite;
    private TestExecutionResult baselineExecution;

    @BeforeEach
    void setUp() {
        Graph raw = new GraphBuilder("refundGraph")
                .node("lookupRefund", new OriginalOperator())
                .build();
        graph = withoutEmbeddedOperators(raw);
        operators = new DefaultOperatorRegistry();
        graph.nodes().values().forEach(node -> operators.register(
                node.operatorRef(), new OriginalOperator()));
        String graphFingerprint = fingerprint('1');
        var inventory = new com.leanowtech.bloge.gateway.testing.planning
                .InvocationInventoryBuilder(operators).build(graph, graphFingerprint);
        targetSite = inventory.entries().getFirst().site().invocationSiteId();
        fixture = new FixtureBundle("", "refund-fixture", 1, graphFingerprint, "INTERNAL",
                AT, 42L, List.of(new FixtureRule("", "target-rule",
                FixtureRule.Selector.node("lookupRefund"),
                FixtureRule.Behavior.returning(Map.of("approved", true)),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict())),
                List.of(), Map.of());
        fixtureRef = new MirrorArtifactRef("FIXTURE_BUNDLE", fixture.fixtureBundleId(),
                fixture.revision(), ProtocolFingerprint.of(mapper, fixture));
        suite = suite(fixtureRef);
        suiteRef = new MirrorArtifactRef("TEST_SUITE", suite.suiteId(), suite.revision(),
                suite.fingerprint());
        proposal = proposal();
        proposalRef = new MirrorArtifactRef("CAPABILITY_PROPOSAL_DRAFT", proposal.proposalId(),
                proposal.revision(), proposal.draftFingerprint());
        simulationRef = ref("PROPOSAL_SIMULATION_EVIDENCE", "simulation-1", '2');
        temporaryCapability = ref("CAPABILITY", "temporary-refund", '3');
        targetCapability = ref("CAPABILITY", "target-refund", '4');
        runtime = runtime();
        binding = binding();
        baselineExecution = executeImplementationOnce();
        implementationCalls.set(0);
    }

    @Test
    void producesConformantSnapshotAndDoesNotReinvokeOnExactRetry() {
        CapabilityProposalDraftRepository proposals = mock(CapabilityProposalDraftRepository.class);
        when(proposals.findRevision(SCOPE, proposal.proposalId(), proposal.revision()))
                .thenReturn(Optional.of(proposal));
        CapabilityProposalSimulationEvidence simulationEvidence = simulationEvidence();
        StoredCapabilityProposalSimulation simulation = mock(
                StoredCapabilityProposalSimulation.class);
        when(simulation.evidence()).thenReturn(simulationEvidence);
        CapabilityProposalSimulationRepository simulations = mock(
                CapabilityProposalSimulationRepository.class);
        when(simulations.find(SCOPE, proposal.proposalId(), proposal.revision()))
                .thenReturn(Optional.of(new CapabilityProposalSimulationRepository.State(
                        new CapabilityProposalSimulationRepository.Registration(SCOPE,
                                simulationRef.id(), proposal.proposalId(), proposal.revision(),
                                fingerprint('5')),
                        CapabilityProposalSimulationRepository.Status.COMPLETED, "", 1,
                        AT.plusSeconds(60), simulation, "", AT, AT)));
        CapabilityImplementationBindingService bindingService = mock(
                CapabilityImplementationBindingService.class);
        StoredCapabilityImplementationBinding storedBinding = mock(
                StoredCapabilityImplementationBinding.class);
        when(storedBinding.binding()).thenReturn(binding);
        when(bindingService.find(binding.bindingId(), identity())).thenReturn(storedBinding);
        InMemoryConformanceRepository conformances = new InMemoryConformanceRepository();
        TestSuiteRepository suites = mock(TestSuiteRepository.class);
        when(suites.find(any(), eq(suite.suiteId()), eq(suite.revision())))
                .thenReturn(Optional.of(suite));
        MirrorPlanIntegrationService plans = mock(MirrorPlanIntegrationService.class);
        when(plans.materializeForConformance(any(), any())).thenAnswer(ignored -> baseline());
        MirrorEvidenceRepository mirrorEvidence = mock(MirrorEvidenceRepository.class);
        MirrorEvidenceBundle baselineEvidence = baselineEvidence();
        when(mirrorEvidence.find(SCOPE, "mirror-run-1"))
                .thenReturn(Optional.of(baselineEvidence));
        VisualEvidenceSigner signer = signer();
        CapabilityImplementationConformanceService service =
                new CapabilityImplementationConformanceService(proposals, simulations,
                        bindingService, conformances, runtime, suites, plans, mirrorEvidence,
                        operators, null, signer, mapper,
                        Clock.fixed(AT.plusSeconds(5), ZoneOffset.UTC));
        CapabilityImplementationConformanceRequest request =
                new CapabilityImplementationConformanceRequest("", binding.artifactRef(),
                        simulationRef, proposal.draftFingerprint());

        StoredCapabilityImplementationConformance result = service.conform(
                proposal.proposalId(), proposal.revision(), "conformance-1", request, identity());
        StoredCapabilityImplementationConformance replay = service.conform(
                proposal.proposalId(), proposal.revision(), "conformance-1", request, identity());

        assertThat(replay).isEqualTo(result);
        assertThat(result.report().status())
                .withFailMessage("Unexpected conformance mismatches: %s", result.report().cases())
                .isEqualTo(CapabilityImplementationConformanceReport.Status.PASSED);
        assertThat(result.report().cases()).singleElement().satisfies(comparison -> {
            assertThat(comparison.comparison())
                    .isEqualTo(CapabilityImplementationConformanceReport.Comparison.MATCH);
            assertThat(comparison.baselineTargetCallCount()).isEqualTo(1);
            assertThat(comparison.implementationTargetCallCount()).isEqualTo(1);
            assertThat(comparison.targetInvocationSiteIds()).containsExactly(targetSite);
        });
        assertThat(result.proposalSnapshot().evidenceState())
                .isEqualTo(CapabilityProposalSnapshot.EvidenceState.CONFORMANT);
        assertThat(implementationCalls).hasValue(1);
        verify(plans, times(1)).materializeForConformance(any(), any());
    }

    private TestExecutionResult executeImplementationOnce() {
        CompiledMirrorPlan baseline = baseline();
        CapabilityImplementationConformancePlanCompiler.Result compiled =
                new CapabilityImplementationConformancePlanCompiler(mapper).compile(
                        baseline, temporaryCapability, binding, "precompute", "suite:case");
        ConformanceOperatorRegistry registry = new ConformanceOperatorRegistry(
                operators, compiled.targetOperatorRefs(), compiled.runtimeCoordinates(), binding,
                runtime, "precompute", "suite:case",
                Clock.fixed(AT.plusSeconds(2), ZoneOffset.UTC));
        CompiledExecutionControl control = compiled.bindTargetOperator(registry.targetOperator());
        return new TestRunService(registry, mapper, null).executeCompiled(
                new TestExecutionRequest(graph, new GraphContext(Map.of()), compiled.fixture(),
                        CapabilityImplementationConformancePlanCompiler.AUTHORIZED_PURPOSE,
                        fixture.targetFingerprint(), TestExecutionRequest.FixtureSource.STORED,
                        Map.of(), false, control.replayPayloads(), ResolvedTestSecrets.empty()),
                control);
    }

    private CompiledMirrorPlan baseline() {
        CompiledExecutionControl control = new ExecutionControlCompiler(operators, mapper)
                .compileMirror(graph, fixture, "MIRROR_REHEARSAL", fixture.targetFingerprint(),
                        ResolvedReplayPayloads.empty(), Set.of(targetSite));
        MirrorPlan plan = mock(MirrorPlan.class);
        when(plan.externalBindings()).thenReturn(List.of(new MirrorPlan.ExternalBinding(
                ref("CAPABILITY", "root", '6'), "lookupRefund", temporaryCapability,
                targetSite, "/root", CapabilitySnapshot.SourceKind.OPERATOR, "refundLookup",
                List.of(MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                        MirrorPlan.MirrorSource.ABSTAINED), List.of("target-rule"))));
        when(plan.planFingerprint()).thenReturn(fingerprint('7'));
        when(plan.planId()).thenReturn("mirror-plan-1");
        when(plan.fixtureBundleRef()).thenReturn(fixtureRef);
        CompiledMirrorPlan baseline = mock(CompiledMirrorPlan.class);
        when(baseline.plan()).thenReturn(plan);
        when(baseline.graph()).thenReturn(graph);
        when(baseline.fixtureBundle()).thenReturn(fixture);
        when(baseline.executionControl()).thenReturn(control);
        return baseline;
    }

    private CapabilityProposalSimulationEvidence simulationEvidence() {
        CapabilityProposalSimulationEvidence evidence = mock(
                CapabilityProposalSimulationEvidence.class);
        when(evidence.artifactRef()).thenReturn(simulationRef);
        when(evidence.status()).thenReturn(CapabilityProposalSimulationEvidence.Status.PASSED);
        when(evidence.proposalDraftRef()).thenReturn(proposalRef);
        when(evidence.targetCapabilityRef()).thenReturn(targetCapability);
        when(evidence.temporaryCapabilityRef()).thenReturn(temporaryCapability);
        when(evidence.graphRef()).thenReturn(ref("GRAPH_DRAFT", "refund-graph", '8'));
        when(evidence.acceptanceSuiteRefs()).thenReturn(List.of(suiteRef));
        when(evidence.cases()).thenReturn(List.of(
                new CapabilityProposalSimulationEvidence.CaseEvidence("case-1",
                        TestSuite.CaseType.GOLDEN, suiteRef, fixtureRef,
                        ref("MIRROR_PLAN", "mirror-plan-1", '7'),
                        ref("MIRROR_EVIDENCE_BUNDLE", "mirror-run-1", '9'), "PASSED",
                        List.of("OWNER_SPECIFIED"), List.of("target-rule"), 1, List.of())));
        return evidence;
    }

    private MirrorEvidenceBundle baselineEvidence() {
        MirrorResolution resolution = mock(MirrorResolution.class);
        when(resolution.invocationSiteId()).thenReturn(targetSite);
        MirrorRunEvidence evidence = mock(MirrorRunEvidence.class);
        when(evidence.planId()).thenReturn("mirror-plan-1");
        when(evidence.planFingerprint()).thenReturn(fingerprint('7'));
        when(evidence.fixtureBundleRef()).thenReturn(fixtureRef);
        when(evidence.status()).thenReturn(MirrorRunEvidence.Status.PASSED);
        when(evidence.semanticResultFingerprint()).thenReturn(
                baselineExecution.evidence().semanticResultFingerprint());
        when(evidence.nodeTraces()).thenReturn(baselineExecution.evidence().nodeTrace().stream()
                .map(this::mirrorNode).toList());
        when(evidence.edgeTraces()).thenReturn(baselineExecution.evidence().edgeTrace().stream()
                .map(this::mirrorEdge).toList());
        when(evidence.resolutions()).thenReturn(List.of(resolution));
        MirrorEvidenceBundle bundle = mock(MirrorEvidenceBundle.class);
        when(bundle.bundleFingerprint()).thenReturn(fingerprint('9'));
        when(bundle.evidence()).thenReturn(evidence);
        return bundle;
    }

    private MirrorRunEvidence.NodeTrace mirrorNode(
            com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence.NodeTrace value) {
        return new MirrorRunEvidence.NodeTrace(value.nodeId(), value.operatorRef(), value.status(),
                "MOCKED", ProtocolFingerprint.of(mapper, value.input()),
                ProtocolFingerprint.of(mapper, value.output()), value.errorCode(),
                value.durationMs(), value.invocationSiteId(), value.graphPath(),
                value.correlationKey(), value.occurrence(), value.graphOccurrence(),
                value.attempts().stream().map(attempt -> new MirrorRunEvidence.AttemptTrace(
                        attempt.attempt(), "MOCKED", "OUTPUT_LEVEL",
                        ProtocolFingerprint.of(mapper, attempt.input()),
                        ProtocolFingerprint.of(mapper, attempt.output()), attempt.errorCode(),
                        attempt.durationMs())).toList());
    }

    private MirrorRunEvidence.EdgeTrace mirrorEdge(
            com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence.EdgeTrace value) {
        return new MirrorRunEvidence.EdgeTrace(value.edgeId(), value.status(),
                ProtocolFingerprint.of(mapper, value.value()), value.graphPath(),
                value.correlationKey(), value.graphOccurrence(), value.fromInvocationSiteId(),
                value.toInvocationSiteId());
    }

    private CapabilityImplementationRuntimePort runtime() {
        CapabilityImplementationRuntimePort.Descriptor descriptor = descriptor();
        return new CapabilityImplementationRuntimePort() {
            @Override
            public Optional<Descriptor> describe(CapabilitySnapshot.Scope scope, String port) {
                return Optional.of(descriptor);
            }

            @Override
            public Object invoke(CapabilityImplementationBinding exact, Invocation invocation) {
                implementationCalls.incrementAndGet();
                return Map.of("approved", true);
            }
        };
    }

    private CapabilityImplementationBinding binding() {
        CapabilityImplementationRuntimePort.Descriptor descriptor = descriptor();
        return new CapabilityImplementationBinding("", "binding-1", 1, "", SCOPE,
                proposalRef, simulationRef, targetCapability,
                ProtocolFingerprint.of(mapper, proposal.draft().candidateContract()),
                descriptor.runtimePortRef(), descriptor.runtimePortFingerprint(),
                descriptor.implementationVersion(), descriptor.implementationFingerprint(),
                descriptor.runtimeOwner(), descriptor.allowedRegions(), true, true,
                descriptor.attestedAt(), descriptor.expiresAt(), AT).seal(mapper);
    }

    private CapabilityImplementationRuntimePort.Descriptor descriptor() {
        return new CapabilityImplementationRuntimePort.Descriptor("runtime:refund:v1",
                fingerprint('a'), "1.0.0", fingerprint('b'),
                proposal == null ? fingerprint('c')
                        : ProtocolFingerprint.of(mapper, proposal.draft().candidateContract()),
                "owner", List.of("sg"), true, true, AT.minusSeconds(1),
                AT.plus(Duration.ofHours(1)));
    }

    private StoredCapabilityProposalDraft proposal() {
        CapabilityProposalDraft draft = new CapabilityProposalDraft("", "proposal-1", 1, SCOPE,
                new CapabilityProposalDraft.BusinessIntent("missing refund lookup",
                        "validate refund journey",
                        List.of(ref("SCENARIO_CASE", "approved", '1')),
                        List.of(ref("DOMAIN_CAPABILITY_PACKAGE", "refund", '2')),
                        List.of(ref("GRAPH_DRAFT", "refund-graph", '8')), "owner"),
                contract(), List.of(fixtureRef), List.of(suiteRef),
                new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                        ref("FIXTURE_RESOLVER_POLICY", "fixture-only", '0'),
                        false, false, false), List.of(), List.of(),
                AT.plus(Duration.ofDays(1)), provenance(),
                CapabilityProposalDraft.Lifecycle.DRAFT);
        return new StoredCapabilityProposalDraft("",
                VisualBundleFingerprint.fromCanonicalValue(mapper, draft, 4 * 1024 * 1024),
                draft, AT, AT, "owner");
    }

    private StoredTestSuite suite(MirrorArtifactRef exactFixture) {
        TestSuite value = new TestSuite("", "refund-suite", 1,
                new TestSuite.Target("GRAPH", graph.name(), fixture.targetFingerprint()),
                "INTERNAL", List.of(new TestSuite.TestCase("case-1",
                TestSuite.CaseType.GOLDEN, Map.of(),
                new TestSuite.FixtureBundleRef(exactFixture.id(), exactFixture.revision(),
                        exactFixture.fingerprint()), List.of("proposal"), Map.of())),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of(), List.of(), 0, true),
                TestSuite.PromotionPolicy.defaults(), Map.of());
        return new StoredTestSuite("", SCOPE.tenantId(), SCOPE.organizationId(), SCOPE.projectId(),
                SCOPE.environmentId(), SCOPE.region(), value.suiteId(), value.revision(),
                new TestSuiteProtocolCodec(mapper).fingerprint(value), value, AT, "owner");
    }

    private VisualEvidenceSigner signer() {
        VisualEvidenceSigner signer = mock(VisualEvidenceSigner.class);
        when(signer.seal(any(), any())).thenAnswer(invocation ->
                new VisualRunEvidenceSeal("", invocation.getArgument(0), "TEST", "key-1",
                        AT.plusSeconds(5), "signature"));
        when(signer.verify(any(), any())).thenReturn(
                new VisualEvidenceSigner.Verification(true, "VERIFIED", ""));
        return signer;
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

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "conformance-test", null, null, null, null,
                List.of(), "", null, null, "");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(SCOPE.tenantId(), SCOPE.organizationId(),
                SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(), "WORKLOAD", "tester",
                "", "CAPABILITY_CONFORMANCE", "correlation-1", Set.of("testers"),
                "RESTRICTED", "");
    }

    private static Graph withoutEmbeddedOperators(Graph value) {
        return new Graph(value.name(), value.nodes(), value.edges(), value.sourceNodes(),
                value.terminalNodes(), value.schemaValidationLevel(), Map.of(),
                value.declaredInputSchema(), value.declaredOutputSchema(), value.sagaConfig(),
                value.definitionSource(), value.streamingOutputNodeId(), value.streamingInputs());
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class OriginalOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            throw new AssertionError("baseline operator must remain controlled");
        }
    }

    private static final class InMemoryConformanceRepository
            implements CapabilityImplementationConformanceRepository {
        private State state;

        @Override
        public Claim claim(Registration registration, String owner, Duration duration) {
            if (state != null) {
                if (!state.registration().equals(registration)) {
                    throw new IllegalArgumentException("different material");
                }
                return state.status() == Status.COMPLETED
                        ? new Claim(Outcome.COMPLETED, state, null, 0)
                        : new Claim(Outcome.IN_PROGRESS, state, null, 1);
            }
            Lease lease = new Lease(registration.scope(), registration.conformanceId(),
                    registration.implementationBindingRef().id(),
                    registration.implementationBindingRef().revision(), owner, 1);
            state = new State(registration, Status.ACTIVE, owner, 1, AT.plus(duration),
                    null, "", AT, AT);
            return new Claim(Outcome.ACQUIRED, state, lease, 0);
        }

        @Override
        public boolean renew(Lease lease, Duration duration) {
            return true;
        }

        @Override
        public boolean complete(Lease lease, StoredCapabilityImplementationConformance result) {
            state = new State(state.registration(), Status.COMPLETED, state.leaseOwner(),
                    state.leaseEpoch(), state.leaseExpiresAt(), result, "", state.createdAt(),
                    result.completedAt());
            return true;
        }

        @Override
        public boolean release(Lease lease, String failureCode) {
            return true;
        }

        @Override
        public Optional<State> find(
                CapabilitySnapshot.Scope scope, String bindingId, long bindingRevision) {
            return Optional.ofNullable(state);
        }
    }
}
