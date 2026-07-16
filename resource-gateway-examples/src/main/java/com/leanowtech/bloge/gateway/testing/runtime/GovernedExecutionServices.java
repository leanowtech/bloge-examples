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
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
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

/**
 * Frozen test-run execution services and their payload-free usage audit.
 *
 * <p>The instance is created during plan compilation and is carried by
 * {@link com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl}; runtime code
 * never rebuilds it from a mutable fixture. A configured seed drives independent SHA-256 streams
 * for random values and identifiers. Identity, feature flags, and secrets remain fail closed until
 * corresponding governed fixture authorities exist.</p>
 */
public final class GovernedExecutionServices {

    private static final String BINDING_SCHEMA = "bloge.executionServiceBinding.v1";
    private static final String USAGE_SCHEMA = "bloge.executionServiceUsage.v1";

    private final ObjectMapper objectMapper;
    private final ExecutionServices services;
    private final List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings;
    private final UsageTracker usageTracker;
    private final AdvancingLogicalTimeSource logicalTime;

    private GovernedExecutionServices(ObjectMapper objectMapper, ExecutionServices services,
                                      List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings,
                                      UsageTracker usageTracker,
                                      AdvancingLogicalTimeSource logicalTime) {
        this.objectMapper = objectMapper;
        this.services = services;
        this.bindings = List.copyOf(bindings);
        this.usageTracker = usageTracker;
        this.logicalTime = logicalTime;
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
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(fixtureBundle, "fixtureBundle");
        Objects.requireNonNull(inventory, "inventory");
        UsageTracker tracker = new UsageTracker();
        AdvancingLogicalTimeSource logicalTime = fixtureBundle.logicalClock() == null
                ? null : new AdvancingLogicalTimeSource(fixtureBundle.logicalClock());
        TimeSource timeSource = logicalTime == null ? SystemTimeSource.INSTANCE : logicalTime;
        Long seed = fixtureBundle.randomSeed();
        ScopedDigestSequence randomSequence = seed == null
                ? null : new ScopedDigestSequence(seed, "random");
        ScopedDigestSequence idSequence = seed == null
                ? null : new ScopedDigestSequence(seed, "uuid");

        RandomSource randomSource = scope -> {
            tracker.recordProvider(ExecutionServiceKind.RANDOM, scope, true);
            return randomSequence == null
                    ? RandomSource.SYSTEM.nextLong(scope) : randomSequence.nextLong(scope);
        };
        IdGenerator idGenerator = scope -> {
            tracker.recordProvider(ExecutionServiceKind.UUID, scope,
                    !isInfrastructureIdScope(scope));
            return idSequence == null
                    ? IdGenerator.UUID_V4.nextId(scope) : idSequence.nextUuid(scope);
        };
        IdentityProvider identityProvider = attribute -> {
            tracker.recordProvider(ExecutionServiceKind.IDENTITY, attribute, true);
            return IdentityProvider.NONE.resolve(attribute);
        };
        FeatureFlagProvider featureFlags = flag -> {
            tracker.recordProvider(ExecutionServiceKind.FEATURE_FLAG, flag, true);
            return FeatureFlagProvider.NONE.enabled(flag);
        };
        SecretProvider secrets = name -> {
            tracker.recordProvider(ExecutionServiceKind.SECRET, name, true);
            return SecretProvider.NONE.resolve(name);
        };

        ExecutionServices services = ExecutionServices.builder()
                .timeSource(timeSource)
                .randomSource(randomSource)
                .idGenerator(idGenerator)
                .identityProvider(identityProvider)
                .featureFlagProvider(featureFlags)
                .secretProvider(secrets)
                .expressionFunctionResolver((site, function) -> audited(site, function, tracker))
                .build();
        Map<ExecutionServiceKind, List<String>> consumers = declaredConsumers(inventory);
        List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings = Arrays.stream(
                        ExecutionServiceKind.values())
                .map(kind -> binding(objectMapper, kind, fixtureBundle, consumers.getOrDefault(kind, List.of())))
                .toList();
        return new GovernedExecutionServices(objectMapper, services, bindings, tracker, logicalTime);
    }

    /** @return exact service object that must be passed to BLOGE {@code ExecutionOptions} */
    public ExecutionServices services() {
        return services;
    }

    /** @return payload-free plan bindings in stable service order */
    public List<EffectiveExecutionPlan.ExecutionServiceBinding> bindings() {
        return bindings;
    }

    /** @return run logical clock, or {@code null} when system time is active */
    public AdvancingLogicalTimeSource logicalTime() {
        return logicalTime;
    }

    /**
     * Returns stable payload-free usage facts. Raw provider scopes are hashed before exposure.
     *
     * @return ordered usages for services that were actually invoked
     */
    public List<ExecutionServiceUsage> usageSnapshot() {
        Map<String, String> modes = bindings.stream().collect(java.util.stream.Collectors.toMap(
                EffectiveExecutionPlan.ExecutionServiceBinding::service,
                EffectiveExecutionPlan.ExecutionServiceBinding::mode));
        return usageTracker.snapshot().stream().map(usage -> new ExecutionServiceUsage(
                usage.schemaVersion(), usage.service(), modes.getOrDefault(usage.service(), "UNKNOWN"),
                usage.providerCalls(), usage.semanticProviderCalls(), usage.functionCalls(),
                usage.functionCallSites(), usage.providerScopeFingerprints())).toList();
    }

    /** @return canonical digest of the current provider-use state */
    public String stateFingerprint() {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", USAGE_SCHEMA,
                "usages", usageSnapshot()));
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
        Map<String, EffectiveExecutionPlan.ExecutionServiceBinding> byService = bindings.stream()
                .collect(java.util.stream.Collectors.toMap(
                        EffectiveExecutionPlan.ExecutionServiceBinding::service, binding -> binding));
        Set<String> gaps = new java.util.LinkedHashSet<>();
        bindings.stream().filter(EffectiveExecutionPlan.ExecutionServiceBinding::required)
                .filter(binding -> !binding.certificationEligibleWhenUsed())
                .flatMap(binding -> binding.certificationGaps().stream())
                .forEach(gaps::add);
        for (ExecutionServiceUsage usage : usageSnapshot()) {
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

    private static ExpressionFunction audited(FunctionCallSite site, ExpressionFunction function,
                                               UsageTracker tracker) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(function, "function");
        return new ExpressionFunction() {
            @Override
            public String name() {
                return function.name();
            }

            @Override
            public Object apply(Object... args) {
                tracker.recordFunction(site, function.requiredExecutionServices());
                return function.apply(args);
            }

            @Override
            public Object apply(FunctionInvocationContext context, Object... args) {
                tracker.recordFunction(site, function.requiredExecutionServices());
                return function.apply(context, args);
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
            case IDENTITY, FEATURE_FLAG, SECRET -> {
                mode = "FAIL_CLOSED";
                available = false;
                deterministic = true;
                configuration = "UNCONFIGURED";
                gaps.add(kind.name() + " has no governed test authority configured.");
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
            counter.providerCalls.increment();
            if (semantic) counter.semanticProviderCalls.increment();
            counter.providerScopeFingerprints.add(digest(scope));
        }

        private void recordFunction(FunctionCallSite site, Set<ExecutionServiceKind> kinds) {
            for (ExecutionServiceKind kind : kinds) {
                UsageCounter counter = counters.get(kind);
                counter.functionCalls.increment();
                counter.functionCallSites.add(site.structuralId());
            }
        }

        private List<ExecutionServiceUsage> snapshot() {
            return counters.entrySet().stream()
                    .filter(entry -> entry.getValue().used())
                    .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                    .map(entry -> entry.getValue().snapshot(entry.getKey()))
                    .toList();
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

        private ExecutionServiceUsage snapshot(ExecutionServiceKind kind) {
            return new ExecutionServiceUsage(USAGE_SCHEMA, kind.name(), "OBSERVED",
                    providerCalls.sum(), semanticProviderCalls.sum(), functionCalls.sum(),
                    List.copyOf(functionCallSites), List.copyOf(providerScopeFingerprints));
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
            long occurrence = counters.computeIfAbsent(normalized, ignored -> new AtomicLong())
                    .getAndIncrement();
            ByteBuffer material = ByteBuffer.allocate(domain.length + seed.length
                    + normalized.getBytes(StandardCharsets.UTF_8).length + Long.BYTES + 3);
            material.put(domain).put((byte) 0).put(seed).put((byte) 0)
                    .put(normalized.getBytes(StandardCharsets.UTF_8)).put((byte) 0)
                    .putLong(occurrence);
            return sha256(material.array());
        }
    }
}
