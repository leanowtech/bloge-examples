package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.spi.ExecutionServiceKind;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.core.spi.FeatureFlagProvider;
import com.leanowtech.bloge.core.spi.FunctionCallSite;
import com.leanowtech.bloge.core.spi.FunctionInvocationContext;
import com.leanowtech.bloge.core.spi.IdGenerator;
import com.leanowtech.bloge.core.spi.IdentityProvider;
import com.leanowtech.bloge.core.spi.RandomSource;
import com.leanowtech.bloge.core.spi.SecretProvider;
import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.core.spi.TimeSource;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureExecutionServices;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Frozen test-run execution services and their payload-free usage audit.
 *
 * <p>The instance is created during plan compilation and is carried by
 * {@link com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl}; runtime code
 * never rebuilds it from a mutable fixture. A configured seed drives independent SHA-256 streams
 * for random values and identifiers. Its provider-state checkpoint is bound to one effective plan,
 * contains no seed or raw scope, and can be restored only against the same binding set. Identity
 * and feature flags may be supplied by strict fixture maps. Secrets are accepted only from an
 * independently verified, run-scoped external-authority result; missing entries fail closed.</p>
 */
public final class GovernedExecutionServices {

    private static final String BINDING_SCHEMA = "bloge.executionServiceBinding.v1";
    private static final String USAGE_SCHEMA = "bloge.executionServiceUsage.v1";
    private static final int MAX_PROVIDER_SCOPES = 10_000;

    private final ObjectMapper objectMapper;
    private final ExecutionServices services;
    private final List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings;
    private final UsageTracker usageTracker;
    private final AdvancingLogicalTimeSource logicalTime;
    private final ScopedDigestSequence randomSequence;
    private final ScopedDigestSequence idSequence;
    private final StateCoordinator stateCoordinator;
    private final String bindingSetFingerprint;
    private volatile String planFingerprint = "";

    private GovernedExecutionServices(ObjectMapper objectMapper, ExecutionServices services,
                                      List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings,
                                      UsageTracker usageTracker,
                                      AdvancingLogicalTimeSource logicalTime,
                                      ScopedDigestSequence randomSequence,
                                      ScopedDigestSequence idSequence,
                                      StateCoordinator stateCoordinator) {
        this.objectMapper = objectMapper;
        this.services = services;
        this.bindings = List.copyOf(bindings);
        this.usageTracker = usageTracker;
        this.logicalTime = logicalTime;
        this.randomSequence = randomSequence;
        this.idSequence = idSequence;
        this.stateCoordinator = stateCoordinator;
        this.bindingSetFingerprint = bindingSetFingerprint(objectMapper, bindings);
    }

    /**
     * Freezes service providers and plan projections for one compiled run.
     *
     * @param objectMapper canonical protocol mapper
     * @param fixtureBundle already validated immutable fixture
     * @param inventory frozen invocation inventory
     * @return one stateful run-scoped service set
     */
    public static GovernedExecutionServices prepare(ObjectMapper objectMapper,
                                                     FixtureBundle fixtureBundle,
                                                     InvocationInventory inventory) {
        return prepare(objectMapper, fixtureBundle, inventory, ResolvedTestSecrets.empty());
    }

    /**
     * Freezes providers with an independently verified run-scoped test-secret closure.
     *
     * @param objectMapper canonical protocol mapper
     * @param fixtureBundle already validated immutable fixture
     * @param inventory frozen invocation inventory
     * @param testSecrets run-scoped values resolved immediately before this execution
     * @return one stateful run-scoped service set
     */
    public static GovernedExecutionServices prepare(ObjectMapper objectMapper,
                                                     FixtureBundle fixtureBundle,
                                                     InvocationInventory inventory,
                                                     ResolvedTestSecrets testSecrets) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(fixtureBundle, "fixtureBundle");
        Objects.requireNonNull(inventory, "inventory");
        ResolvedTestSecrets resolvedSecrets = testSecrets == null
                ? ResolvedTestSecrets.empty() : testSecrets;
        FixtureExecutionServices fixtureServices = FixtureExecutionServices.from(fixtureBundle);
        StateCoordinator stateCoordinator = new StateCoordinator();
        UsageTracker tracker = new UsageTracker();
        AdvancingLogicalTimeSource logicalTime = fixtureBundle.logicalClock() == null
                ? null : new AdvancingLogicalTimeSource(fixtureBundle.logicalClock());
        TimeSource timeSource = guardedTimeSource(
                logicalTime == null ? SystemTimeSource.INSTANCE : logicalTime,
                tracker, stateCoordinator);
        Long seed = fixtureBundle.randomSeed();
        ScopedDigestSequence randomSequence = seed == null
                ? null : new ScopedDigestSequence(seed, "random");
        ScopedDigestSequence idSequence = seed == null
                ? null : new ScopedDigestSequence(seed, "uuid");

        RandomSource randomSource = scope -> {
            return stateCoordinator.mutate(() -> {
                tracker.recordProvider(ExecutionServiceKind.RANDOM, scope, true);
                return randomSequence == null
                        ? RandomSource.SYSTEM.nextLong(scope) : randomSequence.nextLong(scope);
            });
        };
        IdGenerator idGenerator = scope -> {
            return stateCoordinator.mutate(() -> {
                tracker.recordProvider(ExecutionServiceKind.UUID, scope,
                        !isInfrastructureIdScope(scope));
                return idSequence == null
                        ? IdGenerator.UUID_V4.nextId(scope) : idSequence.nextUuid(scope);
            });
        };
        IdentityProvider identityProvider = attribute -> stateCoordinator.mutate(() -> {
            tracker.recordProvider(ExecutionServiceKind.IDENTITY, attribute, true);
            if (!fixtureServices.identityAttributes().containsKey(attribute)) {
                throw new IllegalStateException(
                        "No governed identity fixture value is configured for the requested attribute");
            }
            return fixtureServices.identityAttributes().get(attribute);
        });
        FeatureFlagProvider featureFlags = flag -> stateCoordinator.mutate(() -> {
            tracker.recordProvider(ExecutionServiceKind.FEATURE_FLAG, flag, true);
            Boolean enabled = fixtureServices.featureFlags().get(flag);
            if (enabled == null) {
                throw new IllegalStateException(
                        "No governed feature-flag fixture decision is configured for the requested flag");
            }
            return enabled;
        });
        SecretProvider secrets = name -> stateCoordinator.mutate(() -> {
            tracker.recordProvider(ExecutionServiceKind.SECRET, name, true);
            return resolvedSecrets.isEmpty()
                    ? SecretProvider.NONE.resolve(name) : resolvedSecrets.resolve(name);
        });

        ExecutionServices services = ExecutionServices.builder()
                .timeSource(timeSource)
                .randomSource(randomSource)
                .idGenerator(idGenerator)
                .identityProvider(identityProvider)
                .featureFlagProvider(featureFlags)
                .secretProvider(secrets)
                .expressionFunctionResolver((site, function) -> audited(
                        site, function, tracker, stateCoordinator))
                .build();
        Map<ExecutionServiceKind, List<String>> consumers = declaredConsumers(inventory);
        List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings = Arrays.stream(
                ExecutionServiceKind.values())
                .map(kind -> binding(objectMapper, kind, fixtureBundle, fixtureServices,
                        resolvedSecrets, consumers.getOrDefault(kind, List.of())))
                .toList();
        return new GovernedExecutionServices(objectMapper, services, bindings, tracker, logicalTime,
                randomSequence, idSequence, stateCoordinator);
    }

    /**
     * Restores one exact provider-state checkpoint against freshly frozen configuration.
     *
     * @param objectMapper canonical protocol mapper
     * @param fixtureBundle immutable fixture used by the original plan
     * @param inventory frozen invocation inventory
     * @param planFingerprint recomputed effective-plan fingerprint
     * @param snapshot persisted provider-state checkpoint
     * @return run-scoped services continuing at the checkpointed cursors
     * @throws IllegalArgumentException when state integrity, plan, binding or clock checks fail
     */
    public static GovernedExecutionServices restore(ObjectMapper objectMapper,
                                                     FixtureBundle fixtureBundle,
                                                     InvocationInventory inventory,
                                                     String planFingerprint,
                                                     ExecutionServiceStateSnapshot snapshot) {
        return restore(objectMapper, fixtureBundle, inventory, planFingerprint, snapshot,
                ResolvedTestSecrets.empty());
    }

    /**
     * Restores provider cursors only after fresh test-secret re-authorization.
     *
     * @param objectMapper canonical protocol mapper
     * @param fixtureBundle exact immutable fixture
     * @param inventory freshly rebuilt invocation inventory
     * @param planFingerprint freshly rebuilt effective-plan fingerprint
     * @param snapshot payload-free provider-state snapshot
     * @param testSecrets freshly resolved run-scoped test-secret closure
     * @return restored execution services
     */
    public static GovernedExecutionServices restore(ObjectMapper objectMapper,
                                                     FixtureBundle fixtureBundle,
                                                     InvocationInventory inventory,
                                                     String planFingerprint,
                                                     ExecutionServiceStateSnapshot snapshot,
                                                     ResolvedTestSecrets testSecrets) {
        GovernedExecutionServices restored = prepare(
                objectMapper, fixtureBundle, inventory, testSecrets);
        restored.restoreState(planFingerprint, Objects.requireNonNull(snapshot, "snapshot"));
        return restored;
    }

    /** @return exact service object that must be passed to BLOGE {@code ExecutionOptions} */
    public ExecutionServices services() {
        return services;
    }

    /** @return payload-free plan bindings in stable service order */
    public List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings() {
        return bindings;
    }

    /** @return immutable logical-clock observation, or {@code null} when system time is active */
    public LogicalTimeObservation logicalTimeObservation() {
        return stateCoordinator.observe(() -> {
            if (logicalTime == null) {
                return null;
            }
            java.time.Instant current = logicalTime.now();
            return new LogicalTimeObservation(logicalTime.origin(), current,
                    java.time.Duration.between(logicalTime.origin(), current));
        });
    }

    /**
     * Binds fresh provider state to the exact plan that owns all future checkpoints.
     *
     * @param fingerprint canonical effective-plan fingerprint
     * @return this run-scoped service set
     */
    public GovernedExecutionServices bindToPlan(String fingerprint) {
        String value = canonicalFingerprint(fingerprint, "plan fingerprint");
        stateCoordinator.checkpoint(() -> {
            if (!planFingerprint.isBlank() && !planFingerprint.equals(value)) {
                throw new IllegalStateException("Execution services are already bound to another plan");
            }
            planFingerprint = value;
            return null;
        });
        return this;
    }

    /**
     * Returns stable payload-free usage facts. Raw provider scopes are hashed before exposure.
     *
     * @return ordered usages for services that were actually invoked
     */
    public List<ExecutionServiceUsage> usageSnapshot() {
        return stateCoordinator.observe(this::usageSnapshotUnsafe);
    }

    private List<ExecutionServiceUsage> usageSnapshotUnsafe() {
        return projectUsages(usageTracker.stateSnapshot());
    }

    private List<ExecutionServiceUsage> projectUsages(
            List<ExecutionServiceStateSnapshot.UsageState> states) {
        Map<String, String> modes = bindings.stream().collect(java.util.stream.Collectors.toMap(
                EffectiveExecutionPlan.ExecutionServiceBinding::service,
                EffectiveExecutionPlan.ExecutionServiceBinding::mode));
        return states.stream().map(usage -> new ExecutionServiceUsage(
                USAGE_SCHEMA, usage.service(), modes.getOrDefault(usage.service(), "UNKNOWN"),
                usage.providerCalls(), usage.semanticProviderCalls(), usage.functionCalls(),
                usage.functionCallSites(), usage.providerScopeFingerprints())).toList();
    }

    /**
     * Captures a content-addressed provider-state checkpoint while excluding concurrent mutations.
     *
     * @return payload-free state bound to the effective plan
     */
    public ExecutionServiceStateSnapshot snapshotState() {
        return stateCoordinator.checkpoint(() -> {
            if (planFingerprint.isBlank()) {
                throw new IllegalStateException(
                        "Execution services must be bound to an effective plan before checkpointing");
            }
            Map<String, Long> randomCursors = randomSequence == null
                    ? Map.of() : randomSequence.snapshot();
            Map<String, Long> uuidCursors = idSequence == null
                    ? Map.of() : idSequence.snapshot();
            List<ExecutionServiceStateSnapshot.UsageState> usages = usageTracker.stateSnapshot();
            List<String> restoreGaps = restoreGaps(usageSnapshotUnsafe());
            java.time.Instant currentLogicalTime = logicalTime == null ? null : logicalTime.now();
            Map<String, Object> material = stateMaterial(planFingerprint, bindingSetFingerprint,
                    currentLogicalTime, randomCursors, uuidCursors, usages, restoreGaps);
            String fingerprint = ProtocolFingerprint.of(objectMapper, material);
            return new ExecutionServiceStateSnapshot(ExecutionServiceStateSnapshot.SCHEMA_VERSION,
                    planFingerprint, bindingSetFingerprint, currentLogicalTime,
                    randomCursors, uuidCursors,
                    usages, restoreGaps.isEmpty(), restoreGaps, fingerprint);
        });
    }

    /** @return canonical digest of the complete current provider-state checkpoint */
    public String stateFingerprint() {
        return snapshotState().snapshotFingerprint();
    }

    /**
     * Returns all service-related reasons this completed run cannot be certifiable.
     *
     * <p>Declared operator consumers are checked before execution. Runtime function call sites and
     * direct semantic provider calls are checked after execution, which also catches DSL built-ins
     * absent from the graph's operator inventory.</p>
     *
     * @return ordered, duplicate-free certification gaps
     */
    public List<String> certificationGaps() {
        return certificationGaps(usageSnapshot());
    }

    private List<String> certificationGaps(List<ExecutionServiceUsage> usages) {
        Map<String, EffectiveExecutionPlan.ExecutionServiceBinding> byService = bindings.stream()
                .collect(java.util.stream.Collectors.toMap(
                        EffectiveExecutionPlan.ExecutionServiceBinding::service, binding -> binding));
        Set<String> gaps = new java.util.LinkedHashSet<>();
        bindings.stream().filter(EffectiveExecutionPlan.ExecutionServiceBinding::required)
                .filter(binding -> !binding.certificationEligibleWhenUsed())
                .flatMap(binding -> binding.certificationGaps().stream())
                .forEach(gaps::add);
        for (ExecutionServiceUsage usage : usages) {
            if (usage.functionCalls() == 0 && usage.semanticProviderCalls() == 0) {
                continue;
            }
            EffectiveExecutionPlan.ExecutionServiceBinding binding = byService.get(usage.service());
            if (binding != null && !binding.certificationEligibleWhenUsed()) {
                gaps.addAll(binding.certificationGaps());
            }
        }
        return List.copyOf(gaps);
    }

    private List<String> restoreGaps(List<ExecutionServiceUsage> usages) {
        Map<String, EffectiveExecutionPlan.ExecutionServiceBinding> byService = bindings.stream()
                .collect(java.util.stream.Collectors.toMap(
                        EffectiveExecutionPlan.ExecutionServiceBinding::service, binding -> binding));
        Set<String> gaps = new java.util.LinkedHashSet<>();
        bindings.stream().filter(EffectiveExecutionPlan.ExecutionServiceBinding::required)
                .filter(binding -> !binding.deterministic())
                .flatMap(binding -> binding.certificationGaps().stream())
                .forEach(gaps::add);
        for (ExecutionServiceUsage usage : usages) {
            if (usage.semanticProviderCalls() == 0 && usage.functionCalls() == 0) {
                continue;
            }
            EffectiveExecutionPlan.ExecutionServiceBinding binding = byService.get(usage.service());
            if (binding != null && !binding.deterministic()) {
                gaps.addAll(binding.certificationGaps());
            }
        }
        return List.copyOf(gaps);
    }

    private static ExpressionFunction audited(FunctionCallSite site, ExpressionFunction function,
                                               UsageTracker tracker,
                                               StateCoordinator stateCoordinator) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(function, "function");
        return new ExpressionFunction() {
            @Override
            public String name() {
                return function.name();
            }

            @Override
            public Object apply(Object... args) {
                return stateCoordinator.mutate(() -> {
                    tracker.recordFunction(site, function.requiredExecutionServices());
                    return function.apply(args);
                });
            }

            @Override
            public Object apply(FunctionInvocationContext context, Object... args) {
                return stateCoordinator.mutate(() -> {
                    tracker.recordFunction(site, function.requiredExecutionServices());
                    return function.apply(context, args);
                });
            }

            @Override
            public String returnType(String... argTypes) {
                return function.returnType(argTypes);
            }

            @Override
            public boolean isPure() {
                return function.isPure();
            }

            @Override
            public Set<ExecutionServiceKind> requiredExecutionServices() {
                return function.requiredExecutionServices();
            }
        };
    }

    private void restoreState(String expectedPlanFingerprint,
                              ExecutionServiceStateSnapshot snapshot) {
        String expectedPlan = canonicalFingerprint(expectedPlanFingerprint, "plan fingerprint");
        stateCoordinator.checkpoint(() -> {
            String actualFingerprint = ProtocolFingerprint.of(objectMapper,
                    snapshot.fingerprintMaterial());
            if (!actualFingerprint.equals(snapshot.snapshotFingerprint())) {
                throw new IllegalArgumentException(
                        "Execution-service state snapshot fingerprint is invalid");
            }
            if (!expectedPlan.equals(snapshot.planFingerprint())) {
                throw new IllegalArgumentException(
                        "Execution-service state snapshot belongs to another plan");
            }
            if (!bindingSetFingerprint.equals(snapshot.bindingSetFingerprint())) {
                throw new IllegalArgumentException(
                        "Execution-service state snapshot binding set has drifted");
            }
            List<String> expectedGaps = restoreGaps(projectUsages(snapshot.usages()));
            if (!expectedGaps.equals(snapshot.restoreGaps())
                    || snapshot.restorable() != expectedGaps.isEmpty()) {
                throw new IllegalArgumentException(
                        "Execution-service state snapshot restore policy is invalid");
            }
            if (!snapshot.restorable()) {
                throw new IllegalArgumentException(
                        "Execution-service state snapshot contains non-restorable providers");
            }
            if (randomSequence != null) {
                validateSequenceState("RANDOM", snapshot.randomScopeCursors(), snapshot.usages());
            }
            if (idSequence != null) {
                validateSequenceState("UUID", snapshot.uuidScopeCursors(), snapshot.usages());
            }
            restoreLogicalTime(snapshot.logicalTime());
            restoreSequence("RANDOM", randomSequence, snapshot.randomScopeCursors());
            restoreSequence("UUID", idSequence, snapshot.uuidScopeCursors());
            usageTracker.restore(snapshot.usages());
            planFingerprint = expectedPlan;
            return null;
        });
    }

    private void restoreLogicalTime(java.time.Instant restored) {
        if (logicalTime == null) {
            if (restored != null) {
                throw new IllegalArgumentException(
                        "Execution-service state has logical time for a wall-clock binding");
            }
            return;
        }
        if (restored == null) {
            throw new IllegalArgumentException(
                    "Execution-service state is missing its logical clock");
        }
        logicalTime.restore(restored);
    }

    private static void restoreSequence(String service, ScopedDigestSequence sequence,
                                        Map<String, Long> cursors) {
        if (sequence == null) {
            if (!cursors.isEmpty()) {
                throw new IllegalArgumentException(
                        service + " cursors cannot be restored without a deterministic binding");
            }
            return;
        }
        sequence.restore(cursors);
    }

    private static void validateSequenceState(
            String service, Map<String, Long> cursors,
            List<ExecutionServiceStateSnapshot.UsageState> usages) {
        ExecutionServiceStateSnapshot.UsageState usage = usages.stream()
                .filter(state -> state.service().equals(service))
                .findFirst()
                .orElse(null);
        long cursorCalls;
        try {
            cursorCalls = cursors.values().stream().reduce(0L, Math::addExact);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    service + " execution-service cursor count overflow", overflow);
        }
        long providerCalls = usage == null ? 0 : usage.providerCalls();
        Set<String> observedScopes = usage == null
                ? Set.of() : Set.copyOf(usage.providerScopeFingerprints());
        if (cursorCalls != providerCalls || !cursors.keySet().equals(observedScopes)) {
            throw new IllegalArgumentException(
                    service + " execution-service cursors do not match cumulative usage");
        }
    }

    private static TimeSource guardedTimeSource(TimeSource delegate, UsageTracker tracker,
                                                StateCoordinator stateCoordinator) {
        return new TimeSource() {
            @Override
            public java.time.Instant now() {
                return stateCoordinator.mutate(() -> {
                    tracker.recordProvider(ExecutionServiceKind.TIME, "now", false);
                    return delegate.now();
                });
            }

            @Override
            public void sleep(java.time.Duration duration) throws InterruptedException {
                stateCoordinator.mutateInterruptibly(() -> {
                    tracker.recordProvider(ExecutionServiceKind.TIME, "sleep", false);
                    delegate.sleep(duration);
                });
            }
        };
    }

    private static String bindingSetFingerprint(ObjectMapper mapper,
                                                List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings) {
        return ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", "bloge.executionServiceBindingSet.v1",
                "bindings", bindings));
    }

    private static Map<String, Object> stateMaterial(
            String planFingerprint, String bindingSetFingerprint, java.time.Instant logicalTime,
            Map<String, Long> randomScopeCursors, Map<String, Long> uuidScopeCursors,
            List<ExecutionServiceStateSnapshot.UsageState> usages, List<String> restoreGaps) {
        return Map.of(
                "schemaVersion", ExecutionServiceStateSnapshot.SCHEMA_VERSION,
                "planFingerprint", planFingerprint,
                "bindingSetFingerprint", bindingSetFingerprint,
                "logicalTime", logicalTime == null ? "" : logicalTime.toString(),
                "randomScopeCursors", randomScopeCursors,
                "uuidScopeCursors", uuidScopeCursors,
                "usages", usages,
                "restorable", restoreGaps.isEmpty(),
                "restoreGaps", restoreGaps);
    }

    private static String canonicalFingerprint(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static Map<ExecutionServiceKind, List<String>> declaredConsumers(
            InvocationInventory inventory) {
        Map<ExecutionServiceKind, Set<String>> mutable = new EnumMap<>(ExecutionServiceKind.class);
        for (InvocationInventory.Entry entry : inventory.entries()) {
            if (!(entry.frozenOperator() instanceof OperatorComposabilityManifestProvider provider)) {
                continue;
            }
            try {
                OperatorComposabilityManifest manifest = provider.operatorComposabilityManifest();
                if (manifest == null) {
                    continue;
                }
                for (OperatorComposabilityManifest.ExecutionService service
                        : manifest.executionServices()) {
                    ExecutionServiceKind kind = ExecutionServiceKind.valueOf(service.name());
                    mutable.computeIfAbsent(kind, ignored -> new ConcurrentSkipListSet<>())
                            .add(entry.site().invocationSiteId());
                }
            } catch (RuntimeException ignored) {
                // Target snapshot owns manifest validity; an invalid provider cannot gain certification here.
            }
        }
        Map<ExecutionServiceKind, List<String>> result = new EnumMap<>(ExecutionServiceKind.class);
        mutable.forEach((kind, values) -> result.put(kind, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static EffectiveExecutionPlan.ExecutionServiceBinding binding(
            ObjectMapper mapper, ExecutionServiceKind kind, FixtureBundle bundle,
            FixtureExecutionServices fixtureServices,
            ResolvedTestSecrets testSecrets,
            List<String> consumers) {
        String mode;
        boolean available;
        boolean deterministic;
        Object configuration;
        List<String> gaps = new ArrayList<>();
        switch (kind) {
            case TIME -> {
                boolean configured = bundle.logicalClock() != null;
                mode = configured ? "LOGICAL_ADVANCING" : "SYSTEM_WALL_CLOCK";
                available = true;
                deterministic = configured;
                configuration = configured ? bundle.logicalClock().toString() : "SYSTEM";
                if (!configured) gaps.add("TIME requires fixtureBundle.logicalClock for certification.");
            }
            case RANDOM -> {
                boolean configured = bundle.randomSeed() != null;
                mode = configured ? "SEEDED_SHA256_SEQUENCE" : "SYSTEM_RANDOM";
                available = true;
                deterministic = configured;
                configuration = configured ? bundle.randomSeed() : "SYSTEM";
                if (!configured) gaps.add("RANDOM requires fixtureBundle.randomSeed for certification.");
            }
            case UUID -> {
                boolean configured = bundle.randomSeed() != null;
                mode = configured ? "SEEDED_SHA256_UUID" : "SYSTEM_UUID_V4";
                available = true;
                deterministic = configured;
                configuration = configured ? bundle.randomSeed() : "SYSTEM";
                if (!configured) gaps.add("UUID requires fixtureBundle.randomSeed for certification.");
            }
            case IDENTITY, FEATURE_FLAG -> {
                boolean configured = fixtureServices.configures(kind);
                mode = configured ? "FIXTURE_MAP" : "FAIL_CLOSED";
                available = configured;
                deterministic = true;
                configuration = configured ? Map.of("fixtureControlFingerprint",
                        fixtureControlFingerprint(mapper, kind, fixtureServices)) : "UNCONFIGURED";
                if (!configured) {
                    gaps.add(kind.name() + " has no governed test authority configured.");
                }
            }
            case SECRET -> {
                boolean configured = fixtureServices.configures(kind) && !testSecrets.isEmpty();
                mode = configured ? "EXTERNAL_TEST_AUTHORITY" : "FAIL_CLOSED";
                available = configured;
                deterministic = true;
                configuration = configured ? Map.of(
                        "authorityConfigurationFingerprint",
                        testSecrets.configurationFingerprint(mapper),
                        "dependencyFingerprints", testSecrets.planDependencies(mapper))
                        : "UNCONFIGURED";
                if (!configured) {
                    gaps.add(kind.name() + " has no governed test authority configured.");
                }
            }
            default -> throw new IllegalStateException("Unhandled execution service: " + kind);
        }
        String fingerprint = ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", BINDING_SCHEMA,
                "service", kind.name(),
                "mode", mode,
                "configuration", configuration));
        return new EffectiveExecutionPlan.ExecutionServiceBinding(kind.name(), mode,
                available, deterministic, fingerprint, consumers, gaps);
    }

    private static String fixtureControlFingerprint(ObjectMapper mapper, ExecutionServiceKind kind,
                                                    FixtureExecutionServices fixtureServices) {
        return ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", FixtureExecutionServices.SCHEMA_VERSION,
                "service", kind.name(),
                "values", fixtureServices.configuration(kind)));
    }

    private static boolean isInfrastructureIdScope(String scope) {
        String value = scope == null ? "" : scope;
        return value.startsWith("graph:")
                || value.startsWith("nested-graph:")
                || value.startsWith("streaming-graph:")
                || value.startsWith("streaming-output-graph:");
    }

    private static String digest(String value) {
        byte[] hash = sha256((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        return "sha256:" + java.util.HexFormat.of().formatHex(hash);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Payload-free usage fact included in test evidence metadata and semantic fingerprints. */
    public record ExecutionServiceUsage(
            String schemaVersion,
            String service,
            String mode,
            long providerCalls,
            long semanticProviderCalls,
            long functionCalls,
            List<String> functionCallSites,
            List<String> providerScopeFingerprints
    ) {
        /** Creates immutable ordered call-site and scope-fingerprint lists. */
        public ExecutionServiceUsage {
            functionCallSites = functionCallSites == null ? List.of() : List.copyOf(functionCallSites);
            providerScopeFingerprints = providerScopeFingerprints == null
                    ? List.of() : List.copyOf(providerScopeFingerprints);
        }
    }

    /** Immutable evidence-facing observation of the governed logical clock. */
    public record LogicalTimeObservation(
            java.time.Instant origin,
            java.time.Instant current,
            java.time.Duration elapsed
    ) {
        /** Rejects incomplete or internally inconsistent observations. */
        public LogicalTimeObservation {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(elapsed, "elapsed");
            if (current.isBefore(origin) || !elapsed.equals(java.time.Duration.between(origin, current))) {
                throw new IllegalArgumentException("Logical-time observation is inconsistent");
            }
        }
    }

    private static final class UsageTracker {
        private final Map<ExecutionServiceKind, UsageCounter> counters =
                new EnumMap<>(ExecutionServiceKind.class);

        private UsageTracker() {
            for (ExecutionServiceKind kind : ExecutionServiceKind.values()) {
                counters.put(kind, new UsageCounter());
            }
        }

        private void recordProvider(ExecutionServiceKind kind, String scope, boolean semantic) {
            UsageCounter counter = counters.get(kind);
            addBounded(counter.providerScopeFingerprints, digest(scope), "provider scopes");
            counter.providerCalls.increment();
            if (semantic) counter.semanticProviderCalls.increment();
        }

        private void recordFunction(FunctionCallSite site, Set<ExecutionServiceKind> kinds) {
            for (ExecutionServiceKind kind : kinds) {
                UsageCounter counter = counters.get(kind);
                addBounded(counter.functionCallSites, site.structuralId(), "function call sites");
                counter.functionCalls.increment();
            }
        }

        private static void addBounded(Set<String> values, String value, String label) {
            synchronized (values) {
                if (!values.contains(value) && values.size() >= MAX_PROVIDER_SCOPES) {
                    throw new IllegalStateException(
                            "Execution-service " + label + " exceed " + MAX_PROVIDER_SCOPES);
                }
                values.add(value);
            }
        }

        private List<ExecutionServiceStateSnapshot.UsageState> stateSnapshot() {
            return counters.entrySet().stream()
                    .filter(entry -> entry.getValue().used())
                    .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                    .map(entry -> entry.getValue().stateSnapshot(entry.getKey()))
                    .toList();
        }

        private void restore(List<ExecutionServiceStateSnapshot.UsageState> states) {
            counters.values().forEach(UsageCounter::clear);
            for (ExecutionServiceStateSnapshot.UsageState state : states) {
                ExecutionServiceKind kind;
                try {
                    kind = ExecutionServiceKind.valueOf(state.service());
                } catch (IllegalArgumentException unsupported) {
                    throw new IllegalArgumentException(
                            "Execution-service state contains an unsupported usage service", unsupported);
                }
                counters.get(kind).restore(state);
            }
        }
    }

    private static final class UsageCounter {
        private final LongAdder providerCalls = new LongAdder();
        private final LongAdder semanticProviderCalls = new LongAdder();
        private final LongAdder functionCalls = new LongAdder();
        private final Set<String> functionCallSites = new ConcurrentSkipListSet<>();
        private final Set<String> providerScopeFingerprints = new ConcurrentSkipListSet<>();

        private boolean used() {
            return providerCalls.sum() > 0 || functionCalls.sum() > 0;
        }

        private ExecutionServiceStateSnapshot.UsageState stateSnapshot(ExecutionServiceKind kind) {
            return new ExecutionServiceStateSnapshot.UsageState(kind.name(), providerCalls.sum(),
                    semanticProviderCalls.sum(), functionCalls.sum(), List.copyOf(functionCallSites),
                    List.copyOf(providerScopeFingerprints));
        }

        private void clear() {
            providerCalls.reset();
            semanticProviderCalls.reset();
            functionCalls.reset();
            functionCallSites.clear();
            providerScopeFingerprints.clear();
        }

        private void restore(ExecutionServiceStateSnapshot.UsageState state) {
            clear();
            providerCalls.add(state.providerCalls());
            semanticProviderCalls.add(state.semanticProviderCalls());
            functionCalls.add(state.functionCalls());
            functionCallSites.addAll(state.functionCallSites());
            providerScopeFingerprints.addAll(state.providerScopeFingerprints());
        }
    }

    private static final class ScopedDigestSequence {
        private final byte[] seed;
        private final byte[] domain;
        private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

        private ScopedDigestSequence(long seed, String domain) {
            this.seed = ByteBuffer.allocate(Long.BYTES).putLong(seed).array();
            this.domain = domain.getBytes(StandardCharsets.UTF_8);
        }

        private long nextLong(String scope) {
            return ByteBuffer.wrap(next(scope)).getLong();
        }

        private String nextUuid(String scope) {
            byte[] value = Arrays.copyOf(next(scope), 16);
            value[6] = (byte) ((value[6] & 0x0f) | 0x40);
            value[8] = (byte) ((value[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(value);
            return new UUID(bytes.getLong(), bytes.getLong()).toString();
        }

        private byte[] next(String scope) {
            String normalized = scope == null ? "" : scope;
            String scopeFingerprint = digest(normalized);
            AtomicLong cursor = counters.get(scopeFingerprint);
            if (cursor == null) {
                AtomicLong candidate = new AtomicLong();
                AtomicLong existing = counters.putIfAbsent(scopeFingerprint, candidate);
                cursor = existing == null ? candidate : existing;
                if (existing == null && counters.size() > MAX_PROVIDER_SCOPES) {
                    counters.remove(scopeFingerprint, candidate);
                    throw new IllegalStateException(
                            "Execution-service sequence scopes exceed " + MAX_PROVIDER_SCOPES);
                }
            }
            long occurrence = cursor.getAndIncrement();
            ByteBuffer material = ByteBuffer.allocate(domain.length + seed.length
                    + normalized.getBytes(StandardCharsets.UTF_8).length + Long.BYTES + 3);
            material.put(domain).put((byte) 0).put(seed).put((byte) 0)
                    .put(normalized.getBytes(StandardCharsets.UTF_8)).put((byte) 0)
                    .putLong(occurrence);
            return sha256(material.array());
        }

        private Map<String, Long> snapshot() {
            Map<String, Long> state = new java.util.TreeMap<>();
            counters.forEach((scope, cursor) -> {
                long value = cursor.get();
                if (value > 0) {
                    state.put(scope, value);
                }
            });
            return Map.copyOf(state);
        }

        private void restore(Map<String, Long> restored) {
            counters.clear();
            restored.forEach((scope, cursor) -> counters.put(scope, new AtomicLong(cursor)));
        }
    }

    private static final class StateCoordinator {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

        private <T> T mutate(Supplier<T> action) {
            lock.readLock().lock();
            try {
                return action.get();
            } finally {
                lock.readLock().unlock();
            }
        }

        private void mutateInterruptibly(InterruptibleAction action) throws InterruptedException {
            lock.readLock().lockInterruptibly();
            try {
                action.run();
            } finally {
                lock.readLock().unlock();
            }
        }

        private <T> T observe(Supplier<T> action) {
            return mutate(action);
        }

        private <T> T checkpoint(Supplier<T> action) {
            lock.writeLock().lock();
            try {
                return action.get();
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    @FunctionalInterface
    private interface InterruptibleAction {
        void run() throws InterruptedException;
    }
}
