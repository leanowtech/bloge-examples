package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.ResilienceConfig;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshotIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationToken;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTrustProvider;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorServingGenerationFence;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorPlanCompilerTest {
    private static final String TARGET = fingerprint('a');
    private static final Instant COMPILED_AT = Instant.parse("2026-07-22T08:00:00Z");
    private static final String PURPOSE = "MIRROR_REHEARSAL";
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "support", "test", "sg");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
    private MirrorPlanCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new MirrorPlanCompiler(registry, mapper);
    }

    @Test
    void compilesStablePayloadFreePlanFromExactClosureGraphAndFixture() throws Exception {
        registry.register("customer.lookup", new ReadOnlyOperator());
        Graph graph = graph("customerView", Map.of("loadCustomer", "customer.lookup"));
        CapabilityClosure closure = directClosure("customerView", "loadCustomer",
                "customer.lookup", TARGET, readOnlyEffect(), null);
        FixtureBundle fixture = fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning(Map.of("customerName", "sensitive-fixture-value"))));
        MirrorPlanCompilationRequest request = request(graph, closure, fixture, policy(), null, TARGET);

        CompiledMirrorPlan first = compiler.compile(request);
        CompiledMirrorPlan second = compiler.compile(request);

        MirrorPlanIntegrity.verify(mapper, first.plan());
        assertThat(first.plan().planFingerprint()).isEqualTo(second.plan().planFingerprint());
        assertThat(first.plan().executionControlFingerprint())
                .isEqualTo(first.executionControl().effectivePlan().planFingerprint());
        assertThat(first.plan().externalBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.invocationSiteId()).isEqualTo("/root/loadCustomer#PRIMARY");
            assertThat(binding.fixtureRuleRefs()).containsExactly("customer-response");
            assertThat(binding.resolverOrder()).containsExactly(
                    MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                    MirrorPlan.MirrorSource.ABSTAINED);
        });
        assertThat(mapper.writeValueAsString(first.plan()))
                .doesNotContain("sensitive-fixture-value");
        assertThat(first.executionControl().controls().get("/root/loadCustomer#PRIMARY")
                .rules().getFirst().behavior().value()).isEqualTo(
                Map.of("customerName", "sensitive-fixture-value"));
    }

    @Test
    void bindsRecordedCorpusToAMirrorPlanV2ServingGeneration() {
        registry.register("customer.lookup", new ReadOnlyOperator());
        Graph graph = graph(
                "customerView", Map.of("loadCustomer", "customer.lookup"));
        CapabilityClosure closure = directClosure(
                "customerView", "loadCustomer", "customer.lookup",
                TARGET, readOnlyEffect(), null);
        MirrorArtifactRef capabilityRef = closure.snapshots().stream()
                .filter(snapshot ->
                        snapshot.kind() == CapabilitySnapshot.Kind.EXTERNAL)
                .map(CapabilityClosureIntegrity::reference)
                .findFirst().orElseThrow();
        Instant issuedAt = COMPILED_AT;
        ResolvedCorpusPayloads unbound = corpus(capabilityRef, issuedAt);

        assertRejected(() -> compiler.compile(
                        requestWithCorpus(
                                graph, closure, unbound, issuedAt)),
                "RG.MIRROR.SERVING_GENERATION_REQUIRED");

        ResolvedCorpusPayloads governed = corpus(capabilityRef, issuedAt);
        VisualEvidenceSigner signer = InMemoryVisualEvidenceSigner.usingClock(
                Clock.fixed(issuedAt, ZoneOffset.UTC));
        MirrorServingGenerationIntegrity generationIntegrity =
                new MirrorServingGenerationIntegrity(mapper);
        String dependencyFingerprint = ProtocolFingerprint.of(
                mapper, governed.generationDependencies());
        MirrorServingGenerationToken token = generationIntegrity.seal(
                new MirrorServingGenerationToken.Material(
                        "support-corpus", 3, fingerprint('8'), SCOPE,
                        PURPOSE, dependencyFingerprint, 17, issuedAt,
                        issuedAt.plus(Duration.ofHours(1)),
                        Duration.ofSeconds(5)),
                "corpus-authority-a", signer);
        VisualEvidenceSigner.VerificationKey key = signer.key(
                token.seal().keyId()).orElseThrow();
        MirrorServingGenerationTrustProvider trust =
                MirrorServingGenerationTrustProvider.fixed(
                        new MirrorServingGenerationTrustProvider.AuthorityKey(
                                token.seal().authorityId(), key.keyId(),
                                key.algorithm(), key.encodedPublicKey(),
                                issuedAt.minus(Duration.ofHours(1)),
                                issuedAt.plus(Duration.ofHours(2)),
                                MirrorServingGenerationTrustProvider.KeyState.ACTIVE));
        MirrorServingGenerationAuthority authority =
                currentAuthority(token);
        governed = governed.withServingGeneration(
                new MirrorServingGenerationFence(
                        token, authority, trust, generationIntegrity,
                        Clock.fixed(issuedAt, ZoneOffset.UTC)));

        ResolvedCorpusPayloads mismatched = corpus(
                capabilityRef, issuedAt);
        MirrorServingGenerationToken wrongDependency =
                generationIntegrity.seal(
                        new MirrorServingGenerationToken.Material(
                                "support-corpus-wrong", 1, "", SCOPE,
                                PURPOSE, fingerprint('f'), 18, issuedAt,
                                issuedAt.plus(Duration.ofHours(1)),
                                Duration.ofSeconds(5)),
                        "corpus-authority-a", signer);
        mismatched = mismatched.withServingGeneration(
                new MirrorServingGenerationFence(
                        wrongDependency,
                        currentAuthority(wrongDependency),
                        trust,
                        generationIntegrity,
                        Clock.fixed(issuedAt, ZoneOffset.UTC)));
        ResolvedCorpusPayloads mismatchedDependencies = mismatched;
        assertRejected(
                () -> compiler.compile(requestWithCorpus(
                        graph, closure, mismatchedDependencies, issuedAt)),
                "RG.MIRROR.SERVING_GENERATION_DEPENDENCY_MISMATCH");

        try (CompiledMirrorPlan compiled = compiler.compile(
                requestWithCorpus(graph, closure, governed, issuedAt))) {
            assertThat(compiled.plan().schemaVersion())
                    .isEqualTo(MirrorPlan.SCHEMA_VERSION);
            assertThat(compiled.plan().servingGeneration())
                    .isEqualTo(token);
            assertThat(compiled.plan().externalBindings())
                    .singleElement()
                    .satisfies(binding -> assertThat(binding.resolverOrder())
                            .contains(MirrorPlan.MirrorSource.RECORDED_EXACT));
            MirrorPlanIntegrity.verify(mapper, compiled.plan());
            assertThat(mapper.valueToTree(compiled.plan())
                    .has("servingGeneration")).isTrue();
        } finally {
            unbound.close();
            governed.close();
            mismatched.close();
        }
    }

    @Test
    void turnsAnUnconfiguredReadOnlyExternalLeafIntoExplicitAbstention() {
        registry.register("customer.lookup", new ReadOnlyOperator());
        Graph graph = graph("customerView", Map.of("loadCustomer", "customer.lookup"));
        CapabilityClosure closure = directClosure("customerView", "loadCustomer",
                "customer.lookup", TARGET, readOnlyEffect(), null);

        CompiledMirrorPlan compiled = compiler.compile(request(
                graph, closure, fixture(), policy(), null, TARGET));

        assertThat(compiled.plan().externalBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.fixtureRuleRefs()).isEmpty();
            assertThat(binding.resolverOrder()).containsExactly(MirrorPlan.MirrorSource.ABSTAINED);
        });
        assertThat(compiled.executionControl().controls()
                .get("/root/loadCustomer#PRIMARY").implicitDeny()).isTrue();
    }

    @Test
    void compilesReadOnlyStateDependencyAsTheHighestPrecedenceResolver() {
        registry.register("order.query", new ReadOnlyOperator());
        Graph graph = graph(
                "orderView", Map.of("loadOrder", "order.query"));
        MirrorArtifactRef stateModel =
                ref("STATE_MODEL", "order-world", 'c');
        CapabilityClosure closure = directClosure(
                "orderView", "loadOrder", "order.query",
                TARGET, EffectContract.readOnly(List.of("order:*")),
                stateModel);
        FixtureBundle ownerFallback = fixture(rule(
                "owner-order", "loadOrder",
                FixtureRule.Behavior.returning(
                        Map.of("status", "OWNER_FALLBACK"))));

        CompiledMirrorPlan compiled = compiler.compile(request(
                graph, closure, ownerFallback, policy(), null, TARGET));

        assertThat(compiled.plan().stateModelRefs())
                .containsExactly(stateModel);
        assertThat(compiled.plan().externalBindings())
                .singleElement()
                .satisfies(binding -> assertThat(binding.resolverOrder())
                        .containsExactly(
                                MirrorPlan.MirrorSource.SESSION_STATE,
                                MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                                MirrorPlan.MirrorSource.ABSTAINED));
        assertThat(compiled.executionControl().effectivePlan().resolvedSites())
                .singleElement()
                .satisfies(site -> assertThat(site.fidelity())
                        .isEqualTo("SESSION_STATE"));
        MirrorPlanIntegrity.verify(mapper, compiled.plan());
    }

    @Test
    void rejectsRealFallbackEvenWhenExternalOperatorClaimsReadOnly() {
        registry.register("customer.lookup", new ReadOnlyOperator());
        Graph graph = graph("customerView", Map.of("loadCustomer", "customer.lookup"));
        CapabilityClosure closure = directClosure("customerView", "loadCustomer",
                "customer.lookup", TARGET, readOnlyEffect(), null);
        FixtureBundle fixture = fixture(rule("unsafe-real", "loadCustomer",
                FixtureRule.Behavior.real()));

        assertRejected(() -> compiler.compile(request(
                        graph, closure, fixture, policy(), null, TARGET)),
                "RG.MIRROR.CONTROL_PLAN_UNSAFE_EXTERNAL_REAL");
    }

    @Test
    void rejectsGraphDriftAndCapabilityEdgesMissingFromRuntime() {
        registry.register("customer.lookup", new ReadOnlyOperator());
        Graph graph = graph("customerView", Map.of("loadCustomer", "customer.lookup"));
        CapabilityClosure closure = directClosure("customerView", "loadCustomer",
                "customer.lookup", TARGET, readOnlyEffect(), null);

        assertRejected(() -> compiler.compile(request(
                        graph, closure, fixture(), policy(), null, fingerprint('b'))),
                "RG.MIRROR.GRAPH_ARTIFACT_DRIFT");

        CapabilityClosure missing = directClosure("customerView", "missingNode",
                "customer.lookup", TARGET, readOnlyEffect(), null);
        assertRejected(() -> compiler.compile(request(
                        graph, missing, fixture(), policy(), null, TARGET)),
                "RG.MIRROR.EXTERNAL_RUNTIME_SITE_MISSING");
    }

    @Test
    void rejectsRuntimeExternalSitesOmittedByTheCapabilityClosure() {
        registry.register("customer.lookup", new ReadOnlyOperator());
        registry.register("audit.write", new ExternalOperator());
        Graph graph = graph("customerView", Map.of(
                "loadCustomer", "customer.lookup", "writeAudit", "audit.write"));
        CapabilityClosure closure = directClosure("customerView", "loadCustomer",
                "customer.lookup", TARGET, readOnlyEffect(), null);

        assertRejected(() -> compiler.compile(request(
                        graph, closure, fixture(), policy(), null, TARGET)),
                "RG.MIRROR.RUNTIME_EXTERNAL_NOT_IN_CLOSURE");
    }

    @Test
    void rejectsAPlanWhoseStaticInventoryAlreadyExceedsTheOccurrenceBudget() {
        registry.register("customer.lookup", new ReadOnlyOperator());
        registry.register("customer.format", new ReadOnlyOperator());
        Graph graph = graph("customerView", Map.of(
                "loadCustomer", "customer.lookup", "formatCustomer", "customer.format"));
        CapabilityClosure closure = directClosure("customerView", "loadCustomer",
                "customer.lookup", TARGET, readOnlyEffect(), null);

        assertRejected(() -> compiler.compile(request(
                        graph, closure, fixture(), policy(1), null, TARGET)),
                "RG.MIRROR.INVOCATION_BUDGET_TOO_SMALL");
    }

    @Test
    void requiresDeterministicServicesAndAdmittedFixtureClassification() {
        registry.register("customer.lookup", new ReadOnlyOperator());
        Graph graph = graph("customerView", Map.of("loadCustomer", "customer.lookup"));
        CapabilityClosure closure = directClosure("customerView", "loadCustomer",
                "customer.lookup", TARGET, readOnlyEffect(), null);
        FixtureBundle missingServices = new FixtureBundle("", "fixture", 1, TARGET,
                "INTERNAL", null, null, List.of(), List.of(), Map.of());
        assertRejected(() -> compiler.compile(request(
                        graph, closure, missingServices, policy(), null, TARGET)),
                "RG.MIRROR.DETERMINISTIC_SERVICES_REQUIRED");

        FixtureBundle restricted = new FixtureBundle("", "fixture", 1, TARGET,
                "RESTRICTED", COMPILED_AT, 42L, List.of(), List.of(), Map.of());
        assertRejected(() -> compiler.compile(request(
                        graph, closure, restricted, policy(), null, TARGET)),
                "RG.MIRROR.FIXTURE_CLASSIFICATION_FORBIDDEN");
    }

    @Test
    void rejectsReservedStateScenarioAndSynthesisCapabilitiesUntilTheirRuntimesExist() {
        registry.register("refund.update", new ReadOnlyOperator());
        Graph graph = graph("refundFlow", Map.of("updateRefund", "refund.update"));
        MirrorArtifactRef stateModel = ref("STATE_MODEL", "refund-world", 'c');
        EffectContract mutation = new EffectContract("", EffectContract.Mode.VIRTUAL_MUTATION,
                List.of("refund:*"), List.of("refund:*"), List.of(), null, false,
                EffectContract.RiskLevel.MEDIUM, EffectContract.Derivation.DECLARED, List.of());
        CapabilityClosure closure = directClosure("refundFlow", "updateRefund",
                "refund.update", TARGET, mutation, stateModel);
        assertRejected(() -> compiler.compile(request(
                        graph, closure, fixture(), policy(), null, TARGET)),
                "RG.MIRROR.STATEFUL_WRITE_RUNTIME_NOT_AVAILABLE");

        CapabilityClosure stateless = directClosure("refundFlow", "updateRefund",
                "refund.update", TARGET, readOnlyEffect(), null);
        MirrorArtifactRef scenario = ref("SCENARIO_PACK", "refund-regression", 'd');
        assertRejected(() -> compiler.compile(request(
                        graph, stateless, fixture(), policy(), scenario, TARGET)),
                "RG.MIRROR.SCENARIO_PACK_NOT_AVAILABLE");
        MirrorPlan.ExecutionPolicy synthesis = new MirrorPlan.ExecutionPolicy(PURPOSE,
                false, false, false, true, true, MirrorPlan.UnmatchedResolution.ABSTAINED,
                1000, Duration.ofMinutes(5), CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg"), List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
        assertRejected(() -> compiler.compile(request(
                        graph, stateless, fixture(), synthesis, null, TARGET)),
                "RG.MIRROR.SCHEMA_SYNTHESIS_NOT_AVAILABLE");
    }

    @Test
    void rejectsSchemaWaiversFromCertificationRequiredPlans() {
        registry.register("customer.lookup", new ReadOnlyOperator());
        Graph graph = graph("customerView", Map.of("loadCustomer", "customer.lookup"));
        CapabilityClosure closure = directClosure("customerView", "loadCustomer",
                "customer.lookup", TARGET, readOnlyEffect(), null);
        FixtureRule waived = new FixtureRule("", "waived", FixtureRule.Selector.node("loadCustomer"),
                FixtureRule.Behavior.returning("value"), FixtureRule.Consumption.once(),
                new FixtureRule.SchemaCheck(FixtureRule.SchemaCheckMode.WAIVED,
                        "legacy response has no schema"));

        assertRejected(() -> compiler.compile(request(
                        graph, closure, fixture(waived), policy(), null, TARGET)),
                "RG.MIRROR.CERTIFICATION_SCHEMA_WAIVER_FORBIDDEN");
    }

    @Test
    void resolvesNestedComposedCapabilitiesToTheirOwnedBlogeGraphPath() {
        DefaultOperatorRegistry nestedRegistry = new DefaultOperatorRegistry();
        nestedRegistry.register("customer.lookup", new ReadOnlyOperator());
        Graph childGraph = graph("customerBody", Map.of("loadCustomer", "customer.lookup"));
        Graph rootGraph = new GraphBuilder("customerJourney")
                .node("nestedCustomer", new SubGraphOperator(childGraph, nestedRegistry))
                .build();
        CapabilityClosure closure = nestedClosure(rootGraph.name(), "nestedCustomer",
                childGraph.name(), "loadCustomer", "customer.lookup", TARGET);
        FixtureRule nestedRule = new FixtureRule("", "nested-response",
                new FixtureRule.Selector("/root/nestedCustomer/customerBody", "loadCustomer",
                        "", "", "", List.of(), List.of(),
                        com.leanowtech.bloge.gateway.testing.domain.InvocationSite.InvocationKind.PRIMARY,
                        List.of(), List.of(), "", FixtureRule.Match.none()),
                FixtureRule.Behavior.returning("nested-value"), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());

        CompiledMirrorPlan compiled = compiler.compile(request(
                rootGraph, closure, fixture(nestedRule), policy(), null, TARGET));

        assertThat(compiled.plan().externalBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.graphPath()).isEqualTo("/root/nestedCustomer/customerBody");
            assertThat(binding.invocationSiteId())
                    .isEqualTo("/root/nestedCustomer/customerBody/loadCustomer#PRIMARY");
            assertThat(binding.parentCapabilityRef().id()).isEqualTo("graph:customerBody");
        });
    }

    private MirrorPlanCompilationRequest request(
            Graph graph,
            CapabilityClosure closure,
            FixtureBundle fixture,
            MirrorPlan.ExecutionPolicy policy,
            MirrorArtifactRef scenario,
            String target) {
        return new MirrorPlanCompilationRequest("plan-customer-view", graph, target, closure,
                retarget(fixture, target), ResolvedReplayPayloads.empty(), policy, scenario,
                COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(1)));
    }

    private MirrorPlanCompilationRequest requestWithCorpus(
            Graph graph,
            CapabilityClosure closure,
            ResolvedCorpusPayloads corpus,
            Instant compiledAt) {
        return new MirrorPlanCompilationRequest(
                "plan-customer-view", graph, TARGET, closure,
                retarget(fixture(), TARGET), ResolvedReplayPayloads.empty(),
                corpus, policy(), null, compiledAt,
                compiledAt.plus(Duration.ofMinutes(30)));
    }

    private ResolvedCorpusPayloads corpus(
            MirrorArtifactRef capabilityRef, Instant materializedAt) {
        ResolvedCorpusPayloads.Sample sample =
                ResolvedCorpusPayloads.Sample.response(
                        fingerprint('1'),
                        "{\"customerId\":\"C-1\"}".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8),
                        List.of(
                                ref("CAPABILITY_CORPUS_PUBLICATION",
                                        "customer-publication", '2'),
                                ref("CAPABILITY_CORPUS_REVISION",
                                        "customer-corpus", '3'),
                                ref("SANITIZED_PAYLOAD", "response", '4')),
                        List.of("observation-a"), 1, List.of());
        return ResolvedCorpusPayloads.of(List.of(
                new ResolvedCorpusPayloads.CapabilityCorpus(
                        capabilityRef,
                        ref("CAPABILITY_CORPUS_PUBLICATION",
                                "customer-publication", '2'),
                        ref("CAPABILITY_CORPUS_REVISION",
                                "customer-corpus", '3'),
                        materializedAt,
                        materializedAt.plus(Duration.ofHours(1)),
                        List.of(sample))));
    }

    private static MirrorServingGenerationAuthority currentAuthority(
            MirrorServingGenerationToken token) {
        return new MirrorServingGenerationAuthority() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Resolution admit(AdmissionRequest request) {
                return Resolution.current(token);
            }

            @Override
            public Resolution currentFloor(FloorRequest request) {
                return Resolution.current(token);
            }
        };
    }

    private static FixtureBundle retarget(FixtureBundle fixture, String target) {
        return new FixtureBundle(fixture.schemaVersion(), fixture.fixtureBundleId(), fixture.revision(),
                target, fixture.classification(), fixture.logicalClock(), fixture.randomSeed(),
                fixture.rules(), fixture.assertions(), fixture.metadata());
    }

    private CapabilityClosure directClosure(
            String graphName,
            String nodeId,
            String operatorRef,
            String graphFingerprint,
            EffectContract effect,
            MirrorArtifactRef stateModelRef) {
        CapabilitySnapshot child = external(operatorRef, effect, stateModelRef);
        CapabilitySnapshot root = composed(graphName, graphFingerprint, effect, stateModelRef,
                List.of(new CapabilitySnapshot.Dependency(nodeId,
                        CapabilityClosureIntegrity.reference(child), true, List.of())));
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, child), ""));
    }

    private CapabilityClosure nestedClosure(
            String rootGraph,
            String nestedNode,
            String childGraph,
            String externalNode,
            String operatorRef,
            String graphFingerprint) {
        EffectContract effect = readOnlyEffect();
        CapabilitySnapshot external = external(operatorRef, effect, null);
        CapabilitySnapshot child = composed(childGraph, fingerprint('7'), effect, null,
                List.of(new CapabilitySnapshot.Dependency(externalNode,
                        CapabilityClosureIntegrity.reference(external), true, List.of())));
        CapabilitySnapshot root = composed(rootGraph, graphFingerprint, effect, null,
                List.of(new CapabilitySnapshot.Dependency(nestedNode,
                        CapabilityClosureIntegrity.reference(child), true, List.of())));
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, child, external), ""));
    }

    private CapabilitySnapshot external(
            String operatorRef,
            EffectContract effect,
            MirrorArtifactRef stateModelRef) {
        return CapabilitySnapshotIntegrity.seal(mapper, new CapabilitySnapshot("",
                "operator:" + operatorRef, 1, "", CapabilitySnapshot.Kind.EXTERNAL, SCOPE,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.OPERATOR,
                        operatorRef, fingerprint('e')), contract(effect, stateModelRef),
                runtime("OPERATOR", operatorRef, 'f'), List.of(), ownership(),
                CapabilitySnapshot.Lifecycle.ACTIVE, provenance(), COMPILED_AT));
    }

    private CapabilitySnapshot composed(
            String graphName,
            String graphFingerprint,
            EffectContract effect,
            MirrorArtifactRef stateModelRef,
            List<CapabilitySnapshot.Dependency> dependencies) {
        return CapabilitySnapshotIntegrity.seal(mapper, new CapabilitySnapshot("",
                "graph:" + graphName, 1, "", CapabilitySnapshot.Kind.COMPOSED, SCOPE,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                        graphName, graphFingerprint), contract(effect, stateModelRef),
                runtime("BLOGE_GRAPH", graphName, '8'), dependencies, ownership(),
                CapabilitySnapshot.Lifecycle.ACTIVE, provenance(), COMPILED_AT));
    }

    private static CapabilityContract contract(
            EffectContract effect,
            MirrorArtifactRef stateModelRef) {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(),
                effect, CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true),
                stateModelRef, CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, false,
                        List.of("sg"), false), CapabilityContract.SloContract.unspecified());
    }

    private static CapabilitySnapshot.RuntimeBinding runtime(
            String kind,
            String ref,
            char fingerprint) {
        return new CapabilitySnapshot.RuntimeBinding(kind, ref, fingerprint(fingerprint),
                true, List.of());
    }

    private static CapabilitySnapshot.Ownership ownership() {
        return new CapabilitySnapshot.Ownership("owner-a", "support", "pager");
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), PURPOSE, null, null, null, null, List.of(),
                "owner-a", COMPILED_AT.minus(Duration.ofDays(1)),
                COMPILED_AT.plus(Duration.ofDays(1)), "");
    }

    private static MirrorPlan.ExecutionPolicy policy() {
        return policy(1000);
    }

    private static MirrorPlan.ExecutionPolicy policy(int maximumInvocations) {
        return new MirrorPlan.ExecutionPolicy(PURPOSE, false, false, false, false, true,
                MirrorPlan.UnmatchedResolution.ABSTAINED, maximumInvocations,
                Duration.ofMinutes(5),
                CapabilityContract.DataClassification.CONFIDENTIAL, List.of("sg"),
                List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
    }

    private static FixtureBundle fixture(FixtureRule... rules) {
        return new FixtureBundle("", "customer-fixture", 1, TARGET, "CONFIDENTIAL",
                COMPILED_AT, 42L, List.of(rules), List.of(), Map.of());
    }

    private static FixtureRule rule(String id, String nodeId, FixtureRule.Behavior behavior) {
        return new FixtureRule("", id, FixtureRule.Selector.node(nodeId), behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static Graph graph(String name, Map<String, String> nodes) {
        Map<String, NodeSpec> specs = new LinkedHashMap<>();
        nodes.forEach((nodeId, operatorRef) -> specs.put(nodeId,
                new NodeSpec(nodeId, operatorRef, null, ResilienceConfig.DEFAULT,
                        Map.of(), OpaqueSchema.INSTANCE, OpaqueSchema.INSTANCE)));
        return new Graph(name, specs, List.of(), Set.copyOf(specs.keySet()),
                Set.copyOf(specs.keySet()), SchemaValidationLevel.OFF);
    }

    private static EffectContract readOnlyEffect() {
        return EffectContract.readOnly(List.of("customer:*"));
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void assertRejected(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MirrorPlanRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static class ReadOnlyOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static final class ExternalOperator extends ReadOnlyOperator {
        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.EXTERNAL_CALL;
        }
    }
}
