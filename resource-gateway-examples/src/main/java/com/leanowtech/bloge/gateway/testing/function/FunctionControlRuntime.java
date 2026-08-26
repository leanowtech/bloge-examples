package com.leanowtech.bloge.gateway.testing.function;

import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.spi.ExecutionServiceKind;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.core.spi.ExpressionFunctionResolver;
import com.leanowtech.bloge.core.spi.FunctionCallSite;
import com.leanowtech.bloge.core.spi.FunctionInvocationContext;
import com.leanowtech.bloge.core.spi.TimeSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * One run-scoped function-control resolver.
 *
 * <p>The runtime decorates the already selected BLOGE resolver. It does not alter the registry
 * function or create a second invocation protocol. Controlled calls are selected only after the
 * BLOGE invocation context supplies their full structural identity.</p>
 */
public final class FunctionControlRuntime implements AutoCloseable {

    private final CompiledFunctionControlPlan plan;
    private final ExpressionFunctionResolver delegate;
    private final TimeSource timeSource;
    private final Map<String, List<ResolvedFunctionControl>> byFunction;
    private final Map<String, ResolvedFunctionControl> bySite;
    private final Map<String, RuleCounter> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> occurrences = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<FunctionControlObservation> observations =
            new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private volatile FunctionControlRunEvidence evidence;

    private FunctionControlRuntime(CompiledFunctionControlPlan plan,
                                   Map<String, ? extends ExpressionFunction> registry,
                                   ExpressionFunctionResolver delegate,
                                   TimeSource timeSource) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        if (registry == null) {
            throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
        }
        this.bySite = new HashMap<>();
        Map<String, List<ResolvedFunctionControl>> functions = new HashMap<>();
        for (ResolvedFunctionControl control : plan.controls()) {
            ExpressionFunction function = registry.get(control.site().functionName());
            if (function == null) {
                function = registry.values().stream().filter(Objects::nonNull)
                        .filter(candidate -> candidate.name().equals(control.runtimeFact().runtimeName()))
                        .findFirst().orElse(null);
            }
            if (function == null || !factMatches(control, function,
                    registry.containsKey(control.site().functionName())
                            ? control.site().functionName() : control.runtimeFact().registryName())) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_BINDING_DRIFT);
            }
            if (bySite.put(control.site().structuralKey(), control) != null) {
                throw new FunctionControlException(FunctionControlException.Code.PLAN_INVALID);
            }
            functions.computeIfAbsent(control.site().functionName(), ignored -> new ArrayList<>())
                    .add(control);
            for (FunctionControlRule rule : control.executableRules()) {
                counters.put(rule.ruleId(), new RuleCounter(rule));
            }
        }
        functions.values().forEach(list -> list.sort(Comparator.comparing(
                control -> control.site().structuralKey())));
        this.byFunction = functions.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    /** Creates a test-only runtime; there is intentionally no production constructor. */
    static FunctionControlRuntime forTestRun(
            CompiledFunctionControlPlan plan,
            Map<String, ? extends ExpressionFunction> registry,
            ExpressionFunctionResolver delegate,
            TimeSource governedTimeSource) {
        return new FunctionControlRuntime(plan, registry, delegate, governedTimeSource);
    }

    /** Creates a runtime from the exact resolver and clock already owned by one run. */
    public static FunctionControlRuntime forTestRun(
            CompiledFunctionControlPlan plan,
            Map<String, ? extends ExpressionFunction> registry,
            ExecutionServices baseServices) {
        Objects.requireNonNull(baseServices, "baseServices");
        return new FunctionControlRuntime(plan, registry,
                baseServices.expressionFunctionResolver(), baseServices.timeSource());
    }

    /** A deterministic in-memory logical clock suitable for isolated test runs. */
    static TimeSource logicalClock() {
        return new LogicalClock();
    }

    /** Returns a resolver that composes the original resolver before applying controls. */
    public ExpressionFunctionResolver resolver() {
        return resolver(null);
    }

    private ExpressionFunctionResolver resolver(
            BiConsumer<FunctionInvocationContext, Set<ExecutionServiceKind>> auditCallback) {
        return (callSite, registeredFunction) -> {
            if (closed) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_CLOSED);
            }
            if (callSite == null || registeredFunction == null) {
                throw new FunctionControlException(FunctionControlException.Code.INVALID_INPUT);
            }
            ExpressionFunction resolved;
            try {
                resolved = delegate.resolve(callSite, registeredFunction);
            } catch (FunctionControlException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_BINDING_DRIFT,
                        failure);
            }
            if (resolved == null) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_BINDING_DRIFT);
            }
            validateResolvedFunction(callSite, registeredFunction);
            validateResolvedFunction(callSite, resolved);
            return decorated(callSite, resolved, auditCallback);
        };
    }

    /** Rebuilds the existing provider set with only its resolver decorated. */
    public ExecutionServices compose(ExecutionServices base) {
        return compose(base, null);
    }

    /**
     * Rebuilds the existing provider set with a run-owner audit callback.
     *
     * <p>The callback is retained only by the composed resolver closure. It is never attached to
     * the returned {@link ExpressionFunction}, so user functions cannot obtain or forge a
     * controlled-invocation audit capability from the resolver.</p>
     */
    public ExecutionServices compose(
            ExecutionServices base,
            BiConsumer<FunctionInvocationContext, Set<ExecutionServiceKind>> auditCallback) {
        Objects.requireNonNull(base, "base");
        if (base.expressionFunctionResolver() != delegate) {
            throw new FunctionControlException(FunctionControlException.Code.RUNTIME_BINDING_DRIFT);
        }
        return new ExecutionServices(base.timeSource(), base.randomSource(), base.idGenerator(),
                base.identityProvider(), base.featureFlagProvider(), base.secretProvider(),
                resolver(auditCallback));
    }

    /** Finishes the run and verifies every configured minimum consumption. */
    public synchronized FunctionControlRunEvidence finish() {
        if (evidence != null) return evidence;
        if (closed) {
            throw new FunctionControlException(FunctionControlException.Code.RUNTIME_CLOSED);
        }
        verifyMinimums();
        return finishEvidence();
    }

    /** Finishes after a graph failure without replacing that primary failure with a minimum error. */
    public synchronized FunctionControlRunEvidence finishAfterFailure() {
        if (evidence != null) return evidence;
        if (closed) {
            throw new FunctionControlException(FunctionControlException.Code.RUNTIME_CLOSED);
        }
        return finishEvidence();
    }

    private void verifyMinimums() {
        for (RuleCounter counter : counters.values()) {
            if (counter.used.get() < counter.rule.consumption().minimum()) {
                throw new FunctionControlException(FunctionControlException.Code.MINIMUM_UNCONSUMED);
            }
        }
    }

    private FunctionControlRunEvidence finishEvidence() {
        List<FunctionControlObservation> ordered = observations.stream().sorted().toList();
        List<FunctionControlConsumption> consumptions = counters.values().stream()
                .map(RuleCounter::projection)
                .sorted(java.util.Comparator.comparing(FunctionControlConsumption::ruleId))
                .toList();
        List<FunctionControlEvidenceBinding> bindings = plan.controls().stream().map(control ->
                new FunctionControlEvidenceBinding(control.site(), control.functionFingerprint(),
                        control.runtimeFact().runtimeFingerprint(), control.mode(),
                        control.evidenceCeiling(), downgradeReason(control))).toList();
        Map<String, Object> material = new java.util.LinkedHashMap<>();
        material.put("planFingerprint", plan.planFingerprint());
        material.put("evidenceCeiling", plan.evidenceCeiling().name());
        material.put("bindings", bindings);
        material.put("consumptions", consumptions);
        material.put("observations", ordered.stream()
                .map(FunctionControlObservation::semanticMaterial).toList());
        String fingerprint = FunctionValueSupport.fingerprint(material);
        evidence = new FunctionControlRunEvidence(plan.planFingerprint(), plan.evidenceCeiling(),
                bindings, consumptions, ordered, fingerprint);
        closed = true;
        return evidence;
    }

    private static String downgradeReason(ResolvedFunctionControl control) {
        if (control.evidenceCeiling() == FunctionEvidenceCeiling.EXPLORATORY
                && control.forcePureOverride()) return "PURE_FORCE_OVERRIDE";
        if (control.evidenceCeiling() == FunctionEvidenceCeiling.PREVIEW) {
            return "DECLARATION_NOT_CERTIFIABLE";
        }
        return "";
    }

    public FunctionControlRunEvidence evidence() {
        return finish();
    }

    @Override
    public void close() {
        finish();
    }

    private ExpressionFunction decorated(
            FunctionCallSite resolverSite,
            ExpressionFunction resolved,
            BiConsumer<FunctionInvocationContext, Set<ExecutionServiceKind>> auditCallback) {
        return new ExpressionFunction() {
            @Override
            public String name() { return resolved.name(); }

            @Override
            public Object apply(Object... args) {
                if (!byFunction.containsKey(resolverSite.functionName())) {
                    return resolved.apply(args);
                }
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_CONTEXT_INVALID);
            }

            @Override
            public Object apply(FunctionInvocationContext context, Object... args) {
                if (context == null) {
                    throw new FunctionControlException(FunctionControlException.Code.RUNTIME_CONTEXT_INVALID);
                }
                FunctionInvocationSite site = site(context);
                ResolvedFunctionControl control = bySite.get(site.structuralKey());
                if (control == null) {
                    if (!byFunction.containsKey(resolverSite.functionName())) {
                        return resolved.apply(context, args);
                    }
                    throw new FunctionControlException(FunctionControlException.Code.RUNTIME_SITE_UNPLANNED);
                }
                if (control.mode() == FunctionControlMode.DIRECT) {
                    return resolved.apply(context, args);
                }
                return execute(control, context, args, resolved, auditCallback);
            }

            @Override
            public String returnType(String... argTypes) { return resolved.returnType(argTypes); }

            @Override
            public boolean isPure() { return resolved.isPure(); }

            @Override
            public Set<ExecutionServiceKind> requiredExecutionServices() {
                return resolved.requiredExecutionServices();
            }
        };
    }

    private Object execute(ResolvedFunctionControl control,
                           FunctionInvocationContext context, Object[] args,
                           ExpressionFunction resolved,
                           BiConsumer<FunctionInvocationContext, Set<ExecutionServiceKind>> auditCallback) {
        if (auditCallback != null) {
            try {
                auditCallback.accept(context, Set.copyOf(resolved.requiredExecutionServices()));
            } catch (RuntimeException failure) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_CONTEXT_INVALID,
                        failure);
            }
        }
        String argsFingerprint = FunctionValueSupport.fingerprint(arguments(args));
        String scopeFingerprint = FunctionValueSupport.fingerprint(context.invocationScope());
        long occurrence = occurrence(control.site(), scopeFingerprint, argsFingerprint);
        FunctionControlRule rule;
        try {
            rule = select(control, argsFingerprint);
        } catch (FunctionControlException failure) {
            recordFailure(control.site(), "__unmatched__", FunctionControlRule.Behavior.THROW,
                    scopeFingerprint, argsFingerprint, failure.code(), occurrence);
            throw failure;
        }
        RuleCounter counter = counters.get(rule.ruleId());
        try {
            counter.consume();
        } catch (FunctionControlException failure) {
            recordFailure(control.site(), rule.ruleId(), rule.behavior(), scopeFingerprint,
                    argsFingerprint, failure.code(), occurrence);
            throw failure;
        }
        String resultFingerprint = rule.returnValueFingerprint();
        String errorFingerprint = rule.errorFingerprint();
        switch (rule.behavior()) {
            case RETURN -> {
                Object result = FunctionValueSupport.freeze(rule.executableReturnValue());
                record(control.site(), rule, scopeFingerprint, argsFingerprint, resultFingerprint,
                        errorFingerprint,
                        occurrence);
                return result;
            }
            case DELAY -> {
                try {
                    timeSource.sleep(rule.duration());
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    recordFailure(control.site(), rule.ruleId(), rule.behavior(), scopeFingerprint,
                            argsFingerprint, FunctionControlException.Code.CONTROL_DELAY_FAILED,
                            occurrence);
                    throw new FunctionControlException(FunctionControlException.Code.CONTROL_DELAY_FAILED,
                            failure);
                } catch (RuntimeException failure) {
                    recordFailure(control.site(), rule.ruleId(), rule.behavior(), scopeFingerprint,
                            argsFingerprint, FunctionControlException.Code.CONTROL_DELAY_FAILED,
                            occurrence);
                    throw new FunctionControlException(FunctionControlException.Code.CONTROL_DELAY_FAILED,
                            failure);
                }
                record(control.site(), rule, scopeFingerprint, argsFingerprint, resultFingerprint,
                        errorFingerprint,
                        occurrence);
                return FunctionValueSupport.freeze(rule.executableReturnValue());
            }
            case THROW -> {
                record(control.site(), rule, scopeFingerprint, argsFingerprint, "", errorFingerprint,
                        occurrence);
                throw new FunctionControlException(FunctionControlException.Code.CONTROL_THROW);
            }
            case TIMEOUT -> {
                record(control.site(), rule, scopeFingerprint, argsFingerprint, "",
                        FunctionValueSupport.fingerprint("RG.FUNCTION.CONTROL_TIMEOUT"), occurrence);
                throw new FunctionControlException(FunctionControlException.Code.CONTROL_TIMEOUT);
            }
            default -> throw new FunctionControlException(FunctionControlException.Code.RULE_INVALID);
        }
    }

    private FunctionControlRule select(ResolvedFunctionControl control, String actualFingerprint) {
        FunctionControlRule wildcard = null;
        for (FunctionControlRule candidate : control.executableRules()) {
            if (candidate.expectedArguments() == null) {
                if (wildcard == null) wildcard = candidate;
            } else if (actualFingerprint.equals(FunctionValueSupport.fingerprint(candidate.expectedArguments()))) {
                return candidate;
            }
        }
        if (wildcard != null) return wildcard;
        throw new FunctionControlException(FunctionControlException.Code.CONTROL_ARGUMENT_MISMATCH);
    }

    private void record(FunctionInvocationSite site, FunctionControlRule rule,
                        String scopeFingerprint, String argsFingerprint, String resultFingerprint,
                        String errorFingerprint, long occurrence) {
        observations.add(new FunctionControlObservation(site, rule.ruleId(), rule.behavior(),
                scopeFingerprint, argsFingerprint, resultFingerprint, errorFingerprint, occurrence,
                rule.duration().toMillis()));
    }

    private void recordFailure(FunctionInvocationSite site, String ruleId,
                               FunctionControlRule.Behavior behavior, String scopeFingerprint,
                               String argsFingerprint, FunctionControlException.Code code,
                               long occurrence) {
        observations.add(new FunctionControlObservation(site, ruleId, behavior, scopeFingerprint,
                argsFingerprint, "", FunctionValueSupport.fingerprint("RG.FUNCTION." + code.name()),
                occurrence, 0));
    }

    private long occurrence(FunctionInvocationSite site, String scopeFingerprint,
                            String argumentsFingerprint) {
        String scope = site.structuralKey() + "\u0000" + scopeFingerprint + "\u0000"
                + argumentsFingerprint;
        return occurrences.computeIfAbsent(scope, ignored -> new AtomicLong()).incrementAndGet();
    }

    private void validateResolvedFunction(FunctionCallSite site, ExpressionFunction function) {
        List<ResolvedFunctionControl> controls = byFunction.get(site.functionName());
        if (controls == null) return;
        for (ResolvedFunctionControl control : controls) {
            if (!factMatches(control, function, site.functionName())) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_BINDING_DRIFT);
            }
        }
    }

    private static boolean factMatches(ResolvedFunctionControl control, ExpressionFunction function,
                                       String registryName) {
        try {
            return control.runtimeFact().runtimeFingerprint()
                    .equals(FunctionRuntimeFact.from(registryName, function).runtimeFingerprint());
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.RUNTIME_BINDING_DRIFT,
                    failure);
        }
    }

    private static FunctionInvocationSite site(FunctionInvocationContext context) {
        try {
            if (context.nodeId() == null) {
                throw new FunctionControlException(FunctionControlException.Code.RUNTIME_CONTEXT_INVALID);
            }
            return new FunctionInvocationSite(context.graphPath(), context.nodeId(),
                    context.callSite().functionName(), context.sourceLine(), context.sourceColumn());
        } catch (FunctionControlException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new FunctionControlException(FunctionControlException.Code.RUNTIME_CONTEXT_INVALID,
                    failure);
        }
    }

    private static List<Object> arguments(Object[] args) {
        return args == null ? List.of() : List.copyOf(Arrays.asList(args));
    }

    private static final class RuleCounter {
        private final FunctionControlRule rule;
        private final AtomicLong used = new AtomicLong();

        private RuleCounter(FunctionControlRule rule) { this.rule = rule; }

        private void consume() {
            while (true) {
                long current = used.get();
                if (current >= rule.consumption().maximum()) {
                    throw new FunctionControlException(FunctionControlException.Code.CONTROL_EXHAUSTED);
                }
                if (used.compareAndSet(current, current + 1)) return;
            }
        }

        private FunctionControlConsumption projection() {
            long count = used.get();
            String status = count < rule.consumption().minimum()
                    ? "MINIMUM_UNSATISFIED"
                    : count == rule.consumption().maximum() ? "MAX_REACHED" : "SATISFIED";
            return new FunctionControlConsumption(rule.ruleId(), rule.consumption().minimum(),
                    rule.consumption().maximum(), count, status);
        }
    }

    private static final class LogicalClock implements TimeSource {
        private final java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);

        @Override
        public Instant now() { return now.get(); }

        @Override
        public void sleep(Duration duration) {
            if (duration == null || duration.isNegative()) {
                throw new FunctionControlException(FunctionControlException.Code.CONTROL_DELAY_FAILED);
            }
            now.updateAndGet(current -> current.plus(duration));
        }
    }
}
