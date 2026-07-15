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
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates schema-gated real, fixed, failure, logical-time, denial, and observation controls. */
public class TestDoubleFactory {

    private final ObjectMapper objectMapper;
    private final FixtureMatcher matcher;
    private final ResourceFixtureRuntime resourceRuntime;

    /**
     * @param objectMapper mapper for canonical input matching and schema-visible values
     * @param resourceRuntime optional descriptor-backed protocol runtime; required by raw HTTP fixtures
     */
    public TestDoubleFactory(ObjectMapper objectMapper, ResourceFixtureRuntime resourceRuntime) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.matcher = new FixtureMatcher(objectMapper);
        this.resourceRuntime = resourceRuntime;
    }

    /**
     * Creates one node-scoped controlled operator.
     *
     * @param node frozen node specification
     * @param rules preflight-resolved, pairwise-disjoint candidate rules
     * @param realOperator frozen real binding
     * @param implicitDeny whether missing rules represent fail-closed external-effect policy
     * @param recorder per-run trace and consumption recorder
     * @return operator passed only to the independent test engine
     */
    @SuppressWarnings("unchecked")
    public Operator<Object, Object> create(NodeSpec node, List<FixtureRule> rules,
                                           Object realOperator, boolean implicitDeny,
                                           InvocationRecorder recorder) {
        if (!(realOperator instanceof Operator<?, ?> typed)) {
            throw new IllegalArgumentException("Node '" + node.id()
                    + "' is not a synchronous Operator and cannot use v1 execution control.");
        }
        return new ControlledOperator(node, rules, (Operator<Object, Object>) typed,
                implicitDeny, recorder);
    }

    private final class ControlledOperator implements Operator<Object, Object> {
        private final NodeSpec node;
        private final List<FixtureRule> rules;
        private final Operator<Object, Object> real;
        private final boolean implicitDeny;
        private final InvocationRecorder recorder;

        private ControlledOperator(NodeSpec node, List<FixtureRule> rules,
                                   Operator<Object, Object> real, boolean implicitDeny,
                                   InvocationRecorder recorder) {
            this.node = node;
            this.rules = List.copyOf(rules);
            this.real = real;
            this.implicitDeny = implicitDeny;
            this.recorder = recorder;
        }

        @Override
        public Object execute(Object input, OperatorContext context) throws Exception {
            List<FixtureRule> matched = rules.stream().filter(rule -> matcher.matches(rule, input)).toList();
            if (matched.isEmpty()) {
                if (!implicitDeny && rules.stream().anyMatch(rule -> rule.consumption().onUnmatched()
                        == FixtureRule.UnmatchedAction.ALLOW_REAL)) {
                    recorder.markFidelity(node.id(), "REAL");
                    recorder.markControlMode(node.id(), "REAL");
                    return real.execute(input, context);
                }
                recorder.markFidelity(node.id(), "OUTPUT_LEVEL");
                recorder.markControlMode(node.id(), implicitDeny ? "IMPLICIT_DENY" : "UNMATCHED");
                throw new TestControlException("FIXTURE_UNMATCHED", "FIXTURE_MATCH",
                        "No approved fixture matched invocation site " + node.id() + ".");
            }
            if (matched.size() > 1) {
                throw new TestControlException("CONTROL_PLAN_RUNTIME_AMBIGUITY", "FIXTURE_MATCH",
                        "More than one fixture matched invocation site " + node.id() + ".");
            }
            FixtureRule rule = matched.getFirst();
            int currentUses = recorder.uses(rule.ruleId());
            if (rule.consumption().maxUses() > 0 && currentUses >= rule.consumption().maxUses()) {
                if (rule.consumption().onExhausted() == FixtureRule.ExhaustedAction.FALLBACK_TO_REAL) {
                    recorder.markFidelity(node.id(), "REAL");
                    recorder.markControlMode(node.id(), "REAL");
                    return real.execute(input, context);
                }
                throw new TestControlException("FIXTURE_EXHAUSTED", "FIXTURE_CONSUMPTION",
                        "Fixture rule '" + rule.ruleId() + "' exceeded maxUses.");
            }
            recorder.consume(rule.ruleId());
            return apply(rule, input, context);
        }

        private Object apply(FixtureRule rule, Object input, OperatorContext context) throws Exception {
            FixtureRule.Behavior behavior = rule.behavior();
            recorder.markControlMode(node.id(), behavior.kind().name());
            return switch (behavior.kind()) {
                case REAL -> {
                    recorder.markFidelity(node.id(), "REAL");
                    yield real.execute(input, context);
                }
                case SPY -> {
                    recorder.markFidelity(node.id(), "REAL");
                    yield real.execute(input, context);
                }
                case RETURN -> returnValue(rule, input, context);
                case DELAY -> {
                    context.timeSource().sleep(behavior.after());
                    yield returnValue(rule, input, context);
                }
                case TIMEOUT -> {
                    recorder.markFidelity(node.id(), "OUTPUT_LEVEL");
                    context.timeSource().sleep(behavior.after());
                    throw new OperatorTimeoutException(node.id(), behavior.after(),
                            controlledFailure(behavior, "TEST_TIMEOUT"));
                }
                case THROW -> {
                    recorder.markFidelity(node.id(), "OUTPUT_LEVEL");
                    throw controlledFailure(behavior, "TEST_THROW");
                }
                case DENY -> {
                    recorder.markFidelity(node.id(), "OUTPUT_LEVEL");
                    throw controlledFailure(behavior, "TEST_CONTROL_DENIED");
                }
                case STREAM, REPLAY -> throw new TestControlException(
                        "CONTROL_PLAN_RESERVED_BEHAVIOR", "CONTROL_PLAN",
                        "Behavior " + behavior.kind() + " is reserved in v1.");
            };
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
                recorder.markFidelity(node.id(), fidelity);
                output = resourceRuntime.execute(behavior, input, context);
            } else if ("httpResource".equals(node.operatorRef())) {
                recorder.markFidelity(node.id(), "OUTPUT_LEVEL");
                output = resourceOutput(input, behavior);
            } else {
                recorder.markFidelity(node.id(), "OUTPUT_LEVEL");
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
            SchemaEnvelope schema = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                    node.outputSchema().toMap());
            List<String> errors = VisualSchemaValidator.validateValue(schema, schemaVisible(output), "/output")
                    .stream().filter(diagnostic -> diagnostic.error())
                    .map(diagnostic -> diagnostic.message()).toList();
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

    private Object schemaVisible(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), schemaVisible(item)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::schemaVisible).toList();
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (var component : value.getClass().getRecordComponents()) {
                try {
                    normalized.put(component.getName(),
                            schemaVisible(component.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException ex) {
                    throw new TestControlException("FIXTURE_OUTPUT_NORMALIZATION_FAILED",
                            "SCHEMA_VALIDATION", "Cannot inspect fixture output record.");
                }
            }
            return normalized;
        }
        return value;
    }
}
