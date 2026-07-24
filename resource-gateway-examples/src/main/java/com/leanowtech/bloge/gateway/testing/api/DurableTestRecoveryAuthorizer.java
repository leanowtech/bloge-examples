package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryAuthorization;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorInputCoercer;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorMicroGraphRunner;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;

import java.util.LinkedHashMap;
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
    private final TestSecretResolutionService testSecrets;
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
        this(graphService, operatorRegistry, resourceRegistry, fixtureRepository, replayPayloads,
                authority, objectMapper, null);
    }

    /**
     * Creates recovery authorization with fresh external test-secret re-authorization.
     *
     * @param graphService current graph registry
     * @param operatorRegistry current operator binding registry
     * @param resourceRegistry current resource descriptor registry
     * @param fixtureRepository immutable fixture registry
     * @param replayPayloads governed replay resolver
     * @param authority current integration identity policy snapshotter
     * @param objectMapper canonical protocol mapper
     * @param testSecrets external test-secret trust transition
     */
    public DurableTestRecoveryAuthorizer(
            GatewayGraphService graphService,
            OperatorRegistry operatorRegistry,
            ResourceRegistry resourceRegistry,
            FixtureBundleRepository fixtureRepository,
            TestReplayPayloadService replayPayloads,
            DurableTestRecoveryAuthority authority,
            ObjectMapper objectMapper,
            TestSecretResolutionService testSecrets) {
        this.graphService = Objects.requireNonNull(graphService, "graphService");
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.resourceRegistry = Objects.requireNonNull(resourceRegistry, "resourceRegistry");
        this.fixtureRepository = Objects.requireNonNull(fixtureRepository, "fixtureRepository");
        this.replayPayloads = Objects.requireNonNull(replayPayloads, "replayPayloads");
        this.testSecrets = testSecrets;
        this.authority = Objects.requireNonNull(authority, "authority");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.compiler = new ExecutionControlCompiler(operatorRegistry, objectMapper);
    }

    /**
     * Proves that one checkpoint can still be recovered under current authorized dependencies.
     *
     * @param checkpoint integrity-verified v2 checkpoint
     * @param identity freshly verified recovery caller
     * @return exact executable closure and its payload-free authorization receipt
     */
    public AuthorizedRecovery authorize(
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
        AuthorizedTarget authorizedTarget = resolveRecoveryTarget(checkpoint, target, identity);
        FixtureBundle fixture = resolveFixture(dependencies, identity);
        ResolvedReplayPayloads replays = resolveReplays(fixture, identity);
        ResolvedTestSecrets secrets = resolveSecrets(fixture, target.fingerprint(),
                dependencies.plan().authorizedPurpose(), identity);
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
            compiled = compiler.compileWithSecrets(authorizedTarget.graph(), fixture,
                    dependencies.plan().authorizedPurpose(), target.fingerprint(), replays,
                    secrets, checkpoint.executionServiceState());
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
        DurableTestRecoveryAuthorization authorization =
                DurableTestRecoveryAuthorization.issue(
                        objectMapper,
                        checkpoint.checkpointFingerprint(),
                        DurableTestRecoveryPrincipal.fingerprint(objectMapper, identity),
                        target.fingerprint(),
                        rebuilt.planFingerprint(),
                        dependencies.fixture().fingerprint(),
                        ProtocolFingerprint.of(objectMapper, rebuilt.replayDependencies()),
                        checkpoint.executionServiceState().snapshotFingerprint(),
                        dependencies.identitySnapshot().fingerprint(),
                        rebuilt.authorizedPurpose(),
                        dependencies.sideEffectPolicy());
        return new AuthorizedRecovery(authorizedTarget.graph(), compiled,
                authorizedTarget.dependencyRefs(), authorization);
    }

    /**
     * Freezes and authorizes every dependency required by a fresh durable graph execution.
     *
     * <p>The caller must already have passed transport and shape validation. This method proves the
     * exact current graph fingerprint, immutable stored fixture, replay closure, authority snapshot,
     * graph input contract, and effective execution plan before a creation reservation may execute.
     * No latest alias or inline fixture is accepted.</p>
     *
     * @param request exact public creation request
     * @param identity verified non-production execution identity
     * @return frozen executable closure and payload-free checkpoint dependencies
     */
    public AuthorizedCreation authorizeCreation(
            DurableTestExecutionCreateRequest request,
            IntegrationRequestContext identity) {
        DurableTestExecutionCreateRequest requiredRequest = Objects.requireNonNull(
                request, "request");
        Objects.requireNonNull(identity, "identity").requireComplete();
        TestExecutionApiRequest.Target requestedTarget = Objects.requireNonNull(
                requiredRequest.target(), "target");
        DurableTestExecutionCheckpoint.ExecutionTargetRef target =
                new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                        requestedTarget.kind(), requestedTarget.id(),
                        requestedTarget.fingerprint());
        if (!"GRAPH".equals(target.kind())) {
            throw unavailable(identity, "TARGET");
        }
        AuthorizedTarget authorizedTarget = resolveTarget(target, identity);
        try {
            graphService.validateInput(target.id(), new GraphContext(requiredRequest.context()));
        } catch (IllegalArgumentException invalidInput) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_GRAPH_INPUT_INVALID",
                    "Durable graph input does not satisfy the exact graph contract.",
                    identity.correlationId(), Map.of()));
        } catch (RuntimeException infrastructure) {
            throw dependencyStoreUnavailable(identity, "TARGET");
        }

        return authorizeFreshCreation(authorizedTarget, target,
                requiredRequest.fixtureBundleRef(), TestExecutionApiService.AUTHORIZED_PURPOSE,
                identity);
    }

    /**
     * Freezes a fresh durable operator test and converts its formal input after target verification.
     *
     * <p>The returned context is server-derived for the canonical start-gated micro graph. The caller
     * cannot inject the internal {@code operatorInput} context key directly. Input conversion and
     * exact binding resolution happen only after the idempotent command replay lookup performed by
     * the application service.</p>
     *
     * @param operatorRef path-bound operator registry reference
     * @param request exact durable operator creation request
     * @param identity verified non-production execution identity
     * @return exact executable authorization plus its server-derived micro-graph context
     */
    public AuthorizedOperatorCreation authorizeOperatorCreation(
            String operatorRef,
            DurableOperatorTestExecutionCreateRequest request,
            IntegrationRequestContext identity) {
        DurableOperatorTestExecutionCreateRequest requiredRequest = Objects.requireNonNull(
                request, "request");
        Objects.requireNonNull(identity, "identity").requireComplete();
        TestExecutionApiRequest.Target requestedTarget = Objects.requireNonNull(
                requiredRequest.target(), "target");
        DurableTestExecutionCheckpoint.ExecutionTargetRef target =
                new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                        requestedTarget.kind(), requestedTarget.id(),
                        requestedTarget.fingerprint());
        if (!"OPERATOR".equals(target.kind()) || !normalized(operatorRef).equals(target.id())) {
            throw unavailable(identity, "TARGET");
        }
        AuthorizedOperatorTarget operatorTarget = resolveOperatorTarget(target, identity);
        Object typedInput;
        try {
            typedInput = OperatorInputCoercer.coerce(
                    requiredRequest.input(), operatorTarget.snapshot().metadata(), objectMapper);
        } catch (IllegalArgumentException invalidInput) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_OPERATOR_INPUT_INVALID",
                    "Durable operator input does not satisfy the exact operator contract.",
                    identity.correlationId(), Map.of()));
        }
        AuthorizedCreation authorization = authorizeFreshCreation(
                operatorTarget.target(), target, requiredRequest.fixtureBundleRef(),
                TestExecutionApiService.AUTHORIZED_OPERATOR_PURPOSE, identity);
        Map<String, Object> context = new LinkedHashMap<>();
        if (typedInput != null) {
            context.put("operatorInput", typedInput);
        }
        return new AuthorizedOperatorCreation(authorization, context);
    }

    private AuthorizedCreation authorizeFreshCreation(
            AuthorizedTarget authorizedTarget,
            DurableTestExecutionCheckpoint.ExecutionTargetRef target,
            TestExecutionApiRequest.FixtureBundleRef requestedFixture,
            String authorizedPurpose,
            IntegrationRequestContext identity) {

        Objects.requireNonNull(requestedFixture, "fixtureBundleRef");
        DurableTestExecutionCheckpoint.ExactFixtureRef fixtureRef =
                new DurableTestExecutionCheckpoint.ExactFixtureRef(
                        requestedFixture.fixtureBundleId(), requestedFixture.revision(),
                        requestedFixture.fingerprint());
        FixtureBundle fixture = resolveFixture(fixtureRef, target.fingerprint(), identity);
        ResolvedReplayPayloads replays = resolveReplays(fixture, identity);
        ResolvedTestSecrets secrets = resolveSecrets(
                fixture, target.fingerprint(), authorizedPurpose, identity);
        String sideEffectPolicy = replays.references().isEmpty()
                ? "DENY_REAL" : "REPLAY_ONLY";
        CompiledExecutionControl compiled;
        try {
            compiled = compiler.compileWithSecrets(authorizedTarget.graph(), fixture,
                    authorizedPurpose, target.fingerprint(), replays, secrets);
        } catch (IllegalArgumentException rejected) {
            throw unavailable(identity, "PLAN");
        } catch (RuntimeException infrastructure) {
            throw dependencyStoreUnavailable(identity, "PLAN");
        }
        DurableTestExecutionCheckpoint.AuthoritySnapshot authoritySnapshot =
                currentAuthority(identity);
        DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        compiled.effectivePlan(), fixtureRef, sideEffectPolicy,
                        authoritySnapshot, target);
        String authorizationFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.durableTestCreationAuthorization.v1",
                "principalFingerprint", DurableTestRecoveryPrincipal.fingerprint(
                        objectMapper, identity),
                "dependencies", dependencies,
                "replayDependenciesFingerprint", ProtocolFingerprint.of(
                        objectMapper, compiled.effectivePlan().replayDependencies())));
        return new AuthorizedCreation(
                authorizedTarget.graph(), compiled, authorizedTarget.dependencyRefs(), dependencies,
                authorizationFingerprint);
    }

    private void requireAuthority(
            DurableTestExecutionCheckpoint.ControlDependencies dependencies,
            IntegrationRequestContext identity) {
        DurableTestExecutionCheckpoint.AuthoritySnapshot current = currentAuthority(identity);
        if (!current.equals(dependencies.identitySnapshot())) {
            throw unavailable(identity, "AUTHORITY");
        }
    }

    private DurableTestExecutionCheckpoint.AuthoritySnapshot currentAuthority(
            IntegrationRequestContext identity) {
        try {
            return authority.currentSnapshot();
        } catch (RuntimeException unavailable) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.TEST.DURABLE_AUTHORITY_UNAVAILABLE",
                    "The durable recovery identity authority is unavailable.",
                    identity.correlationId(), Map.of("dependencyKind", "AUTHORITY")));
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
                    yield new AuthorizedTarget(
                            snapshot.graph(), snapshot.dependencyFingerprints().keySet());
                }
                case "OPERATOR" -> {
                    yield resolveOperatorTarget(target, identity, false).target();
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

    private AuthorizedTarget resolveRecoveryTarget(
            DurableTestExecutionCheckpoint checkpoint,
            DurableTestExecutionCheckpoint.ExecutionTargetRef target,
            IntegrationRequestContext identity) {
        if (!"OPERATOR".equals(target.kind())) {
            return resolveTarget(target, identity);
        }
        boolean startGated = checkpoint.engineState() != null
                && OperatorMicroGraphRunner.DURABLE_START_NODE_ID.equals(
                checkpoint.engineState().nodeId());
        return resolveOperatorTarget(target, identity, startGated).target();
    }

    private AuthorizedOperatorTarget resolveOperatorTarget(
            DurableTestExecutionCheckpoint.ExecutionTargetRef target,
            IntegrationRequestContext identity) {
        return resolveOperatorTarget(target, identity, true);
    }

    private AuthorizedOperatorTarget resolveOperatorTarget(
            DurableTestExecutionCheckpoint.ExecutionTargetRef target,
            IntegrationRequestContext identity,
            boolean startGated) {
        try {
            OperatorExecutionTargetSnapshot snapshot = OperatorExecutionTargetSnapshot.capture(
                    objectMapper, target.id(), operatorRegistry, resourceRegistry);
            if (!snapshot.executionSupported()
                    || !target.fingerprint().equals(snapshot.fingerprint())) {
                throw unavailable(identity, "TARGET");
            }
            return new AuthorizedOperatorTarget(new AuthorizedTarget(
                    startGated
                            ? OperatorMicroGraphRunner.durableMicroGraph(
                            snapshot.operatorRef(), snapshot.synchronousOperator())
                            : OperatorMicroGraphRunner.microGraph(
                            snapshot.operatorRef(), snapshot.synchronousOperator()),
                    snapshot.resourceDependencyFingerprints().keySet()), snapshot);
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
        return resolveFixture(dependencies.fixture(),
                dependencies.plan().targetFingerprint(), identity);
    }

    private FixtureBundle resolveFixture(
            DurableTestExecutionCheckpoint.ExactFixtureRef ref,
            String targetFingerprint,
            IntegrationRequestContext identity) {
        StoredFixtureBundle stored;
        TestingArtifactScope scope = TestingArtifactScope.from(identity);
        try {
            stored = fixtureRepository.find(
                    scope, ref.fixtureBundleId(), ref.revision()).orElse(null);
            if (stored != null) {
                stored = StoredFixtureBundleIntegrity.verifiedSnapshot(
                        objectMapper, stored, scope, ref.fixtureBundleId(), ref.revision());
            }
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
                || !identity.organizationId().equals(stored.organizationId())
                || !identity.projectId().equals(stored.projectId())
                || !identity.environmentId().equals(stored.environmentId())
                || !identity.region().equals(stored.region())
                || !ref.fixtureBundleId().equals(stored.fixtureBundleId())
                || ref.revision() != stored.revision()
                || !ref.fingerprint().equals(stored.fingerprint())
                || !ref.fingerprint().equals(actualFingerprint)
                || !FixtureBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())
                || !ref.fixtureBundleId().equals(bundle.fixtureBundleId())
                || ref.revision() != bundle.revision()
                || !targetFingerprint.equals(bundle.targetFingerprint())) {
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

    private ResolvedTestSecrets resolveSecrets(
            FixtureBundle fixture,
            String targetFingerprint,
            String authorizedPurpose,
            IntegrationRequestContext identity) {
        FixtureExecutionServices controls = FixtureExecutionServices.from(fixture);
        if (controls.secretRefs().isEmpty()) {
            return ResolvedTestSecrets.empty();
        }
        if (testSecrets == null) {
            throw dependencyStoreUnavailable(identity, "SECRET_AUTHORITY");
        }
        try {
            return testSecrets.resolve(fixture, targetFingerprint, fixture.targetFingerprint(),
                    authorizedPurpose, identity);
        } catch (IntegrationProblemException problem) {
            if (problem.problem().status() == 403 || problem.problem().status() == 503) {
                throw problem;
            }
            throw unavailable(identity, "SECRET_AUTHORITY");
        } catch (RuntimeException infrastructure) {
            throw dependencyStoreUnavailable(identity, "SECRET_AUTHORITY");
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

    private record AuthorizedTarget(Graph graph, Set<String> dependencyRefs) {
        private AuthorizedTarget {
            Objects.requireNonNull(graph, "graph");
            dependencyRefs = dependencyRefs == null ? Set.of() : Set.copyOf(dependencyRefs);
        }
    }

    private record AuthorizedOperatorTarget(
            AuthorizedTarget target, OperatorExecutionTargetSnapshot snapshot) {
        private AuthorizedOperatorTarget {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Server-internal executable closure paired with its payload-free durable authorization proof.
     *
     * <p>The graph and compiled controls may be handed directly to an in-process worker. Only the
     * authorization receipt is persistence-safe; a cold worker must reconstruct the executable
     * objects and reproduce the same receipt before execution.</p>
     *
     * @param graph exact graph or canonical operator micro-graph
     * @param control exact fixture, replay, and execution-service controls
     * @param dependencyRefs exact or conservatively frozen external resource references
     * @param authorization payload-free content-addressed authorization receipt
     */
    public record AuthorizedRecovery(
            Graph graph,
            CompiledExecutionControl control,
            Set<String> dependencyRefs,
            DurableTestRecoveryAuthorization authorization) {
        /** Compatibility constructor for focused runtimes without external dependencies. */
        public AuthorizedRecovery(
                Graph graph,
                CompiledExecutionControl control,
                DurableTestRecoveryAuthorization authorization) {
            this(graph, control, Set.of(), authorization);
        }

        /** Requires all executable and auditable parts of the authorization decision. */
        public AuthorizedRecovery {
            graph = Objects.requireNonNull(graph, "graph");
            control = Objects.requireNonNull(control, "control");
            dependencyRefs = dependencyRefs == null ? Set.of() : Set.copyOf(dependencyRefs);
            authorization = Objects.requireNonNull(authorization, "authorization");
        }
    }

    /**
     * Server-internal executable closure for one fresh durable graph execution.
     *
     * @param graph exact graph selected by the caller's content fingerprint
     * @param control exact fixture, replay, operator, and execution-service controls
     * @param dependencyRefs exact or conservatively frozen external resource references
     * @param dependencies payload-free immutable checkpoint dependency closure
     * @param authorizationFingerprint complete principal and dependency authorization identity
     */
    public record AuthorizedCreation(
            Graph graph,
            CompiledExecutionControl control,
            Set<String> dependencyRefs,
            DurableTestExecutionCheckpoint.ControlDependencies dependencies,
            String authorizationFingerprint) {
        /** Compatibility constructor for focused runtimes without external dependencies. */
        public AuthorizedCreation(
                Graph graph,
                CompiledExecutionControl control,
                DurableTestExecutionCheckpoint.ControlDependencies dependencies,
                String authorizationFingerprint) {
            this(graph, control, Set.of(), dependencies, authorizationFingerprint);
        }

        /** Requires all executable and payload-free authorization material. */
        public AuthorizedCreation {
            graph = Objects.requireNonNull(graph, "graph");
            control = Objects.requireNonNull(control, "control");
            dependencyRefs = dependencyRefs == null ? Set.of() : Set.copyOf(dependencyRefs);
            dependencies = Objects.requireNonNull(dependencies, "dependencies");
            authorizationFingerprint = authorizationFingerprint == null
                    ? "" : authorizationFingerprint.trim();
            if (!authorizationFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "Creation authorization fingerprint must be canonical SHA-256");
            }
        }
    }

    /**
     * Fresh operator authorization paired with the only business context accepted by its micro graph.
     *
     * @param authorization exact payload-free durable dependency closure
     * @param context server-derived context containing only the converted formal operator input
     */
    public record AuthorizedOperatorCreation(
            AuthorizedCreation authorization, Map<String, Object> context) {
        /** Requires the authorization and freezes the top-level server-owned context. */
        public AuthorizedOperatorCreation {
            authorization = Objects.requireNonNull(authorization, "authorization");
            context = context == null ? Map.of() : Map.copyOf(context);
        }
    }
}
