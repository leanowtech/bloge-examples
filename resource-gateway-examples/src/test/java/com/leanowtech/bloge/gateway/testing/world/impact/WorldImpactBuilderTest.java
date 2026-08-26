package com.leanowtech.bloge.gateway.testing.world.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompiler;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioSourceMap;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldImpactBuilderTest {
    private static final String DSL = "graph customerWorld { transform result { value = ctx.id } }";
    private static final Instant START = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void staticBuilderUsesExactCompilerSourceMapChainAndRejectsTargetDrift() {
        Fixture fixture = fixture();
        WorldStaticDependencySnapshot snapshot = new WorldStaticDependencySnapshotBuilder().build(
                fixture.scenario(), fixture.world(), fixture.compilation(), 4, START);

        assertThat(snapshot.dependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.logicalContractFingerprint())
                    .isEqualTo(fixture.contract().contractFingerprint());
            assertThat(dependency.fragmentFingerprint())
                    .isEqualTo(fixture.world().slices().getFirst().behavior().fingerprint());
            assertThat(dependency.targetGraphArtifactFingerprint())
                    .isEqualTo(fixture.graphFingerprint());
            assertThat(dependency.invocationSiteIds()).containsExactly("/root/lookup#PRIMARY");
        });
        InMemoryWorldImpactSnapshotRepository repository = new InMemoryWorldImpactSnapshotRepository();
        assertThat(new WorldImpactIndexService(repository).rebuildStatic(
                fixture.scenario(), fixture.world(), fixture.compilation(), 4, START).snapshot())
                .isEqualTo(snapshot);
        Scenario drifted = new Scenario("scenario-drift", "tenant-a", 1,
                new Scenario.TargetRef("GRAPH", fixture.graph().name(), fp('9')), fixture.world(), Map.of(),
                Scenario.WorldStateInit.EMPTY, List.of(),
                List.of(Scenario.ContractDependency.of(fixture.contract())));
        assertThatThrownBy(() -> new WorldStaticDependencySnapshotBuilder().build(
                drifted, fixture.world(), fixture.compilation(), 4, START))
                .isInstanceOf(WorldImpactException.class);
    }

    @Test
    void runtimeBuilderAcceptsOnlyVerifiedEvidenceAndKeepsDeclarationsOutOfObservations() {
        Fixture fixture = fixture();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String runId = "run-a";
        TestRunEvidence evidence = new TestRunEvidence("", runId, TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST", fixture.graphFingerprint(),
                ProtocolFingerprint.of(mapper, fixture.compilation().bundle()), "", START,
                START.plusSeconds(1), List.of(new TestRunEvidence.NodeTrace("lookup", "", "MOCKED",
                "PROTOCOL_DERIVED", Map.of("id", "canary-input"), Map.of("status", "ok"), "", 1,
                "/root/lookup#PRIMARY", "/root", "", 1, 1, List.of())), List.of(),
                List.of(new TestRunEvidence.FixtureConsumption(
                        fixture.compilation().bundle().rules().getFirst().ruleId(), 1, true, "CONSUMED")),
                List.of(), List.of(), Map.of("tenantId", "tenant-a", "organizationId", "org-a",
                        "projectId", "project-a", "environmentId", "test", "actorId", "actor-a",
                        "payloadSanitized", true));
        TestEvidenceIntegrityService integrity = new TestEvidenceIntegrityService(mapper,
                new InMemoryVisualEvidenceSigner());
        TestEvidenceIntegrityService.SealResult seal = integrity.seal(
                com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint.attach(mapper, evidence));
        TestRunRecord record = new TestRunRecord(runId, "tenant-a", "org-a", "project-a", "test", "actor-a",
                new TestExecutionApiRequest.Target("GRAPH", fixture.graph().name(), fixture.graphFingerprint()),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("INLINE", "bundle", 1,
                        evidence.fixtureBundleFingerprint()), TestExecutionApiRequest.Verbosity.FULL, null,
                seal.evidence(), seal.integrity(), seal.evidence().completedAt(), START.plusSeconds(30));

        WorldRuntimeConsumptionSnapshot snapshot = new WorldRuntimeConsumptionSnapshotBuilder().build(record,
                mapper, integrity, fixture.scenario(), fixture.compilation(), 4, START.plusSeconds(2));
        assertThat(snapshot.consumptions()).singleElement().satisfies(consumption -> {
            assertThat(consumption.logicalContractId()).isEqualTo(fixture.contract().contractId());
            assertThat(consumption.invocationSiteIds()).containsExactly("/root/lookup#PRIMARY");
        });
        assertThat(snapshot.toString()).doesNotContain("canary-input", "status");
        TestRunRecord tampered = new TestRunRecord(record.runId(), "tenant-b", record.organizationId(),
                record.projectId(), record.environmentId(), record.actorId(), record.target(),
                record.fixtureBundleRef(), record.requestedVerbosity(), record.plan(), record.evidence(),
                record.integrity(), record.createdAt(), record.expiresAt());
        assertThatThrownBy(() -> new WorldRuntimeConsumptionSnapshotBuilder().build(tampered, mapper,
                integrity, fixture.scenario(), fixture.compilation(), 4, START))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void runtimeBuilderRejectsUnknownConsumedFixtureRuleInsteadOfDroppingIt() {
        Fixture fixture = fixture();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TestEvidenceIntegrityService integrity = new TestEvidenceIntegrityService(mapper,
                new InMemoryVisualEvidenceSigner());
        TestRunEvidence evidence = evidence(fixture, "unknown-rule-run", List.of(
                new TestRunEvidence.NodeTrace("lookup", "", "MOCKED", "PROTOCOL_DERIVED",
                        Map.of("id", "canary-input"), Map.of("status", "ok"), "", 1,
                        "/root/lookup#PRIMARY", "/root", "", 1, 1, List.of())),
                List.of(new TestRunEvidence.FixtureConsumption("unknown-rule", 1, true, "CONSUMED")));

        assertThatThrownBy(() -> new WorldRuntimeConsumptionSnapshotBuilder().build(
                record(fixture, "unknown-rule-run", evidence, mapper, integrity), mapper, integrity,
                fixture.scenario(), fixture.compilation(), 4, START))
                .isInstanceOf(WorldImpactException.class)
                .hasMessage("RG.WORLD_IMPACT.MAPPING_MISSING");
    }

    @Test
    void unexpectedObservedSiteIsRetainedAndBlocksReconciliation() {
        Fixture fixture = fixture();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TestEvidenceIntegrityService integrity = new TestEvidenceIntegrityService(mapper,
                new InMemoryVisualEvidenceSigner());
        TestRunEvidence evidence = evidence(fixture, "unexpected-site-run", List.of(
                new TestRunEvidence.NodeTrace("lookup", "", "MOCKED", "PROTOCOL_DERIVED",
                        Map.of("id", "canary-input"), Map.of("status", "ok"), "", 1,
                        "/root/lookup#PRIMARY", "/root", "", 1, 1, List.of())), List.of());
        WorldRuntimeConsumptionSnapshot observed = new WorldRuntimeConsumptionSnapshotBuilder().build(
                record(fixture, "unexpected-site-run", evidence, mapper, integrity), mapper, integrity,
                fixture.scenario(), fixture.compilation(), 4, START);
        WorldStaticDependencySnapshot declared = WorldStaticDependencySnapshot.create("tenant-a", "scenario-a", 1,
                fixture.scenario().fingerprint(), "customer-world", 1, fixture.world().fingerprint(),
                fixture.graphFingerprint(), 4, START, List.of(new WorldStaticDependencySnapshot.Dependency(
                        "world-delegate:logical.customer", fixture.contract().contractId(),
                        fixture.contract().contractFingerprint(), fixture.world().slices().getFirst().fingerprint(),
                        fixture.world().slices().getFirst().behavior().fingerprint(), fixture.graphFingerprint(),
                        List.of("/root/other#PRIMARY"))));

        WorldImpactReconciliation reconciliation = WorldImpactReconciliation.reconcile(declared, observed);
        assertThat(reconciliation.publicationBlocked()).isTrue();
        assertThat(reconciliation.entries()).extracting(WorldImpactReconciliation.Entry::classification)
                .contains(WorldImpactReconciliation.Classification.OBSERVED_ONLY);
    }

    @Test
    void sourceMapBindingDriftFailsClosedBeforeStaticFactsAreBuilt() {
        Fixture fixture = fixture();
        com.leanowtech.bloge.gateway.testing.world.WorldDelegateBinding drifted =
                new com.leanowtech.bloge.gateway.testing.world.WorldDelegateBinding(
                        "world-delegate:logical.customer", fixture.contract().contractId(), fp('9'),
                        fixture.world().slices().getFirst().behavior());
        String fingerprint = WorldScenarioCompilation.fingerprintFor(fixture.compilation().bundle(),
                List.of(drifted), fixture.compilation().sourceMap(), fixture.compilation().stateAccessPlan(),
                fixture.compilation().runStateDescriptor());
        WorldScenarioCompilation compilation = new WorldScenarioCompilation(fixture.compilation().bundle(),
                List.of(drifted), fixture.compilation().sourceMap(), fingerprint,
                fixture.compilation().stateAccessPlan(), fixture.compilation().runStateDescriptor());

        assertThatThrownBy(() -> new WorldStaticDependencySnapshotBuilder().build(fixture.scenario(),
                fixture.world(), compilation, 4, START))
                .isInstanceOf(WorldImpactException.class)
                .hasMessage("RG.WORLD_IMPACT.SOURCE_INTEGRITY");
    }

    @Test
    void sourceMapUnexpectedLinkFailsClosed() {
        Fixture fixture = fixture();
        WorldScenarioSourceMap tamperedMap = sourceMapWith(fixture, List.<String[]>of(
                new String[]{"unexpected-source", "unexpected-output"}));
        WorldScenarioCompilation compilation = compilationWith(fixture, tamperedMap);

        assertThatThrownBy(() -> new WorldStaticDependencySnapshotBuilder().build(fixture.scenario(),
                fixture.world(), compilation, 4, START))
                .isInstanceOf(WorldImpactException.class)
                .hasMessage("RG.WORLD_IMPACT.SOURCE_INTEGRITY");
    }

    @Test
    void sourceMapAmbiguousSliceLinkFailsClosed() {
        Fixture fixture = fixture();
        WorldScenarioSourceMap tamperedMap = sourceMapWith(fixture, List.<String[]>of(
                new String[]{"world-slice:logical.customer@" + fp('8'),
                        "fixture-rule:world-delegate:logical.customer"}));
        WorldScenarioCompilation compilation = compilationWith(fixture, tamperedMap);

        assertThatThrownBy(() -> new WorldStaticDependencySnapshotBuilder().build(fixture.scenario(),
                fixture.world(), compilation, 4, START))
                .isInstanceOf(WorldImpactException.class)
                .hasMessage("RG.WORLD_IMPACT.SOURCE_INTEGRITY");
    }

    @Test
    void runtimeBuilderRejectsFixtureBundleSourceDrift() {
        Fixture fixture = fixture();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TestEvidenceIntegrityService integrity = new TestEvidenceIntegrityService(mapper,
                new InMemoryVisualEvidenceSigner());
        TestRunEvidence evidence = evidenceWithBundle(fixture, "fixture-drift-run", List.of(
                new TestRunEvidence.NodeTrace("lookup", "", "MOCKED", "PROTOCOL_DERIVED",
                        Map.of("id", "canary-input"), Map.of("status", "ok"), "", 1,
                        "/root/lookup#PRIMARY", "/root", "", 1, 1, List.of())), List.of(), fp('9'));

        assertThatThrownBy(() -> new WorldRuntimeConsumptionSnapshotBuilder().build(
                record(fixture, "fixture-drift-run", evidence, mapper, integrity), mapper, integrity,
                fixture.scenario(), fixture.compilation(), 4, START))
                .isInstanceOf(WorldImpactException.class)
                .hasMessage("RG.WORLD_IMPACT.SOURCE_INTEGRITY");
    }

    private static WorldScenarioCompilation compilationWith(Fixture fixture, WorldScenarioSourceMap sourceMap) {
        String fingerprint = WorldScenarioCompilation.fingerprintFor(fixture.compilation().bundle(),
                fixture.compilation().bindings(), sourceMap, fixture.compilation().stateAccessPlan(),
                fixture.compilation().runStateDescriptor());
        return new WorldScenarioCompilation(fixture.compilation().bundle(), fixture.compilation().bindings(),
                sourceMap, fingerprint, fixture.compilation().stateAccessPlan(),
                fixture.compilation().runStateDescriptor());
    }

    private static WorldScenarioSourceMap sourceMapWith(Fixture fixture, List<String[]> extras) {
        try {
            Class<?> mapType = WorldScenarioSourceMap.class;
            Class<?> linkType = Class.forName(WorldScenarioSourceMap.class.getName() + "$Link");
            var linkConstructor = linkType.getDeclaredConstructor(String.class, String.class);
            linkConstructor.setAccessible(true);
            List<Object> links = new ArrayList<>();
            fixture.compilation().sourceMap().sourceToOutputs().forEach((source, outputs) ->
                    outputs.forEach(output -> {
                        try {
                            links.add(linkConstructor.newInstance(source, output));
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }));
            extras.forEach(extra -> {
                try {
                    links.add(linkConstructor.newInstance(extra[0], extra[1]));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            var factory = mapType.getDeclaredMethod("of", List.class);
            factory.setAccessible(true);
            return (WorldScenarioSourceMap) factory.invoke(null, links);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static TestRunRecord record(Fixture fixture, String runId, TestRunEvidence evidence,
                                        ObjectMapper mapper, TestEvidenceIntegrityService integrity) {
        TestEvidenceIntegrityService.SealResult seal = integrity.seal(
                com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint.attach(mapper, evidence));
        return new TestRunRecord(runId, "tenant-a", "org-a", "project-a", "test", "actor-a",
                new TestExecutionApiRequest.Target("GRAPH", fixture.graph().name(), fixture.graphFingerprint()),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("INLINE", "bundle", 1,
                        evidence.fixtureBundleFingerprint()), TestExecutionApiRequest.Verbosity.FULL, null,
                seal.evidence(), seal.integrity(), seal.evidence().completedAt(), START.plusSeconds(30));
    }

    private static TestRunEvidence evidence(Fixture fixture, String runId,
                                             List<TestRunEvidence.NodeTrace> nodeTrace,
                                             List<TestRunEvidence.FixtureConsumption> consumptions) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return evidenceWithBundle(fixture, runId, nodeTrace, consumptions,
                ProtocolFingerprint.of(mapper, fixture.compilation().bundle()));
    }

    private static TestRunEvidence evidenceWithBundle(Fixture fixture, String runId,
                                                       List<TestRunEvidence.NodeTrace> nodeTrace,
                                                       List<TestRunEvidence.FixtureConsumption> consumptions,
                                                       String bundleFingerprint) {
        return new TestRunEvidence("", runId, TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST", fixture.graphFingerprint(),
                bundleFingerprint, "", START,
                START.plusSeconds(1), nodeTrace, List.of(), consumptions, List.of(), List.of(),
                Map.of("tenantId", "tenant-a", "organizationId", "org-a", "projectId", "project-a",
                        "environmentId", "test", "actorId", "actor-a", "payloadSanitized", true));
    }

    private static Fixture fixture() {
        LogicalResourceContract contract = new LogicalResourceContract("logical.customer",
                SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id")),
                SchemaEnvelope.object(Map.of("status", Map.of("type", "string")), List.of("status")),
                ResponseSemantics.confirmed("http.status in 200..299", Map.of("BUSINESS", List.of("NOT_FOUND")),
                        ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL));
        String tag = WorldScenarioCompiler.logicalContractTag(contract.contractId(), contract.contractFingerprint());
        Operator<Object, Object> identity = (input, context) -> input;
        Graph graph = new GraphBuilder("customer-graph").node("lookup", identity)
                .meta("tags", tag).build();
        ResourceDesignContract design = new ResourceDesignContract("contract:customer.lookup",
                "customer.lookup", "Customer lookup", "", List.of(), contract.inputShape(),
                contract.outputShape(), Map.of(), "ACTIVE");
        VisualResourceDescriptor descriptor = new VisualResourceDescriptor("customer.lookup",
                "https://example.test/customers/{id}", "GET", Map.of("Accept", "application/json"), null,
                Duration.ofSeconds(2), new VisualResourceParameterMapping(Map.of("id", "$.id"), Map.of(), null),
                new VisualResourceResponseProtocol.HttpStatus(), "data");
        LogicalResourceBinding binding = LogicalResourceBinding.bind("provider-a", "v1", design, descriptor, contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration("tenant-a", "provider-a", "v1",
                contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true), contract,
                binding, BlogeFragmentRef.frozen("customer-world.bloge", DSL),
                com.leanowtech.bloge.gateway.testing.world.StateSpec.empty());
        ResourceWorldModel world = new ResourceWorldModel("customer-world", "tenant-a", 1, List.of(slice));
        String graphFingerprint = GraphArtifactFingerprint.of(new ObjectMapper(), graph);
        Scenario scenario = new Scenario("scenario-a", "tenant-a", 1,
                new Scenario.TargetRef("GRAPH", graph.name(), graphFingerprint), world, Map.of(),
                Scenario.WorldStateInit.EMPTY, List.of(), List.of(Scenario.ContractDependency.of(contract)));
        WorldScenarioCompilation compilation = new WorldScenarioCompiler().compile(scenario, world, graph,
                new DefaultOperatorRegistry(), Map.of(contract.contractId(), new com.leanowtech.bloge.gateway.testing.world.WorldSliceSelection(
                        "provider-a", "v1", slice.fingerprint())));
        return new Fixture(contract, world, scenario, graph, graphFingerprint, compilation);
    }

    private record Fixture(LogicalResourceContract contract, ResourceWorldModel world, Scenario scenario,
                           Graph graph, String graphFingerprint, WorldScenarioCompilation compilation) { }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
