package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.exception.OperatorTimeoutException;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectProtocol;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaValidator;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.SelectorResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates schema-gated real, fixed, failure, logical-time, denial, and observation controls. */
public class TestDoubleFactory {

    private final ObjectMapper objectMapper;
    private final FixtureMatcher matcher;
    private final ResourceFixtureRuntime resourceRuntime;
    private final MirrorResolverChain mirrorResolverChain;

    /**
     * @param objectMapper mapper for canonical input matching and schema-visible values
     * @param resourceRuntime optional descriptor-backed protocol runtime; required by raw HTTP fixtures
     */
    public TestDoubleFactory(ObjectMapper objectMapper, ResourceFixtureRuntime resourceRuntime) {
        this(objectMapper, resourceRuntime, MirrorResolverChain.standard(objectMapper));
    }

    /**
     * Creates a factory with an explicitly assembled mirror resolver chain.
     *
     * @param objectMapper mapper for canonical input matching and schema-visible values
     * @param resourceRuntime optional descriptor-backed protocol runtime
     * @param mirrorResolverChain exact resolver implementations admitted by this runtime
     */
    public TestDoubleFactory(
            ObjectMapper objectMapper,
            ResourceFixtureRuntime resourceRuntime,
            MirrorResolverChain mirrorResolverChain) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.matcher = new FixtureMatcher(objectMapper);
        this.resourceRuntime = resourceRuntime;
        this.mirrorResolverChain = Objects.requireNonNull(
                mirrorResolverChain, "mirrorResolverChain");
    }

    /**
     * Creates one node-scoped controlled operator.
     *
     * @param node frozen node specification
     * @param binding occurrence-specific invocation coordinates
     * @param rules preflight-resolved, pairwise-disjoint candidate rules
     * @param realOperator frozen real binding
     * @param implicitDeny whether missing rules represent fail-closed external-effect policy
     * @param recorder per-run trace and consumption recorder
     * @return operator passed only to the independent test engine
     */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> create(NodeSpec node, InvocationRecorder.InvocationBinding binding,
                                           List<FixtureRule> rules,
                                           Object realOperator, boolean implicitDeny,
                                           InvocationRecorder recorder) {
        return create(node, binding, rules, realOperator, implicitDeny, recorder,
                ResolvedReplayPayloads.empty());
    }

    /**
     * Creates one controlled operator with exact run-scoped replay values.
     *
     * @param node frozen node specification
     * @param binding occurrence-specific invocation coordinates
     * @param rules preflight-resolved candidate rules
     * @param realOperator frozen real binding
     * @param implicitDeny whether the planner synthesized fail-closed denial
     * @param recorder per-run trace and consumption recorder
     * @param replayPayloads payloads frozen before plan compilation
     * @return operator passed only to the independent test engine
     */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> create(NodeSpec node, InvocationRecorder.InvocationBinding binding,
                                           List<FixtureRule> rules,
                                           Object realOperator, boolean implicitDeny,
                                           InvocationRecorder recorder,
                                           ResolvedReplayPayloads replayPayloads) {
        if (!(realOperator instanceof Operator<?, ?> typed)) {
            throw new IllegalArgumentException("Node '" + node.id()
                    + "' is not a synchronous Operator and cannot use v1 execution control.");
        }
        Operator<Object, Object> controlled = new ControlledOperator(node, binding, rules,
                (Operator<Object, Object>) typed, implicitDeny, recorder, replayPayloads,
                null, ResolvedCorpusPayloads.empty(), MirrorResolutionObserver.noop(),
                null, MirrorStateAccessObserver.noop());
        return observed(node, binding, controlled, recorder);
    }

    /**
     * Creates a control that preserves its compiled ordinary or mirror resolution strategy.
     *
     * @param node frozen node specification
     * @param binding occurrence-specific invocation coordinates
     * @param control exact compiled site control
     * @param realOperator frozen real binding
     * @param recorder shared test evidence recorder
     * @param replayPayloads exact replay closure
     * @param mirrorObserver mirror provenance sink
     * @return isolated controlled operator
     */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> create(
            NodeSpec node,
            InvocationRecorder.InvocationBinding binding,
            CompiledExecutionControl.ResolvedControl control,
            Object realOperator,
            InvocationRecorder recorder,
            ResolvedReplayPayloads replayPayloads,
            MirrorResolutionObserver mirrorObserver) {
        if (!(realOperator instanceof Operator<?, ?> typed)) {
            throw new IllegalArgumentException("Node '" + node.id()
                    + "' is not a synchronous Operator and cannot use v1 execution control.");
        }
        CompiledExecutionControl.ResolvedControl requiredControl = Objects.requireNonNull(
                control, "control");
        Operator<Object, Object> controlled = new ControlledOperator(
                node, binding, requiredControl.rules(), (Operator<Object, Object>) typed,
                requiredControl.implicitDeny(), recorder, replayPayloads, requiredControl,
                ResolvedCorpusPayloads.empty(),
                Objects.requireNonNull(mirrorObserver, "mirrorObserver"), null,
                MirrorStateAccessObserver.noop());
        return observed(node, binding, controlled, recorder);
    }

    /**
     * Creates a mirror control with exact replay and recorded-corpus snapshots.
     *
     * @param node frozen node specification
     * @param binding occurrence-specific invocation coordinates
     * @param control exact compiled site control
     * @param realOperator frozen real binding
     * @param recorder shared test evidence recorder
     * @param replayPayloads exact governed replay closure
     * @param corpusPayloads exact governed recorded corpus closure
     * @param mirrorObserver mirror provenance sink
     * @return isolated controlled operator
     */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> create(
            NodeSpec node,
            InvocationRecorder.InvocationBinding binding,
            CompiledExecutionControl.ResolvedControl control,
            Object realOperator,
            InvocationRecorder recorder,
            ResolvedReplayPayloads replayPayloads,
            ResolvedCorpusPayloads corpusPayloads,
            MirrorResolutionObserver mirrorObserver) {
        return create(node, binding, control, realOperator, recorder,
                replayPayloads, corpusPayloads, mirrorObserver, null,
                MirrorStateAccessObserver.noop());
    }

    /**
     * Creates a mirror control that shares one immutable session state head across the run.
     *
     * @param node frozen node specification
     * @param binding occurrence-specific invocation coordinates
     * @param control exact compiled site control
     * @param realOperator frozen real binding
     * @param recorder shared test evidence recorder
     * @param replayPayloads exact governed replay closure
     * @param corpusPayloads exact governed recorded corpus closure
     * @param mirrorObserver mirror provenance sink
     * @param sessionContext immutable session state head, or {@code null}
     * @return isolated controlled operator
     */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> create(
            NodeSpec node,
            InvocationRecorder.InvocationBinding binding,
            CompiledExecutionControl.ResolvedControl control,
            Object realOperator,
            InvocationRecorder recorder,
            ResolvedReplayPayloads replayPayloads,
            ResolvedCorpusPayloads corpusPayloads,
            MirrorResolutionObserver mirrorObserver,
            MirrorResolver.SessionContext sessionContext) {
        return create(node, binding, control, realOperator, recorder,
                replayPayloads, corpusPayloads, mirrorObserver,
                sessionContext, MirrorStateAccessObserver.noop());
    }

    /**
     * Creates a state-evidenced mirror control over one immutable Session state head.
     *
     * @param node frozen node specification
     * @param binding occurrence-specific invocation coordinates
     * @param control exact compiled site control
     * @param realOperator frozen real binding
     * @param recorder shared test evidence recorder
     * @param replayPayloads exact governed replay closure
     * @param corpusPayloads exact governed recorded corpus closure
     * @param mirrorObserver mirror provenance sink
     * @param sessionContext immutable Session state head, or {@code null}
     * @param stateAccessObserver payload-free Session state access sink
     * @return isolated controlled operator
     */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> create(
            NodeSpec node,
            InvocationRecorder.InvocationBinding binding,
            CompiledExecutionControl.ResolvedControl control,
            Object realOperator,
            InvocationRecorder recorder,
            ResolvedReplayPayloads replayPayloads,
            ResolvedCorpusPayloads corpusPayloads,
            MirrorResolutionObserver mirrorObserver,
            MirrorResolver.SessionContext sessionContext,
            MirrorStateAccessObserver stateAccessObserver) {
        if (!(realOperator instanceof Operator<?, ?> typed)) {
            throw new IllegalArgumentException("Node '" + node.id()
                    + "' is not a synchronous Operator and cannot use v1 execution control.");
        }
        CompiledExecutionControl.ResolvedControl requiredControl = Objects.requireNonNull(
                control, "control");
        Operator<Object, Object> controlled = new ControlledOperator(
                node, binding, requiredControl.rules(), (Operator<Object, Object>) typed,
                requiredControl.implicitDeny(), recorder, replayPayloads, requiredControl,
                corpusPayloads,
                Objects.requireNonNull(mirrorObserver, "mirrorObserver"),
                sessionContext,
                Objects.requireNonNull(
                        stateAccessObserver, "stateAccessObserver"));
        return observed(node, binding, controlled, recorder);
    }

    /**
     * Wraps an uncontrolled synchronous binding with the same occurrence and attempt observer used
     * by test doubles. The wrapper delegates operator safety metadata unchanged.
     *
     * @param node frozen node specification
     * @param binding occurrence-specific invocation coordinates
     * @param realOperator frozen real binding
     * @param recorder per-run evidence recorder
     * @return observed operator passed only to the independent test engine
     */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> observe(NodeSpec node,
                                            InvocationRecorder.InvocationBinding binding,
                                            Object realOperator, InvocationRecorder recorder) {
        if (!(realOperator instanceof Operator<?, ?> typed)) {
            throw new IllegalArgumentException("Node '" + node.id()
                    + "' is not a synchronous Operator and cannot produce v1 attempt evidence.");
        }
        return observed(node, binding, (Operator<Object, Object>) typed, recorder);
    }

    private Operator<Object, Object> observed(NodeSpec node,
                                              InvocationRecorder.InvocationBinding binding,
                                              Operator<Object, Object> delegate,
                                              InvocationRecorder recorder) {
        return new ObservedOperator(node, binding, delegate, recorder);
    }

    private final class ControlledOperator implements Operator<Object, Object> {
        private final NodeSpec node;
        private final InvocationRecorder.InvocationBinding binding;
        private final InvocationSite site;
        private final List<FixtureRule> rules;
        private final Operator<Object, Object> real;
        private final boolean implicitDeny;
        private final InvocationRecorder recorder;
        private final ResolvedReplayPayloads replayPayloads;
        private final CompiledExecutionControl.ResolvedControl compiledControl;
        private final ResolvedCorpusPayloads corpusPayloads;
        private final MirrorResolutionObserver mirrorObserver;
        private final MirrorResolver.SessionContext sessionContext;
        private final MirrorStateAccessObserver stateAccessObserver;

        private ControlledOperator(NodeSpec node, InvocationRecorder.InvocationBinding binding,
                                   List<FixtureRule> rules,
                                   Operator<Object, Object> real, boolean implicitDeny,
                                   InvocationRecorder recorder,
                                   ResolvedReplayPayloads replayPayloads,
                                   CompiledExecutionControl.ResolvedControl compiledControl,
                                   ResolvedCorpusPayloads corpusPayloads,
                                   MirrorResolutionObserver mirrorObserver,
                                   MirrorResolver.SessionContext sessionContext,
                                   MirrorStateAccessObserver stateAccessObserver) {
            this.node = node;
            this.binding = Objects.requireNonNull(binding, "binding");
            this.site = binding.site();
            this.rules = List.copyOf(rules);
            this.real = real;
            this.implicitDeny = implicitDeny;
            this.recorder = recorder;
            this.replayPayloads = replayPayloads == null
                    ? ResolvedReplayPayloads.empty() : replayPayloads;
            this.compiledControl = compiledControl;
            this.corpusPayloads = corpusPayloads == null
                    ? ResolvedCorpusPayloads.empty() : corpusPayloads;
            this.mirrorObserver = Objects.requireNonNull(mirrorObserver, "mirrorObserver");
            this.sessionContext = sessionContext;
            this.stateAccessObserver = Objects.requireNonNull(
                    stateAccessObserver, "stateAccessObserver");
        }

        @Override
        public Object execute(Object input, OperatorContext context) throws Exception {
            recorder.markFidelity(site, "OUTPUT_LEVEL");
            int attempt = context.retryAttempt() + 1;
            List<FixtureRule> matched = rules.stream()
                    .filter(rule -> matcher.matches(rule, input, site.correlationKey(),
                            attempt, binding.occurrence())).toList();
            if (compiledControl != null && compiledControl.resolutionStrategy()
                    == CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                    .MIRROR_SOURCE_THEN_SELECTOR) {
                return executeMirror(input, context, attempt, matched);
            }
            if (matched.isEmpty()) {
                if (!implicitDeny && rules.stream().anyMatch(rule -> rule.consumption().onUnmatched()
                        == FixtureRule.UnmatchedAction.ALLOW_REAL)) {
                    recorder.markFidelity(site, "REAL");
                    recorder.markControlMode(site, "REAL");
                    return real.execute(input, context);
                }
                recorder.markFidelity(site, "OUTPUT_LEVEL");
                recorder.markControlMode(site, implicitDeny ? "IMPLICIT_DENY" : "UNMATCHED");
                throw new TestControlException("FIXTURE_UNMATCHED", "FIXTURE_MATCH",
                        "No approved fixture matched invocation site "
                                + site.invocationSiteId() + ".");
            }
            int winningPrecedence = matched.isEmpty() ? -1
                    : SelectorResolver.precedence(matched.getFirst().selector());
            List<FixtureRule> winning = matched.stream()
                    .takeWhile(rule -> SelectorResolver.precedence(rule.selector())
                            == winningPrecedence)
                    .toList();
            if (winning.size() > 1) {
                throw new TestControlException("CONTROL_PLAN_RUNTIME_AMBIGUITY", "FIXTURE_MATCH",
                        "More than one fixture matched invocation site "
                                + site.invocationSiteId() + ".");
            }
            FixtureRule rule = winning.getFirst();
            if (recorder.consumeIfAvailable(rule.ruleId(), rule.consumption().maxUses()) < 0) {
                if (rule.consumption().onExhausted() == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL) {
                    recorder.markFidelity(site, "REAL");
                    recorder.markControlMode(site, "REAL");
                    return real.execute(input, context);
                }
                throw new TestControlException("FIXTURE_EXHAUSTED", "FIXTURE_CONSUMPTION",
                        "Fixture rule '" + rule.ruleId() + "' exceeded maxUses.");
            }
            return apply(rule, input, context);
        }

        private Object executeMirror(
                Object input,
                OperatorContext context,
                int attempt,
                List<FixtureRule> matched) throws Exception {
            String requestFingerprint = MirrorResolutionJournal.requestFingerprint(
                    objectMapper, input);
            MirrorResolver.Request request = new MirrorResolver.Request(
                    site, binding.occurrence(), attempt, requestFingerprint, input, matched,
                    corpusPayloads.forSite(site.invocationSiteId()).orElse(null),
                    sessionContext, stateAccessObserver);
            MirrorResolverChain.Decision decision = mirrorResolverChain.resolve(
                    compiledControl, request);
            if (decision.abstained()) {
                recorder.markFidelity(site, "OUTPUT_LEVEL");
                recorder.markControlMode(site, "ABSTAINED");
                mirrorObserver.abstained(binding, attempt, requestFingerprint);
                throw new TestControlException("FIXTURE_UNMATCHED", "FIXTURE_MATCH",
                        "Every admitted mirror source abstained from this invocation.");
            }
            FixtureRule rule = decision.match().rule();
            if (recorder.consumeIfAvailable(rule.ruleId(), rule.consumption().maxUses()) < 0) {
                TestControlException exhausted = new TestControlException(
                        "FIXTURE_EXHAUSTED", "FIXTURE_CONSUMPTION",
                        "Selected mirror rule exceeded maxUses.");
                mirrorObserver.failed(binding, attempt, requestFingerprint, decision, exhausted);
                throw exhausted;
            }
            try {
                Object output = applyMirror(
                        decision.match(), input, context);
                mirrorObserver.resolved(binding, attempt, requestFingerprint, decision, output);
                return output;
            } catch (Exception failure) {
                mirrorObserver.failed(binding, attempt, requestFingerprint, decision, failure);
                throw failure;
            }
        }

        private Object applyMirror(
                MirrorResolver.Match match,
                Object input,
                OperatorContext context) throws Exception {
            FixtureRule rule = match.rule();
            if (!match.retryableOutcome()) {
                return apply(rule, input, context);
            }
            FixtureRule.Behavior behavior = rule.behavior();
            recorder.markFidelity(site, "OUTPUT_LEVEL");
            recorder.markControlMode(site, behavior.kind().name());
            throw new TestRetryableOutcomeException(
                    behavior.errorCode(),
                    behavior.errorType(),
                    behavior.errorMessage());
        }

        private Object apply(FixtureRule rule, Object input, OperatorContext context) throws Exception {
            FixtureRule.Behavior behavior = rule.behavior();
            recorder.markControlMode(site, behavior.kind().name());
            return switch (behavior.kind()) {
                case REAL -> {
                    recorder.markFidelity(site, "REAL");
                    yield real.execute(input, context);
                }
                case SPY -> {
                    recorder.markFidelity(site, "REAL");
                    yield real.execute(input, context);
                }
                case RETURN -> returnValue(rule, input, context);
                case DELAY -> {
                    context.timeSource().sleep(behavior.after());
                    yield returnValue(rule, input, context);
                }
                case TIMEOUT -> {
                    recorder.markFidelity(site, "OUTPUT_LEVEL");
                    context.timeSource().sleep(behavior.after());
                    throw new OperatorTimeoutException(node.id(), behavior.after(),
                            controlledFailure(behavior, "TEST_TIMEOUT"));
                }
                case THROW -> {
                    recorder.markFidelity(site, "OUTPUT_LEVEL");
                    throw controlledFailure(behavior, "TEST_THROW");
                }
                case DENY -> {
                    recorder.markFidelity(site, "OUTPUT_LEVEL");
                    throw controlledFailure(behavior, "TEST_CONTROL_DENIED");
                }
                case REPLAY -> replayValue(rule);
                case STREAM -> throw new TestControlException(
                        "CONTROL_PLAN_RESERVED_BEHAVIOR", "CONTROL_PLAN",
                        "Behavior " + behavior.kind() + " is reserved in v1.");
            };
        }

        private Object replayValue(FixtureRule rule) {
            recorder.markFidelity(site, "REPLAYED");
            Object output;
            try {
                output = replayPayloads.require(rule.behavior().replayRef())
                        .materialize(objectMapper, node.operatorRef());
            } catch (IllegalArgumentException missing) {
                throw new TestControlException("REPLAY_PAYLOAD_NOT_FROZEN", "REPLAY",
                        "Replay payload was not frozen into the effective execution plan.");
            }
            validateOutput(rule, output);
            return output;
        }

        private Object returnValue(FixtureRule rule, Object input, OperatorContext context) throws Exception {
            FixtureRule.Behavior behavior = rule.behavior();
            Object output;
            if ("httpResource".equals(node.operatorRef())
                    && behavior.statusCode() != null && behavior.value() == null) {
                if (resourceRuntime == null) {
                    throw new TestControlException("RESOURCE_FIXTURE_RUNTIME_UNAVAILABLE",
                            "RESOURCE_FIXTURE", "Protocol-derived fixture requires a ResourceFixtureRuntime.");
                }
                String fidelity = behavior.boundary() == FixtureRule.DoubleBoundary.TRANSPORT
                        ? "TRANSPORT_LEVEL" : "PROTOCOL_DERIVED";
                recorder.markFidelity(site, fidelity);
                output = resourceRuntime.execute(behavior, input, context);
            } else if ("httpResource".equals(node.operatorRef())) {
                recorder.markFidelity(site, "OUTPUT_LEVEL");
                output = resourceOutput(input, behavior);
            } else {
                recorder.markFidelity(site, "OUTPUT_LEVEL");
                output = behavior.value();
            }
            validateOutput(rule, output);
            return output;
        }

        private void validateOutput(FixtureRule rule, Object output) {
            if (rule.schemaCheck().mode() == FixtureRule.SchemaCheckMode.WAIVED
                    || node.outputSchema() instanceof OpaqueSchema) {
                return;
            }
            List<String> errors = SchemaValidator.validateInstance(node.id(), output, node.outputSchema())
                    .stream().map(violation -> violation.path() + ": " + violation.message()).toList();
            if (!errors.isEmpty()) {
                throw new TestControlException("FIXTURE_OUTPUT_SCHEMA_MISMATCH", "SCHEMA_VALIDATION",
                        String.join("; ", errors));
            }
        }

        @Override
        public Idempotency idempotency() {
            return real.idempotency();
        }

        @Override
        public SideEffectType sideEffectType() {
            return real.sideEffectType();
        }

        @Override
        public SideEffectProtocol sideEffectProtocol() {
            return real.sideEffectProtocol();
        }
    }

    /** Captures one immutable occurrence summary plus every delegate attempt. */
    private static final class ObservedOperator implements Operator<Object, Object> {
        private final NodeSpec node;
        private final InvocationRecorder.InvocationBinding binding;
        private final Operator<Object, Object> delegate;
        private final InvocationRecorder recorder;

        private ObservedOperator(NodeSpec node, InvocationRecorder.InvocationBinding binding,
                                 Operator<Object, Object> delegate, InvocationRecorder recorder) {
            this.node = Objects.requireNonNull(node, "node");
            this.binding = Objects.requireNonNull(binding, "binding");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.recorder = Objects.requireNonNull(recorder, "recorder");
        }

        @Override
        public Object execute(Object input, OperatorContext context) throws Exception {
            recorder.beginAttempt(binding);
            try {
                Instant logicalStart = context.timeSource().now();
                long wallStart = System.nanoTime();
                try {
                    Object output = delegate.execute(input, context);
                    recorder.recordSuccess(binding, node, input, output, context.retryAttempt() + 1,
                            elapsedMillis(logicalStart, context.timeSource().now(), wallStart));
                    return output;
                } catch (Exception failure) {
                    recorder.recordFailure(binding, node, input, failure, context.retryAttempt() + 1,
                            elapsedMillis(logicalStart, context.timeSource().now(), wallStart));
                    throw failure;
                }
            } finally {
                recorder.endAttempt(binding);
            }
        }

        @Override
        public Idempotency idempotency() {
            return delegate.idempotency();
        }

        @Override
        public SideEffectType sideEffectType() {
            return delegate.sideEffectType();
        }

        @Override
        public SideEffectProtocol sideEffectProtocol() {
            return delegate.sideEffectProtocol();
        }

        private static long elapsedMillis(Instant logicalStart, Instant logicalEnd, long wallStart) {
            long logical = Math.max(0, Duration.between(logicalStart, logicalEnd).toMillis());
            long wall = Math.max(0, Duration.ofNanos(System.nanoTime() - wallStart).toMillis());
            return Math.max(logical, wall);
        }
    }

    private static TestControlException controlledFailure(FixtureRule.Behavior behavior,
                                                          String defaultCode) {
        return new TestControlException(behavior.errorCode().isBlank() ? defaultCode : behavior.errorCode(),
                behavior.errorType(), behavior.errorMessage());
    }

    private static HttpResourceOutput resourceOutput(Object input, FixtureRule.Behavior behavior) {
        if (behavior.value() instanceof HttpResourceOutput output) {
            return output;
        }
        HttpResourceInput resourceInput = normalizeResourceInput(input);
        int status = behavior.statusCode() == null ? 200 : behavior.statusCode();
        return new HttpResourceOutput(resourceInput.resourceId(), status, behavior.value(),
                behavior.rawBody(), Duration.ZERO, status >= 200 && status < 300);
    }

    private static HttpResourceInput normalizeResourceInput(Object input) {
        if (input instanceof HttpResourceInput typed) {
            return typed;
        }
        if (!(input instanceof Map<?, ?> map)) {
            throw new TestControlException("FIXTURE_INPUT_INVALID", "RESOURCE_FIXTURE",
                    "httpResource fixture input must be a map or HttpResourceInput.");
        }
        Object resourceId = map.get("resourceId");
        Map<String, Object> params = new LinkedHashMap<>();
        if (map.get("params") instanceof Map<?, ?> rawParams) {
            rawParams.forEach((key, value) -> params.put(String.valueOf(key), value));
        }
        return new HttpResourceInput(resourceId == null ? "" : String.valueOf(resourceId), params);
    }

}
