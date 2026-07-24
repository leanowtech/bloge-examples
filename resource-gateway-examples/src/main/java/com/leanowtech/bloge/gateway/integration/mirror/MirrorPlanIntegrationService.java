package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleIntegrityException;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundleIntegrity;
import com.leanowtech.bloge.gateway.testing.api.TestReplayPayloadService;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompilationRequest;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanRejectedException;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated application boundary for compiling and reading immutable mirror plans.
 *
 * <p>This service is deliberately not a thin wrapper around {@link MirrorPlanCompiler}. It closes
 * every mutable lookup first: the current graph is fingerprinted, the fixture envelope is
 * independently verified against the full lookup key, governed replay values are frozen, and the
 * sealed capability scope is compared with the authenticated enterprise scope. Only then is the
 * pure compiler invoked. Exact retries reuse the original server compilation instant and therefore
 * produce the same plan fingerprint; a changed request under the same plan id is rejected.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorPlanIntegrationService {
    /** Only the dedicated non-production purpose may compile or read mirror plans. */
    public static final String AUTHORIZED_PURPOSE = "MIRROR_REHEARSAL";
    /** Bounded Stage 1 plan lifetime. */
    public static final Duration MAXIMUM_PLAN_LIFETIME = Duration.ofHours(24);
    /** Bounded Stage 1 execution timeout. */
    public static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(15);
    /** Bounded Stage 1 static and dynamic invocation budget. */
    public static final int MAXIMUM_INVOCATIONS = 100_000;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final MirrorPlanCompiler compiler;
    private final MirrorPlanRepository plans;
    private final FixtureBundleRepository fixtures;
    private final MirrorFixtureScopeRepository fixtureScopes;
    private final TestReplayPayloadService replayPayloads;
    private final CapabilityCorpusServingService corpusServing;
    private final GatewayGraphService graphs;
    private final ObjectMapper mapper;
    private final MirrorOperationObservability observations;
    private final Clock clock;

    /**
     * Creates the protected plan application service.
     *
     * @param compiler pure exact-artifact mirror compiler
     * @param plans append-only plan repository
     * @param fixtures immutable governed fixture registry
     * @param fixtureScopes full-enterprise-scope fixture authorization index
     * @param replayPayloads governed replay resolver
     * @param corpusServing governed recorded-corpus serving boundary
     * @param graphs authoritative registered graph catalog
     * @param mapper canonical protocol mapper
     * @param observations mandatory audit-before-publish operation observer
     */
    @Autowired
    public MirrorPlanIntegrationService(
            MirrorPlanCompiler compiler,
            MirrorPlanRepository plans,
            FixtureBundleRepository fixtures,
            MirrorFixtureScopeRepository fixtureScopes,
            TestReplayPayloadService replayPayloads,
            CapabilityCorpusServingService corpusServing,
            GatewayGraphService graphs,
            ObjectMapper mapper,
            MirrorOperationObservability observations) {
        this(compiler, plans, fixtures, fixtureScopes, replayPayloads, corpusServing, graphs, mapper,
                observations, Clock.systemUTC());
    }

    /** Full constructor for deterministic application-service tests. */
    public MirrorPlanIntegrationService(
            MirrorPlanCompiler compiler,
            MirrorPlanRepository plans,
            FixtureBundleRepository fixtures,
            MirrorFixtureScopeRepository fixtureScopes,
            TestReplayPayloadService replayPayloads,
            CapabilityCorpusServingService corpusServing,
            GatewayGraphService graphs,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            Clock clock) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.fixtureScopes = Objects.requireNonNull(fixtureScopes, "fixtureScopes");
        this.replayPayloads = replayPayloads;
        this.corpusServing = corpusServing;
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Backward-compatible deterministic constructor for tests without corpus bindings.
     */
    public MirrorPlanIntegrationService(
            MirrorPlanCompiler compiler,
            MirrorPlanRepository plans,
            FixtureBundleRepository fixtures,
            MirrorFixtureScopeRepository fixtureScopes,
            TestReplayPayloadService replayPayloads,
            GatewayGraphService graphs,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            Clock clock) {
        this(compiler, plans, fixtures, fixtureScopes, replayPayloads, null,
                graphs, mapper, observations, clock);
    }

    /**
     * Resolves authoritative artifacts, compiles a sealed plan, and persists it append-only.
     *
     * @param request caller-reviewed content-addressed compile command
     * @param identity authenticated enterprise identity and purpose
     * @return newly persisted plan or the byte-equivalent result of an exact retry
     */
    @Transactional
    public MirrorPlan create(MirrorPlanCreateRequest request, IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation = observations.start(
                MirrorOperationAuditEvent.Operation.PLAN_CREATE, identity, "",
                request == null ? "" : request.planId(), "");
        MirrorPlan created;
        try {
            created = createPlan(request, identity);
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
        observation.succeeded("");
        return created;
    }

    private MirrorPlan createPlan(
            MirrorPlanCreateRequest request, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireMirrorIdentity(identity);
        validateRequest(request, identity);
        requireClosureScope(request.capabilityClosure(), scope, identity);

        Optional<MirrorPlan> existing = findStored(scope, request.planId(), identity);
        Instant compiledAt = existing.map(MirrorPlan::compiledAt).orElseGet(clock::instant);
        validateTemporalPolicy(request.expiresAt(), compiledAt, clock.instant(), identity);

        Graph graph = requireGraph(request.graphName(), identity);
        String currentGraphFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        if (!currentGraphFingerprint.equals(request.expectedGraphArtifactFingerprint())) {
            throw conflict(identity, "RG.MIRROR.GRAPH_ARTIFACT_STALE",
                    "The registered graph differs from the reviewed graph artifact.",
                    Map.of("currentGraphArtifactFingerprint", currentGraphFingerprint));
        }

        StoredFixtureBundle storedFixture = requireFixture(
                request.fixtureBundleRef(), scope, identity);
        FixtureBundle fixture = storedFixture.bundle();
        ResolvedReplayPayloads replays = resolveReplayPayloads(fixture, identity);
        MirrorPlan.ExecutionPolicy policy = serverPolicy(request, identity, scope);
        ResolvedCorpusPayloads corpora = resolveCorpusPayloads(
                fixture, scope, policy, request.expiresAt(), identity);

        CompiledMirrorPlan compiled;
        boolean ownershipTransferred = false;
        try {
            compiled = compiler.compile(new MirrorPlanCompilationRequest(
                    request.planId(), graph, currentGraphFingerprint,
                    request.capabilityClosure(), fixture, replays, corpora, policy, null,
                    compiledAt, request.expiresAt()));
            ownershipTransferred = true;
        } catch (MirrorPlanRejectedException rejected) {
            throw conflict(identity, rejected.code(),
                    "Mirror plan compilation rejected an inconsistent artifact closure.",
                    rejected.diagnostics().isEmpty()
                            ? Map.of() : Map.of("diagnostics", rejected.diagnostics()));
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity, "RG.MIRROR.PLAN_COMPILE_REQUEST_INVALID",
                    "Mirror plan compilation inputs are invalid.", Map.of());
        } finally {
            if (!ownershipTransferred) {
                corpora.close();
            }
        }

        try (compiled) {
            if (existing.isPresent()
                    && !existing.get().planFingerprint().equals(
                    compiled.plan().planFingerprint())) {
                throw conflict(identity, "RG.MIRROR.PLAN_IDEMPOTENCY_CONFLICT",
                        "The plan id already identifies different immutable compile inputs.",
                        Map.of());
            }
            try {
                return plans.create(compiled.plan());
            } catch (IllegalArgumentException conflict) {
                throw conflict(identity, "RG.MIRROR.PLAN_IDEMPOTENCY_CONFLICT",
                        "The plan id already identifies different immutable compile inputs.",
                        Map.of());
            } catch (RuntimeException unavailable) {
                throw unavailable(identity, "RG.MIRROR.PLAN_STORE_UNAVAILABLE",
                        "The isolated mirror plan store is unavailable.");
            }
        }
    }

    /**
     * Reads one verified plan in the exact authenticated enterprise scope.
     *
     * @param planId scoped plan id
     * @param identity authenticated enterprise identity and purpose
     * @return verified payload-free plan
     */
    @Transactional
    public MirrorPlan find(String planId, IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation = observations.start(
                MirrorOperationAuditEvent.Operation.PLAN_READ, identity, "", planId, "");
        MirrorPlan found;
        try {
            found = findForExecution(planId, identity);
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
        observation.succeeded("");
        return found;
    }

    /** Reads one plan for an already-observed execution operation without double-counting it. */
    MirrorPlan findForExecution(String planId, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireMirrorIdentity(identity);
        if (!IDENTIFIER.matcher(normalize(planId)).matches()) {
            throw badRequest(identity, "RG.MIRROR.PLAN_ID_INVALID",
                    "Mirror plan id is invalid.", Map.of());
        }
        return findStored(scope, normalize(planId), identity)
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.MIRROR.PLAN_NOT_FOUND",
                        "Mirror plan was not found in the authorized scope.",
                        identity.correlationId(), Map.of())));
    }

    /**
     * Rehydrates one persisted public plan into its exact in-process execution generation.
     *
     * <p>The public plan deliberately contains no fixture or replay values. This method resolves
     * those values through the same full-scope authorization checks used at plan creation,
     * fingerprints the current authoritative graph, reconstructs and verifies the sealed
     * capability closure, and recompiles the generation. Execution is allowed only when the
     * complete recompiled public plan equals the stored plan, not merely when one convenient
     * fingerprint happens to match.</p>
     *
     * @param plan verified persisted public plan
     * @param identity authenticated enterprise identity and mirror purpose
     * @return exact self-contained runtime generation; caller must close it after execution or
     *         failed terminal commit
     */
    public CompiledMirrorPlan materialize(
            MirrorPlan plan, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireMirrorIdentity(identity);
        if (plan == null || !scope.equals(plan.scope())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.MIRROR.PLAN_NOT_FOUND",
                    "Mirror plan was not found in the authorized scope.",
                    identity.correlationId(), Map.of()));
        }
        try {
            MirrorPlanIntegrity.verify(mapper, plan);
        } catch (IllegalArgumentException invalid) {
            throw unavailable(identity, "RG.MIRROR.PLAN_INTEGRITY_INVALID",
                    "The stored mirror plan failed immutable-content verification.");
        }

        CapabilityClosure closure;
        CapabilitySnapshot root;
        try {
            closure = new CapabilityClosure("", plan.rootCapability(),
                    plan.capabilityClosure(), plan.capabilityClosureFingerprint());
            CapabilityClosureIntegrity.verify(mapper, closure);
            root = closure.snapshots().stream()
                    .filter(snapshot -> plan.rootCapability().equals(capabilityRef(snapshot)))
                    .findFirst().orElseThrow();
        } catch (RuntimeException invalid) {
            throw unavailable(identity, "RG.MIRROR.CAPABILITY_CLOSURE_INTEGRITY_INVALID",
                    "The stored mirror capability closure failed immutable-content verification.");
        }
        if (root.source().sourceKind() != CapabilitySnapshot.SourceKind.GRAPH) {
            throw unavailable(identity, "RG.MIRROR.ROOT_GRAPH_SOURCE_INVALID",
                    "The mirror root no longer identifies an authoritative graph source.");
        }

        Graph graph = requireGraph(root.source().sourceRef(), identity);
        String graphFingerprint = GraphArtifactFingerprint.of(mapper, graph);
        if (!graphFingerprint.equals(root.source().sourceFingerprint())) {
            throw conflict(identity, "RG.MIRROR.RUNTIME_GRAPH_DRIFT",
                    "The authoritative graph differs from the sealed mirror generation.",
                    Map.of("currentGraphArtifactFingerprint", graphFingerprint));
        }
        StoredFixtureBundle storedFixture = requireFixture(plan.fixtureBundleRef(), scope, identity);
        ResolvedReplayPayloads replays = resolveReplayPayloads(storedFixture.bundle(), identity);
        ResolvedCorpusPayloads corpora = resolveCorpusPayloads(
                storedFixture.bundle(), scope, plan.policy(), plan.expiresAt(), identity);

        CompiledMirrorPlan compiled;
        boolean ownershipTransferred = false;
        try {
            compiled = compiler.compile(new MirrorPlanCompilationRequest(
                    plan.planId(), graph, graphFingerprint, closure, storedFixture.bundle(),
                    replays, corpora, plan.policy(), plan.scenarioPackRef(), plan.compiledAt(),
                    plan.expiresAt()));
            ownershipTransferred = true;
        } catch (MirrorPlanRejectedException rejected) {
            throw conflict(identity, rejected.code(),
                    "The sealed mirror generation can no longer be materialized exactly.",
                    rejected.diagnostics().isEmpty()
                            ? Map.of() : Map.of("diagnostics", rejected.diagnostics()));
        } catch (IllegalArgumentException invalid) {
            throw conflict(identity, "RG.MIRROR.RUNTIME_GENERATION_INVALID",
                    "The sealed mirror generation can no longer be materialized exactly.",
                    Map.of());
        } finally {
            if (!ownershipTransferred) {
                corpora.close();
            }
        }
        if (!compiled.plan().equals(plan)) {
            compiled.close();
            throw conflict(identity, "RG.MIRROR.RUNTIME_GENERATION_DRIFT",
                    "Recompiled runtime artifacts differ from the sealed mirror plan.", Map.of());
        }
        return compiled;
    }

    private Optional<MirrorPlan> findStored(
            CapabilitySnapshot.Scope scope,
            String planId,
            IntegrationRequestContext identity) {
        try {
            return plans.find(scope, normalize(planId));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.PLAN_STORE_UNAVAILABLE",
                    "The isolated mirror plan store is unavailable.");
        }
    }

    private StoredFixtureBundle requireFixture(
            MirrorArtifactRef ref,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        if (ref == null || !"FIXTURE_BUNDLE".equals(ref.kind())) {
            throw badRequest(identity, "RG.MIRROR.FIXTURE_REF_INVALID",
                    "An exact FIXTURE_BUNDLE reference is required.", Map.of());
        }
        MirrorFixtureScopeBinding binding;
        try {
            binding = fixtureScopes.find(scope, ref.id(), ref.revision())
                    .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.MIRROR.FIXTURE_NOT_FOUND",
                            "Fixture bundle was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.FIXTURE_SCOPE_STORE_UNAVAILABLE",
                    "The mirror fixture authorization index is unavailable.");
        }
        if (!binding.fixtureBundleRef().equals(ref)) {
            throw conflict(identity, "RG.MIRROR.FIXTURE_FINGERPRINT_CONFLICT",
                    "Fixture authorization differs from the requested immutable reference.", Map.of());
        }
        try {
            TestingArtifactScope fixtureScope = new TestingArtifactScope(
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region());
            StoredFixtureBundle stored = fixtures.find(fixtureScope, ref.id(), ref.revision())
                    .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.MIRROR.FIXTURE_NOT_FOUND",
                            "Fixture bundle was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
            stored = StoredFixtureBundleIntegrity.verifiedSnapshot(
                    mapper, stored, fixtureScope, ref.id(), ref.revision());
            if (!ref.fingerprint().equals(stored.fingerprint())) {
                throw conflict(identity, "RG.MIRROR.FIXTURE_FINGERPRINT_CONFLICT",
                        "Stored fixture differs from the requested immutable reference.", Map.of());
            }
            requireClassification(stored.bundle().classification(), identity);
            return stored;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (FixtureBundleIntegrityException corrupt) {
            throw unavailable(identity, "RG.MIRROR.FIXTURE_INTEGRITY_INVALID",
                    "The stored fixture failed immutable-content verification.");
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.FIXTURE_STORE_UNAVAILABLE",
                    "The isolated fixture registry is unavailable.");
        }
    }

    private ResolvedReplayPayloads resolveReplayPayloads(
            FixtureBundle fixture,
            IntegrationRequestContext identity) {
        boolean requiresReplay = fixture.rules().stream().filter(Objects::nonNull)
                .anyMatch(rule -> rule.behavior().kind() == FixtureRule.BehaviorKind.REPLAY);
        if (!requiresReplay) {
            return ResolvedReplayPayloads.empty();
        }
        if (replayPayloads == null) {
            throw unavailable(identity, "RG.MIRROR.REPLAY_RESOLVER_UNAVAILABLE",
                    "Governed replay payload resolution is unavailable.");
        }
        return replayPayloads.resolveForMirror(fixture, identity);
    }

    private ResolvedCorpusPayloads resolveCorpusPayloads(
            FixtureBundle fixture,
            CapabilitySnapshot.Scope scope,
            MirrorPlan.ExecutionPolicy policy,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        FixtureMirrorCorpusBindings bindings;
        try {
            bindings = FixtureMirrorCorpusBindings.from(fixture);
        } catch (IllegalArgumentException malformed) {
            throw badRequest(identity, "RG.MIRROR.CORPUS_BINDING_INVALID",
                    "Fixture mirror-corpus bindings are invalid.", Map.of());
        }
        if (!bindings.configured()) {
            return ResolvedCorpusPayloads.empty();
        }
        if (corpusServing == null) {
            throw unavailable(identity, "RG.MIRROR.CORPUS_SERVING_UNAVAILABLE",
                    "Governed corpus serving is unavailable.");
        }
        return corpusServing.resolve(
                fixture, scope, policy, requiredUntil, identity);
    }

    private Graph requireGraph(String graphName, IntegrationRequestContext identity) {
        try {
            return graphs.requireGraph(graphName);
        } catch (IllegalArgumentException absent) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.MIRROR.GRAPH_NOT_FOUND",
                    "Graph was not found in the authorized mirror runtime.",
                    identity.correlationId(), Map.of()));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.MIRROR.GRAPH_CATALOG_UNAVAILABLE",
                    "The authoritative graph catalog is unavailable.");
        }
    }

    private static MirrorPlan.ExecutionPolicy serverPolicy(
            MirrorPlanCreateRequest request,
            IntegrationRequestContext identity,
            CapabilitySnapshot.Scope scope) {
        return new MirrorPlan.ExecutionPolicy(AUTHORIZED_PURPOSE,
                false, false, false, false, request.certificationRequired(),
                MirrorPlan.UnmatchedResolution.ABSTAINED,
                request.maximumInvocations(), request.timeout(),
                classification(identity), List.of(scope.region()),
                List.of(CapabilitySnapshot.Lifecycle.ACTIVE,
                        CapabilitySnapshot.Lifecycle.DEPRECATED));
    }

    static CapabilitySnapshot.Scope requireMirrorIdentity(IntegrationRequestContext identity) {
        return requireMirrorIdentity(
                identity,
                Set.of(AUTHORIZED_PURPOSE),
                "RG.MIRROR.PURPOSE_REQUIRED",
                "Mirror plan operations require the verified MIRROR_REHEARSAL purpose.");
    }

    /**
     * Validates the shared non-production scope for rehearsal or governance evidence reads.
     */
    static CapabilitySnapshot.Scope requireMirrorReadIdentity(
            IntegrationRequestContext identity) {
        return requireMirrorIdentity(
                identity,
                Set.of(
                        AUTHORIZED_PURPOSE,
                        "GOVERNANCE_EVIDENCE_INGESTION"),
                "RG.MIRROR.READ_PURPOSE_REQUIRED",
                "Mirror evidence reads require a rehearsal or governance-ingestion purpose.");
    }

    private static CapabilitySnapshot.Scope requireMirrorIdentity(
            IntegrationRequestContext identity,
            Set<String> allowedPurposes,
            String purposeCode,
            String purposeTitle) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!allowedPurposes.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    purposeCode,
                    purposeTitle,
                    identity.correlationId(), Map.of()));
        }
        if (identity.projectId().isBlank() || identity.region().isBlank()) {
            throw badRequest(identity, "RG.MIRROR.SCOPE_INCOMPLETE",
                    "Mirror operations require project and region scope coordinates.", Map.of());
        }
        if (!("test".equalsIgnoreCase(identity.environmentId())
                || "staging".equalsIgnoreCase(identity.environmentId()))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.MIRROR.ENVIRONMENT_FORBIDDEN",
                    "Mirror operations are restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
        return new CapabilitySnapshot.Scope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static void validateRequest(
            MirrorPlanCreateRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !MirrorPlanCreateRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || !IDENTIFIER.matcher(request.planId()).matches()
                || !IDENTIFIER.matcher(request.graphName()).matches()
                || !FINGERPRINT.matcher(request.expectedGraphArtifactFingerprint()).matches()
                || request.capabilityClosure() == null
                || request.fixtureBundleRef() == null
                || request.maximumInvocations() < 1
                || request.maximumInvocations() > MAXIMUM_INVOCATIONS
                || request.timeout() == null || request.timeout().isZero()
                || request.timeout().isNegative()
                || request.timeout().compareTo(MAXIMUM_TIMEOUT) > 0
                || request.expiresAt() == null) {
            throw badRequest(identity, "RG.MIRROR.PLAN_REQUEST_INVALID",
                    "A versioned, bounded, content-addressed mirror plan request is required.",
                    Map.of("maximumInvocations", MAXIMUM_INVOCATIONS,
                            "maximumTimeoutSeconds", MAXIMUM_TIMEOUT.toSeconds(),
                            "maximumPlanLifetimeSeconds", MAXIMUM_PLAN_LIFETIME.toSeconds()));
        }
    }

    private static void validateTemporalPolicy(
            Instant expiresAt,
            Instant compiledAt,
            Instant now,
            IntegrationRequestContext identity) {
        if (!expiresAt.isAfter(now) || !expiresAt.isAfter(compiledAt)
                || expiresAt.isAfter(compiledAt.plus(MAXIMUM_PLAN_LIFETIME))) {
            throw badRequest(identity, "RG.MIRROR.PLAN_EXPIRY_INVALID",
                    "Mirror plan expiry must be future-dated and inside the server lifetime bound.",
                    Map.of("maximumPlanLifetimeSeconds", MAXIMUM_PLAN_LIFETIME.toSeconds()));
        }
    }

    private static void requireClosureScope(
            CapabilityClosure closure,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        if (closure.snapshots().stream().anyMatch(snapshot -> !scope.equals(snapshot.scope()))) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.MIRROR.CAPABILITY_CLOSURE_NOT_FOUND",
                    "Capability closure was not found in the authorized scope.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireClassification(
            String classification,
            IntegrationRequestContext identity) {
        String required = normalize(classification).toUpperCase(java.util.Locale.ROOT);
        if (!identity.hasClearanceAtLeast(required)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.MIRROR.FIXTURE_CLEARANCE_REQUIRED",
                    "Workload clearance cannot use the governed fixture bundle.",
                    identity.correlationId(), Map.of("classification", required)));
        }
    }

    private static CapabilityContract.DataClassification classification(
            IntegrationRequestContext identity) {
        try {
            return CapabilityContract.DataClassification.valueOf(
                    normalize(identity.clearance()).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.MIRROR.CLEARANCE_INVALID",
                    "Workload clearance is not recognized by mirror policy.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity == null ? "" : identity.correlationId(), details));
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
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static MirrorArtifactRef capabilityRef(CapabilitySnapshot snapshot) {
        return new MirrorArtifactRef("CAPABILITY", snapshot.capabilityId(), snapshot.revision(),
                snapshot.fingerprint());
    }
}
