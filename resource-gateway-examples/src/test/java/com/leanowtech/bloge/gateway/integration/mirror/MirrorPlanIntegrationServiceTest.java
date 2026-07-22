package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.ResilienceConfig;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MirrorPlanIntegrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-23T02:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "support", "test", "sg");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultOperatorRegistry operators = new DefaultOperatorRegistry();
    private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
    private Graph graph;
    private String graphFingerprint;
    private CapabilityClosure closure;
    private StoredFixtureBundle storedFixture;
    private InMemoryFixtureScopeRepository fixtureScopes;
    private GatewayGraphService graphService;
    private MirrorPlanIntegrationService service;

    @BeforeEach
    void setUp() {
        operators.register("customer.lookup", new ReadOnlyOperator());
        graph = graph("customerView", "loadCustomer", "customer.lookup");
        graphFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        closure = closure(SCOPE, graphFingerprint);
        FixtureBundle fixture = new FixtureBundle("", "customer-fixture", 1,
                graphFingerprint, "CONFIDENTIAL", NOW, 42L,
                List.of(), List.of(), Map.of("owner", "support"));
        storedFixture = new StoredFixtureBundle("", SCOPE.tenantId(), SCOPE.environmentId(),
                fixture.fixtureBundleId(), fixture.revision(),
                ProtocolFingerprint.of(mapper, fixture), fixture, NOW, "fixture-owner");
        fixtureScopes = new InMemoryFixtureScopeRepository();
        fixtureScopes.create(new MirrorFixtureScopeBinding(
                SCOPE, fixtureRef(storedFixture), NOW, "fixture-owner"));
        graphService = mock(GatewayGraphService.class);
        when(graphService.requireGraph("customerView")).thenReturn(graph);
        service = new MirrorPlanIntegrationService(
                new MirrorPlanCompiler(operators, mapper), plans,
                new FixedFixtureRepository(storedFixture), fixtureScopes, null,
                graphService, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void compilesAuthoritativeArtifactsAndMakesExactRetryIdempotent() {
        MirrorPlanCreateRequest request = request("plan-customer-view", closure,
                graphFingerprint, fixtureRef(storedFixture), Duration.ofMinutes(5));

        MirrorPlan first = service.create(request, identity());
        MirrorPlan retried = service.create(request, identity());

        assertThat(retried).isEqualTo(first);
        assertThat(plans.values).hasSize(1);
        assertThat(first.compiledAt()).isEqualTo(NOW);
        assertThat(first.scope()).isEqualTo(SCOPE);
        assertThat(first.policy().authorizedPurpose()).isEqualTo("MIRROR_REHEARSAL");
        assertThat(first.policy().realExternalCallsAllowed()).isFalse();
        assertThat(first.policy().externalCredentialsAllowed()).isFalse();
        assertThat(first.policy().networkEgressAllowed()).isFalse();
        assertThat(first.policy().maximumClassification())
                .isEqualTo(CapabilityContract.DataClassification.CONFIDENTIAL);
        assertThat(first.externalBindings()).singleElement().satisfies(binding -> {
            assertThat(binding.invocationSiteId()).isEqualTo("/root/loadCustomer#PRIMARY");
            assertThat(binding.resolverOrder())
                    .containsExactly(MirrorPlan.MirrorSource.ABSTAINED);
        });
        assertThat(service.find(first.planId(), identity())).isEqualTo(first);
    }

    @Test
    void rehydratesOnlyTheCompleteExactRuntimeGeneration() {
        MirrorPlan plan = service.create(request("runtime-plan", closure,
                graphFingerprint, fixtureRef(storedFixture), Duration.ofMinutes(5)), identity());

        var materialized = service.materialize(plan, identity());

        assertThat(materialized.plan()).isEqualTo(plan);
        assertThat(materialized.graph()).isEqualTo(graph);
        assertThat(materialized.fixtureBundle()).isEqualTo(storedFixture.bundle());

        Graph changed = graph("customerView", "changedNode", "customer.lookup");
        when(graphService.requireGraph("customerView")).thenReturn(changed);
        assertProblem(() -> service.materialize(plan, identity()),
                409, "RG.MIRROR.RUNTIME_GRAPH_DRIFT");
        assertProblem(() -> service.materialize(plan, identity(
                        "org-b", "support", "test", "sg",
                        "MIRROR_REHEARSAL", "CONFIDENTIAL")),
                404, "RG.MIRROR.PLAN_NOT_FOUND");
    }

    @Test
    void rejectsChangedCompileIntentUnderTheSamePlanIdentity() {
        MirrorPlanCreateRequest first = request("shared-plan", closure,
                graphFingerprint, fixtureRef(storedFixture), Duration.ofMinutes(5));
        service.create(first, identity());
        MirrorPlanCreateRequest changed = request("shared-plan", closure,
                graphFingerprint, fixtureRef(storedFixture), Duration.ofMinutes(6));

        assertProblem(() -> service.create(changed, identity()),
                409, "RG.MIRROR.PLAN_IDEMPOTENCY_CONFLICT");
        assertThat(plans.values).hasSize(1);
    }

    @Test
    void rejectsGraphAndFixtureFingerprintDriftBeforeCompilation() {
        MirrorPlanCreateRequest staleGraph = request("stale-graph", closure,
                fingerprint('9'), fixtureRef(storedFixture), Duration.ofMinutes(5));
        assertProblem(() -> service.create(staleGraph, identity()),
                409, "RG.MIRROR.GRAPH_ARTIFACT_STALE");

        MirrorArtifactRef staleFixture = new MirrorArtifactRef("FIXTURE_BUNDLE",
                storedFixture.fixtureBundleId(), storedFixture.revision(), fingerprint('8'));
        MirrorPlanCreateRequest badFixture = request("stale-fixture", closure,
                graphFingerprint, staleFixture, Duration.ofMinutes(5));
        assertProblem(() -> service.create(badFixture, identity()),
                409, "RG.MIRROR.FIXTURE_FINGERPRINT_CONFLICT");
        assertThat(plans.values).isEmpty();
    }

    @Test
    void hidesAValidClosureAndPlanOutsideTheCompleteEnterpriseScope() {
        CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                "tenant-a", "org-b", "support", "test", "sg");
        CapabilityClosure otherClosure = closure(other, graphFingerprint);
        assertProblem(() -> service.create(request("foreign", otherClosure,
                        graphFingerprint, fixtureRef(storedFixture), Duration.ofMinutes(5)), identity()),
                404, "RG.MIRROR.CAPABILITY_CLOSURE_NOT_FOUND");

        service.create(request("scoped-plan", closure,
                graphFingerprint, fixtureRef(storedFixture), Duration.ofMinutes(5)), identity());
        assertProblem(() -> service.find("scoped-plan", identity("org-b", "support", "test",
                        "sg", "MIRROR_REHEARSAL", "CONFIDENTIAL")),
                404, "RG.MIRROR.PLAN_NOT_FOUND");
    }

    @Test
    void refusesTenantEnvironmentFixtureLookupWithoutAnExactFullScopeBinding() {
        fixtureScopes.values.clear();

        assertProblem(() -> service.create(request("unbound", closure,
                        graphFingerprint, fixtureRef(storedFixture), Duration.ofMinutes(5)), identity()),
                404, "RG.MIRROR.FIXTURE_NOT_FOUND");
        assertThat(plans.values).isEmpty();
    }

    @Test
    void failsClosedForWrongPurposeProductionEnvironmentIncompleteScopeAndClearance() {
        MirrorPlanCreateRequest request = request("guarded", closure,
                graphFingerprint, fixtureRef(storedFixture), Duration.ofMinutes(5));
        assertProblem(() -> service.create(request, identity("org-a", "support", "test",
                        "sg", "TEST_EXECUTION", "CONFIDENTIAL")),
                403, "RG.MIRROR.PURPOSE_REQUIRED");
        assertProblem(() -> service.create(request, identity("org-a", "support", "production",
                        "sg", "MIRROR_REHEARSAL", "CONFIDENTIAL")),
                403, "RG.MIRROR.ENVIRONMENT_FORBIDDEN");
        assertProblem(() -> service.create(request, identity("org-a", "", "test",
                        "sg", "MIRROR_REHEARSAL", "CONFIDENTIAL")),
                400, "RG.MIRROR.SCOPE_INCOMPLETE");
        assertProblem(() -> service.create(request, identity("org-a", "support", "test",
                        "sg", "MIRROR_REHEARSAL", "INTERNAL")),
                403, "RG.MIRROR.FIXTURE_CLEARANCE_REQUIRED");
    }

    @Test
    void rejectsUnboundedRequestsAndExpiredOrOverlongPlans() {
        MirrorPlanCreateRequest tooSlow = new MirrorPlanCreateRequest("", "slow", "customerView",
                graphFingerprint, closure, fixtureRef(storedFixture), 100,
                Duration.ofMinutes(16), false, NOW.plus(Duration.ofHours(1)));
        assertProblem(() -> service.create(tooSlow, identity()),
                400, "RG.MIRROR.PLAN_REQUEST_INVALID");

        MirrorPlanCreateRequest expired = new MirrorPlanCreateRequest("", "expired", "customerView",
                graphFingerprint, closure, fixtureRef(storedFixture), 100,
                Duration.ofMinutes(5), false, NOW);
        assertProblem(() -> service.create(expired, identity()),
                400, "RG.MIRROR.PLAN_EXPIRY_INVALID");

        MirrorPlanCreateRequest tooLong = new MirrorPlanCreateRequest("", "long", "customerView",
                graphFingerprint, closure, fixtureRef(storedFixture), 100,
                Duration.ofMinutes(5), false, NOW.plus(Duration.ofHours(25)));
        assertProblem(() -> service.create(tooLong, identity()),
                400, "RG.MIRROR.PLAN_EXPIRY_INVALID");
    }

    private MirrorPlanCreateRequest request(
            String planId,
            CapabilityClosure requestClosure,
            String expectedGraphFingerprint,
            MirrorArtifactRef fixtureRef,
            Duration timeout) {
        return new MirrorPlanCreateRequest("", planId, "customerView",
                expectedGraphFingerprint, requestClosure, fixtureRef, 1000, timeout,
                false, NOW.plus(Duration.ofHours(1)));
    }

    private CapabilityClosure closure(CapabilitySnapshot.Scope scope, String sourceFingerprint) {
        CapabilityContract contract = contract();
        ArtifactProvenance provenance = new ArtifactProvenance("",
                ArtifactProvenance.SourceType.OWNER, List.of(), scope.tenantId(),
                "MIRROR_REHEARSAL", null, null, null, null, List.of(), "owner-a",
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(2)), "");
        CapabilitySnapshot child = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "operator:customer.lookup", 1, "",
                        CapabilitySnapshot.Kind.EXTERNAL, scope,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.OPERATOR,
                                "customer.lookup", fingerprint('e')),
                        contract, new CapabilitySnapshot.RuntimeBinding("OPERATOR",
                        "customer.lookup", fingerprint('f'), true, List.of()),
                        List.of(), ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance, NOW));
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "graph:customerView", 1, "",
                        CapabilitySnapshot.Kind.COMPOSED, scope,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                                "customerView", sourceFingerprint),
                        contract, new CapabilitySnapshot.RuntimeBinding("BLOGE_GRAPH",
                        "customerView", fingerprint('d'), true, List.of()),
                        List.of(new CapabilitySnapshot.Dependency("loadCustomer",
                                CapabilityClosureIntegrity.reference(child), true, List.of())),
                        ownership(), CapabilitySnapshot.Lifecycle.ACTIVE, provenance, NOW));
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, child), ""));
    }

    private static CapabilityContract contract() {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(), EffectContract.readOnly(List.of("customer:*")),
                CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true),
                null, CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL,
                        false, List.of("sg"), false),
                CapabilityContract.SloContract.unspecified());
    }

    private static CapabilitySnapshot.Ownership ownership() {
        return new CapabilitySnapshot.Ownership("owner-a", "support", "pager");
    }

    private static Graph graph(String name, String nodeId, String operatorRef) {
        Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        nodes.put(nodeId, new NodeSpec(nodeId, operatorRef, null,
                ResilienceConfig.DEFAULT, Map.of(), OpaqueSchema.INSTANCE,
                OpaqueSchema.INSTANCE));
        return new Graph(name, nodes, List.of(), Set.of(nodeId), Set.of(nodeId),
                SchemaValidationLevel.OFF);
    }

    private static MirrorArtifactRef fixtureRef(StoredFixtureBundle fixture) {
        return new MirrorArtifactRef("FIXTURE_BUNDLE", fixture.fixtureBundleId(),
                fixture.revision(), fixture.fingerprint());
    }

    private static IntegrationRequestContext identity() {
        return identity("org-a", "support", "test", "sg",
                "MIRROR_REHEARSAL", "CONFIDENTIAL");
    }

    private static IntegrationRequestContext identity(
            String organization,
            String project,
            String environment,
            String region,
            String purpose,
            String clearance) {
        return new IntegrationRequestContext("tenant-a", organization, project, environment,
                region, "SERVICE", "mirror-client", "", purpose, "corr-1",
                Set.of("quality"), clearance, "");
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                });
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class InMemoryPlanRepository implements MirrorPlanRepository {
        private final Map<String, MirrorPlan> values = new LinkedHashMap<>();

        @Override
        public MirrorPlan create(MirrorPlan plan) {
            String key = key(plan.scope(), plan.planId());
            MirrorPlan existing = values.putIfAbsent(key, plan);
            if (existing != null && !existing.planFingerprint().equals(plan.planFingerprint())) {
                throw new IllegalArgumentException("conflict");
            }
            return existing == null ? plan : existing;
        }

        @Override
        public Optional<MirrorPlan> find(CapabilitySnapshot.Scope scope, String planId) {
            return Optional.ofNullable(values.get(key(scope, planId)));
        }

        private static String key(CapabilitySnapshot.Scope scope, String planId) {
            return scope + ":" + planId;
        }
    }

    private record FixedFixtureRepository(StoredFixtureBundle fixture)
            implements FixtureBundleRepository {
        @Override
        public StoredFixtureBundle create(StoredFixtureBundle fixtureBundle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredFixtureBundle> find(
                String tenantId,
                String environmentId,
                String fixtureBundleId,
                long revision) {
            if (fixture.tenantId().equals(tenantId)
                    && fixture.environmentId().equals(environmentId)
                    && fixture.fixtureBundleId().equals(fixtureBundleId)
                    && fixture.revision() == revision) {
                return Optional.of(fixture);
            }
            return Optional.empty();
        }
    }

    private static final class InMemoryFixtureScopeRepository
            implements MirrorFixtureScopeRepository {
        private final Map<String, MirrorFixtureScopeBinding> values = new LinkedHashMap<>();

        @Override
        public MirrorFixtureScopeBinding create(MirrorFixtureScopeBinding binding) {
            String key = key(binding.scope(), binding.fixtureBundleRef().id(),
                    binding.fixtureBundleRef().revision());
            MirrorFixtureScopeBinding existing = values.putIfAbsent(key, binding);
            if (existing != null
                    && !existing.fixtureBundleRef().equals(binding.fixtureBundleRef())) {
                throw new IllegalArgumentException("conflict");
            }
            return existing == null ? binding : existing;
        }

        @Override
        public Optional<MirrorFixtureScopeBinding> find(
                CapabilitySnapshot.Scope scope, String fixtureBundleId, long revision) {
            return Optional.ofNullable(values.get(key(scope, fixtureBundleId, revision)));
        }

        private static String key(
                CapabilitySnapshot.Scope scope, String fixtureBundleId, long revision) {
            return scope + ":" + fixtureBundleId + ":" + revision;
        }
    }

    private static final class ReadOnlyOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }
}
