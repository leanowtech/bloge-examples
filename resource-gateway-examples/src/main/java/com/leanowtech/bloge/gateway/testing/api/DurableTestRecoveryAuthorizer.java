package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorMicroGraphRunner;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconstructs and re-authorizes every immutable dependency before a durable owner claim.
 *
 * <p>Authorization is affirmative. Exact graph/operator content, fixture registry value, replay
 * closure, identity policy, side-effect policy, execution-service state, and effective-plan
 * fingerprint must all agree. This component never mutates the checkpoint and never falls back to
 * current/latest dependencies or a real side-effecting binding.</p>
 */
public class DurableTestRecoveryAuthorizer {

    private static final Set<String> FIXTURE_CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final GatewayGraphService graphService;
    private final OperatorRegistry operatorRegistry;
    private final ResourceRegistry resourceRegistry;
    private final FixtureBundleRepository fixtureRepository;
    private final TestReplayPayloadService replayPayloads;
    private final DurableTestRecoveryAuthority authority;
    private final ObjectMapper objectMapper;
    private final ExecutionControlCompiler compiler;

    /**
     * Creates the recovery dependency authorization boundary.
     *
     * @param graphService current graph registry
     * @param operatorRegistry current operator binding registry
     * @param resourceRegistry current resource descriptor registry
     * @param fixtureRepository immutable fixture registry
     * @param replayPayloads governed replay resolver
     * @param authority current integration identity policy snapshotter
     * @param objectMapper canonical protocol mapper
     */
    public DurableTestRecoveryAuthorizer(
            GatewayGraphService graphService,
            OperatorRegistry operatorRegistry,
            ResourceRegistry resourceRegistry,
            FixtureBundleRepository fixtureRepository,
            TestReplayPayloadService replayPayloads,
            DurableTestRecoveryAuthority authority,
            ObjectMapper objectMapper) {
        this.graphService = Objects.requireNonNull(graphService, "graphService");
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.resourceRegistry = Objects.requireNonNull(resourceRegistry, "resourceRegistry");
        this.fixtureRepository = Objects.requireNonNull(fixtureRepository, "fixtureRepository");
        this.replayPayloads = Objects.requireNonNull(replayPayloads, "replayPayloads");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.compiler = new ExecutionControlCompiler(operatorRegistry, objectMapper);
    }

    /**
     * Proves that one checkpoint can still be recovered under current authorized dependencies.
     *
     * @param checkpoint integrity-verified v2 checkpoint
     * @param identity freshly verified recovery caller
     */
    public void authorize(
            DurableTestExecutionCheckpoint checkpoint, IntegrationRequestContext identity) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(identity, "identity").requireComplete();
        DurableTestExecutionCheckpoint.ControlDependencies dependencies = checkpoint.dependencies();
        DurableTestExecutionCheckpoint.ExecutionTargetRef target = dependencies.target();
        if (!DurableTestExecutionCheckpoint.SCHEMA_VERSION.equals(checkpoint.schemaVersion())
                || target == null) {
            throw unavailable(identity, "CHECKPOINT");
        }
        requireAuthority(dependencies, identity);
        AuthorizedTarget authorizedTarget = resolveTarget(target, identity);
        FixtureBundle fixture = resolveFixture(dependencies, identity);
        ResolvedReplayPayloads replays = resolveReplays(fixture, identity);
        if (!replays.planDependencies().equals(dependencies.plan().replayDependencies())) {
            throw unavailable(identity, "REPLAY");
        }
        String expectedSideEffectPolicy = replays.references().isEmpty()
                ? "DENY_REAL" : "REPLAY_ONLY";
        if (!expectedSideEffectPolicy.equals(dependencies.sideEffectPolicy())) {
            throw unavailable(identity, "PLAN");
        }

        CompiledExecutionControl compiled;
        try {
            compiled = compiler.compile(authorizedTarget.graph(), fixture,
                    dependencies.plan().authorizedPurpose(), target.fingerprint(), replays,
                    checkpoint.executionServiceState());
        } catch (IllegalArgumentException rejected) {
            throw unavailable(identity, "PLAN");
        } catch (RuntimeException infrastructure) {
            throw dependencyStoreUnavailable(identity, "PLAN");
        }
        EffectiveExecutionPlan rebuilt = compiled.effectivePlan();
        if (!rebuilt.planFingerprint().equals(dependencies.plan().planFingerprint())
                || !rebuilt.targetFingerprint().equals(dependencies.plan().targetFingerprint())
                || !rebuilt.fixtureBundleFingerprint().equals(
                dependencies.plan().fixtureBundleFingerprint())
                || !rebuilt.resolvedSites().equals(dependencies.plan().resolvedSites())
                || !rebuilt.replayDependencies().equals(dependencies.plan().replayDependencies())
                || !rebuilt.executionServiceBindings().equals(
                dependencies.plan().executionServiceBindings())
                || !rebuilt.defaultPolicies().equals(dependencies.plan().defaultPolicies())) {
            throw unavailable(identity, "PLAN");
        }
    }

    private void requireAuthority(
            DurableTestExecutionCheckpoint.ControlDependencies dependencies,
            IntegrationRequestContext identity) {
        DurableTestExecutionCheckpoint.AuthoritySnapshot current;
        try {
            current = authority.currentSnapshot();
        } catch (RuntimeException unavailable) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.TEST.DURABLE_AUTHORITY_UNAVAILABLE",
                    "The durable recovery identity authority is unavailable.",
                    identity.correlationId(), Map.of("dependencyKind", "AUTHORITY")));
        }
        if (!current.equals(dependencies.identitySnapshot())) {
            throw unavailable(identity, "AUTHORITY");
        }
    }

    private AuthorizedTarget resolveTarget(
            DurableTestExecutionCheckpoint.ExecutionTargetRef target,
            IntegrationRequestContext identity) {
        try {
            return switch (target.kind()) {
                case "GRAPH" -> {
                    Graph graph = graphService.requireGraph(target.id());
                    GraphExecutionTargetSnapshot snapshot = GraphExecutionTargetSnapshot.capture(
                            objectMapper, graph, resourceRegistry);
                    if (!target.fingerprint().equals(snapshot.fingerprint())) {
                        throw unavailable(identity, "TARGET");
                    }
                    yield new AuthorizedTarget(snapshot.graph());
                }
                case "OPERATOR" -> {
                    OperatorExecutionTargetSnapshot snapshot =
                            OperatorExecutionTargetSnapshot.capture(objectMapper, target.id(),
                                    operatorRegistry, resourceRegistry);
                    if (!snapshot.executionSupported()
                            || !target.fingerprint().equals(snapshot.fingerprint())) {
                        throw unavailable(identity, "TARGET");
                    }
                    yield new AuthorizedTarget(OperatorMicroGraphRunner.microGraph(
                            snapshot.operatorRef(), snapshot.synchronousOperator()));
                }
                default -> throw unavailable(identity, "TARGET");
            };
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (IllegalArgumentException absentOrInvalid) {
            throw unavailable(identity, "TARGET");
        } catch (RuntimeException infrastructure) {
            throw dependencyStoreUnavailable(identity, "TARGET");
        }
    }

    private FixtureBundle resolveFixture(
            DurableTestExecutionCheckpoint.ControlDependencies dependencies,
            IntegrationRequestContext identity) {
        DurableTestExecutionCheckpoint.ExactFixtureRef ref = dependencies.fixture();
        StoredFixtureBundle stored;
        try {
            stored = fixtureRepository.find(identity.tenantId(), identity.environmentId(),
                    ref.fixtureBundleId(), ref.revision()).orElse(null);
        } catch (RuntimeException infrastructure) {
            throw dependencyStoreUnavailable(identity, "FIXTURE");
        }
        FixtureBundle bundle = stored == null ? null : stored.bundle();
        String actualFingerprint;
        try {
            actualFingerprint = bundle == null ? "" : ProtocolFingerprint.of(objectMapper, bundle);
        } catch (RuntimeException corrupt) {
            throw unavailable(identity, "FIXTURE");
        }
        if (stored == null || !StoredFixtureBundle.SCHEMA_VERSION.equals(stored.schemaVersion())
                || !identity.tenantId().equals(stored.tenantId())
                || !identity.environmentId().equals(stored.environmentId())
                || !ref.fixtureBundleId().equals(stored.fixtureBundleId())
                || ref.revision() != stored.revision()
                || !ref.fingerprint().equals(stored.fingerprint())
                || !ref.fingerprint().equals(actualFingerprint)
                || !FixtureBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())
                || !ref.fixtureBundleId().equals(bundle.fixtureBundleId())
                || ref.revision() != bundle.revision()
                || !dependencies.plan().targetFingerprint().equals(bundle.targetFingerprint())) {
            throw unavailable(identity, "FIXTURE");
        }
        String classification = normalized(bundle.classification()).toUpperCase(Locale.ROOT);
        if (!FIXTURE_CLASSIFICATIONS.contains(classification)
                || !identity.hasClearanceAtLeast(classification)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.DURABLE_FIXTURE_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot authorize the durable fixture.",
                    identity.correlationId(), Map.of("classification", classification)));
        }
        return bundle;
    }

    private ResolvedReplayPayloads resolveReplays(
            FixtureBundle fixture, IntegrationRequestContext identity) {
        boolean containsReplay = fixture.rules().stream().filter(Objects::nonNull)
                .anyMatch(rule -> rule.behavior().kind() == FixtureRule.BehaviorKind.REPLAY);
        if (!containsReplay) {
            return ResolvedReplayPayloads.empty();
        }
        try {
            return replayPayloads.resolve(fixture, identity);
        } catch (IntegrationProblemException problem) {
            if (problem.problem().status() == 403 || problem.problem().status() == 503) {
                throw problem;
            }
            throw unavailable(identity, "REPLAY");
        } catch (RuntimeException infrastructure) {
            throw dependencyStoreUnavailable(identity, "REPLAY");
        }
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String dependencyKind) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                "RG.TEST.DURABLE_CONTROL_PLAN_UNAVAILABLE",
                "The exact durable recovery dependency closure is no longer available.",
                identity.correlationId(), Map.of("dependencyKind", dependencyKind)));
    }

    private static IntegrationProblemException dependencyStoreUnavailable(
            IntegrationRequestContext identity, String dependencyKind) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.TEST.DURABLE_DEPENDENCY_STORE_UNAVAILABLE",
                "A required durable recovery dependency authority is unavailable.",
                identity.correlationId(), Map.of("dependencyKind", dependencyKind)));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record AuthorizedTarget(Graph graph) {
        private AuthorizedTarget {
            Objects.requireNonNull(graph, "graph");
        }
    }
}
