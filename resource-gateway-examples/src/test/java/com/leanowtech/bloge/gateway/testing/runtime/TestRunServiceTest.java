package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TestRunServiceTest {

    private static final String TARGET = "sha256:" + "b".repeat(64);
    private final TestRunService service = new TestRunService(
            new DefaultOperatorRegistry(), new ObjectMapper(), null);

    @Test
    void realPureOperatorRunsOnIndependentEngineAndProducesTrace() {
        Graph graph = single(new PureOperator());

        TestExecutionResult result = service.execute(request(graph, bundle()));

        assertThat(result.passed()).isTrue();
        assertThat(result.graphResult().getOutput("subject", String.class)).isEqualTo("real:hello");
        assertThat(result.evidence().nodeTrace()).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("SUCCESS");
            assertThat(trace.fidelity()).isEqualTo("REAL");
        });
        assertThat(result.evidence().evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
    }

    @Test
    void returnDoubleIsSchemaGatedConsumedAndMarkedOutputLevel() {
        Graph graph = single(new PureOperator());
        FixtureRule rule = rule("fixed", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("fixture"));

        TestExecutionResult result = service.execute(request(graph, bundle(rule)));

        assertThat(result.passed()).isTrue();
        assertThat(result.graphResult().getOutput("subject", String.class)).isEqualTo("fixture");
        assertThat(result.evidence().nodeTrace()).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("MOCKED");
            assertThat(trace.fidelity()).isEqualTo("OUTPUT_LEVEL");
        });
        assertThat(result.evidence().fixtureConsumptions()).singleElement().satisfies(consumption -> {
            assertThat(consumption.uses()).isEqualTo(1);
            assertThat(consumption.status()).isEqualTo("SATISFIED");
        });
    }

    @Test
    void standardizedThrowAndDenyNeverExecuteRealBinding() {
        AtomicInteger calls = new AtomicInteger();
        Graph graph = single(new CountingExternalOperator(calls));
        FixtureRule throwRule = rule("fault", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.throwing("UPSTREAM_BAD", "UPSTREAM", "injected"));

        TestExecutionResult thrown = service.execute(request(graph, bundle(throwRule)));

        assertThat(thrown.evidence().status()).isEqualTo(TestRunEvidence.Status.EXECUTION_FAILED);
        assertThat(thrown.evidence().nodeTrace()).singleElement()
                .satisfies(trace -> assertThat(trace.errorCode()).isEqualTo("UPSTREAM_BAD"));
        assertThat(calls).hasValue(0);

        FixtureRule denyRule = rule("deny", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.deny("SIDE_EFFECT_DENIED", "must not call"));
        TestExecutionResult denied = service.execute(request(graph, bundle(denyRule)));
        assertThat(denied.evidence().nodeTrace()).singleElement()
                .satisfies(trace -> assertThat(trace.errorCode()).isEqualTo("SIDE_EFFECT_DENIED"));
        assertThat(calls).hasValue(0);
    }

    @Test
    void missingExternalFixtureFailsClosedWithoutTouchingExternalOperator() {
        AtomicInteger calls = new AtomicInteger();

        TestExecutionResult result = service.execute(request(
                single(new CountingExternalOperator(calls)), bundle()));

        assertThat(result.evidence().status()).isEqualTo(TestRunEvidence.Status.FIXTURE_UNMATCHED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void requiredFixtureSkippedByFailedUpstreamIsFixtureUnused() {
        Operator<Object, Object> failure = new PureOperator() {
            @Override
            public Object execute(Object input, OperatorContext ctx) {
                throw new IllegalStateException("upstream failed");
            }
        };
        GraphBuilder builder = new GraphBuilder("unused-fixture");
        Graph graph = builder.node("first", failure)
                .node("subject", new PureOperator()).dependsOn("first")
                .build();
        FixtureRule fixture = rule("required", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("never"));

        TestExecutionResult result = service.execute(request(graph, bundle(fixture)));

        assertThat(result.evidence().status()).isEqualTo(TestRunEvidence.Status.FIXTURE_UNUSED);
        assertThat(result.evidence().fixtureConsumptions()).singleElement()
                .satisfies(consumption -> assertThat(consumption.status()).isEqualTo("UNUSED"));
    }

    @Test
    void storedOutputLevelResourceFixtureCannotBecomeCertifiable() {
        Graph graph = withOperatorRef(single(new CountingExternalOperator(new AtomicInteger())),
                "httpResource");
        FixtureRule fixture = rule("resource", FixtureRule.Selector.resource("customer.get"),
                FixtureRule.Behavior.returning(Map.of("id", "C-1")));
        TestExecutionRequest request = new TestExecutionRequest(graph,
                new GraphContext(Map.of("input", Map.of(
                        "resourceId", "customer.get", "params", Map.of()))), bundle(fixture),
                "GRAPH_CONTRACT_TEST", TARGET, TestExecutionRequest.FixtureSource.STORED, Map.of());

        TestExecutionResult result = service.execute(request);

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence().evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(result.evidence().nodeTrace()).singleElement()
                .satisfies(trace -> assertThat(trace.fidelity()).isEqualTo("OUTPUT_LEVEL"));
    }

    @Test
    void storedDelayedResourceValueRemainsOutputLevelAndExploratory() {
        Graph graph = withOperatorRef(single(new CountingExternalOperator(new AtomicInteger())),
                "httpResource");
        FixtureRule fixture = rule("delayed-resource", FixtureRule.Selector.resource("customer.get"),
                FixtureRule.Behavior.delayed(Duration.ofSeconds(2), Map.of("id", "C-1")));
        FixtureBundle bundle = logicalBundle(Instant.parse("2026-07-15T09:00:00Z"), fixture);
        TestExecutionRequest request = new TestExecutionRequest(graph,
                new GraphContext(Map.of("input", Map.of(
                        "resourceId", "customer.get", "params", Map.of()))), bundle,
                "GRAPH_CONTRACT_TEST", TARGET, TestExecutionRequest.FixtureSource.STORED, Map.of());

        TestExecutionResult result = service.execute(request);

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(result.evidence().nodeTrace()).singleElement()
                .satisfies(trace -> assertThat(trace.fidelity()).isEqualTo("OUTPUT_LEVEL"));
    }

    @Test
    void nodeSelectedOutputLevelResourceFixtureAlsoCannotBecomeCertifiable() {
        Graph graph = withOperatorRef(single(new CountingExternalOperator(new AtomicInteger())),
                "httpResource");
        FixtureRule fixture = rule("resource-by-node", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of("id", "C-1")));
        TestExecutionRequest request = new TestExecutionRequest(graph,
                new GraphContext(Map.of("input", Map.of(
                        "resourceId", "customer.get", "params", Map.of()))), bundle(fixture),
                "GRAPH_CONTRACT_TEST", TARGET, TestExecutionRequest.FixtureSource.STORED, Map.of());

        TestExecutionResult result = service.execute(request);

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
    }

    @Test
    void storedFixtureCannotCertifyATargetWithoutRecoverableArtifactEvidence() {
        Graph graph = single(new PureOperator());
        TestExecutionRequest request = new TestExecutionRequest(graph,
                new GraphContext(Map.of("input", "hello")), bundle(),
                "GRAPH_CONTRACT_TEST", TARGET, TestExecutionRequest.FixtureSource.STORED,
                Map.of("targetCertificationGap", "definition-source-missing"), false);

        TestExecutionResult result = service.execute(request);

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
    }

    @Test
    void independentEngineConfigurationHasNoProductionCrossCuttingComponents() {
        IndependentTestEngineFactory.Configuration configuration = service.engineConfiguration();

        assertThat(configuration.interceptorTypes()).isEmpty();
        assertThat(configuration.listenerTypes()).containsExactly(InvocationRecorder.class.getName());
        assertThat(configuration.durableStores()).isFalse();
        assertThat(configuration.productionContextCarriers()).isFalse();
        assertThat(configuration.productionExtensionListeners()).isFalse();
    }

    @Test
    void planRejectionIsReturnedAsEvidenceWithoutStartingGraph() {
        FixtureRule first = rule("first", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("a"));
        FixtureRule second = rule("second", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("b"));

        TestExecutionResult result = service.execute(request(single(new PureOperator()),
                bundle(first, second)));

        assertThat(result.graphResult()).isNull();
        assertThat(result.plan()).isNull();
        assertThat(result.evidence().status())
                .isEqualTo(TestRunEvidence.Status.CONTROL_PLAN_REJECTED);
        assertThat(result.evidence().diagnostics()).anyMatch(item -> item.contains("same-precedence"));
    }

    @Test
    void numericToleranceAssertionsAreEvaluatedBySharedKernel() {
        Operator<Object, Object> score = new PureOperator() {
            @Override
            public Object execute(Object input, OperatorContext ctx) {
                return Map.of("score", 0.30000001);
            }
        };
        FixtureBundle.Assertion assertion = new FixtureBundle.Assertion(
                "OUTPUT_PATH", "subject", "/score", "EQUALS", 0.3, 0.0001);
        FixtureBundle passingBundle = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "numeric", 1, TARGET, "INTERNAL", null, null,
                List.of(), List.of(assertion), Map.of());

        TestExecutionResult passing = service.execute(request(single(score), passingBundle));

        assertThat(passing.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
        assertThat(passing.evidence().assertionResults()).singleElement()
                .satisfies(item -> assertThat(item.passed()).isTrue());

        FixtureBundle failingBundle = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "numeric-fail", 1, TARGET, "INTERNAL", null, null, List.of(),
                List.of(new FixtureBundle.Assertion(
                        "OUTPUT_PATH", "subject", "/score", "EQUALS", 0.4, 0.0001)), Map.of());
        TestExecutionResult failing = service.execute(request(single(score), failingBundle));
        assertThat(failing.evidence().status()).isEqualTo(TestRunEvidence.Status.ASSERTION_FAILED);
    }

    @Test
    void jsonSchemaAssertionsAreEvaluatedInsideTheEvidenceKernel() {
        Operator<Object, Object> score = new PureOperator() {
            @Override
            public Object execute(Object input, OperatorContext ctx) {
                return Map.of("score", 780, "decision", "APPROVE");
            }
        };
        Map<String, Object> matchingSchema = Map.of(
                "type", "object",
                "required", List.of("score", "decision"),
                "properties", Map.of(
                        "score", Map.of("type", "integer"),
                        "decision", Map.of("type", "string")));
        FixtureBundle matching = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "schema-match", 1, TARGET, "INTERNAL", null, null, List.of(),
                List.of(new FixtureBundle.Assertion(
                        "OUTPUT_PATH", "subject", "", "MATCHES_SCHEMA", matchingSchema, null)),
                Map.of());

        TestExecutionResult passed = service.execute(request(single(score), matching));

        assertThat(passed.evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
        assertThat(passed.evidence().assertionResults()).singleElement()
                .satisfies(assertion -> assertThat(assertion.passed()).isTrue());

        FixtureBundle mismatching = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "schema-mismatch", 1, TARGET, "INTERNAL", null, null, List.of(),
                List.of(new FixtureBundle.Assertion(
                        "OUTPUT_PATH", "subject", "", "MATCHES_SCHEMA",
                        Map.of("type", "string"), null)), Map.of());
        TestExecutionResult failed = service.execute(request(single(score), mismatching));
        assertThat(failed.evidence().status()).isEqualTo(TestRunEvidence.Status.ASSERTION_FAILED);
    }

    @Test
    void graphFailureTakesPrecedenceOverAssertionsThatCannotBeEvaluated() {
        Operator<Object, Object> failure = new PureOperator() {
            @Override
            public Object execute(Object input, OperatorContext ctx) {
                throw new IllegalStateException("graph failed");
            }
        };
        FixtureBundle.Assertion assertion = new FixtureBundle.Assertion(
                "OUTPUT_PATH", "subject", "/decision", "EQUALS", "APPROVE", null);
        FixtureBundle fixture = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "failed", 1,
                TARGET, "INTERNAL", null, null, List.of(), List.of(assertion), Map.of());

        TestExecutionResult result = service.execute(request(single(failure), fixture));

        assertThat(result.evidence().status()).isEqualTo(TestRunEvidence.Status.EXECUTION_FAILED);
        assertThat(result.evidence().assertionResults()).singleElement()
                .satisfies(value -> assertThat(value.passed()).isFalse());
    }

    @Test
    void spyEvidenceDistinguishesControlModeAndCapturesSanitizedSideEffectIntent() {
        Operator<Object, Object> observed = new PureOperator() {
            @Override
            public Object execute(Object input, OperatorContext ctx) {
                try (var attempt = ctx.beginSideEffect(
                        "customer.lookup", "secret-idempotency-key", "lookup-reconciler", "lookup-42")) {
                    attempt.notCommitted("READ_ONLY_PROBE");
                }
                return "observed:" + input;
            }
        };
        FixtureRule spy = rule("observe", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.spy());

        TestExecutionResult result = service.execute(request(single(observed), bundle(spy)));

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence().metadata().get("nodeControlModes"))
                .isEqualTo(Map.of("subject", "SPY"));
        assertThat((List<?>) result.evidence().metadata().get("sideEffectIntents"))
                .singleElement().satisfies(snapshot -> {
                    String serialized = String.valueOf(snapshot);
                    assertThat(serialized).contains("customer.lookup", "sha256:", "NOT_COMMITTED");
                    assertThat(serialized).doesNotContain("secret-idempotency-key");
                });
    }

    @Test
    void selectorCorrelationKeyParticipatesInRuntimeMatching() {
        FixtureRule.Selector selector = new FixtureRule.Selector(
                "/root", "subject", "", "", "", List.of(), List.of(),
                InvocationSite.InvocationKind.PRIMARY,
                List.of(), List.of(), "case-42", FixtureRule.Match.none());
        FixtureRule fixture = rule("correlated", selector, FixtureRule.Behavior.returning("matched"));
        Graph graph = single(new PureOperator());
        TestExecutionRequest request = new TestExecutionRequest(graph,
                new GraphContext(Map.of("input", Map.of("correlationKey", "case-42"))),
                bundle(fixture), "GRAPH_CONTRACT_TEST", TARGET,
                TestExecutionRequest.FixtureSource.INLINE, Map.of());

        TestExecutionResult result = service.execute(request);

        assertThat(result.passed()).isTrue();
        assertThat(result.graphResult().getOutput("subject", String.class)).isEqualTo("matched");
    }

    @Test
    @Timeout(2)
    void delayAdvancesRunLogicalTimeWithoutWaitingOnTheWallClock() {
        Instant origin = Instant.parse("2026-07-15T09:00:00Z");
        FixtureRule fixture = rule("delayed", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.delayed(Duration.ofDays(30), "after-delay"));

        TestExecutionResult result = service.execute(request(single(new PureOperator()),
                logicalBundle(origin, fixture)));

        assertThat(result.passed()).isTrue();
        assertThat(result.graphResult().getOutput("subject", String.class)).isEqualTo("after-delay");
        assertThat(result.evidence().metadata().get("logicalTime"))
                .isEqualTo(Map.of(
                        "mode", "ADVANCING_ZERO_WALL_CLOCK",
                        "origin", "2026-07-15T09:00:00Z",
                        "current", "2026-08-14T09:00:00Z",
                        "elapsedMs", Duration.ofDays(30).toMillis()));
        assertThat(result.evidence().metadata().get("nodeControlModes"))
                .isEqualTo(Map.of("subject", "DELAY"));
    }

    @Test
    void timeoutProducesNormalizedTerminalEvidenceAndCustomErrorCode() {
        FixtureRule fixture = rule("timeout", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.timeout(Duration.ofSeconds(4),
                        "CREDIT_BUREAU_TIMEOUT", "bureau did not answer"));

        TestExecutionResult result = service.execute(request(single(new PureOperator()),
                logicalBundle(Instant.parse("2026-07-15T09:00:00Z"), fixture)));

        assertThat(result.evidence().status()).isEqualTo(TestRunEvidence.Status.TIMED_OUT);
        assertThat(result.evidence().nodeTrace()).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("TIMEOUT");
            assertThat(trace.errorCode()).isEqualTo("CREDIT_BUREAU_TIMEOUT");
            assertThat(trace.fidelity()).isEqualTo("OUTPUT_LEVEL");
        });
        assertThat(result.evidence().metadata().get("logicalTime").toString())
                .contains("elapsedMs=4000");
    }

    @Test
    void timeoutFixtureExercisesRealRetryBackoffAndFallbackChainDeterministically() {
        GraphBuilder builder = new GraphBuilder("timeout-retry-fallback");
        Graph graph = builder.node("subject", new PureOperator())
                .input((results, context) -> context.get("input"))
                .retry(1, Duration.ofSeconds(2))
                .fallback(() -> "safe-fallback")
                .build();
        FixtureRule fixture = new FixtureRule(FixtureRule.SCHEMA_VERSION, "timeout-twice",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.timeout(Duration.ofSeconds(3)),
                new FixtureRule.Consumption(true, 2, 2, FixtureRule.ExhaustedAction.FAIL,
                        FixtureRule.UnmatchedAction.FAIL), FixtureRule.SchemaCheck.strict());

        TestExecutionResult result = service.execute(request(graph,
                logicalBundle(Instant.parse("2026-07-15T09:00:00Z"), fixture)));

        assertThat(result.passed()).isTrue();
        assertThat(result.graphResult().getOutput("subject", String.class)).isEqualTo("safe-fallback");
        assertThat(result.evidence().fixtureConsumptions()).singleElement()
                .satisfies(consumption -> assertThat(consumption.uses()).isEqualTo(2));
        assertThat(result.evidence().nodeTrace()).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("MOCKED");
            assertThat(trace.output()).isEqualTo("safe-fallback");
        });
        assertThat(result.evidence().metadata().get("logicalTime").toString())
                .contains("elapsedMs=8000");
    }

    private static Graph single(Operator<Object, Object> operator) {
        GraphBuilder builder = new GraphBuilder("single");
        return builder.node("subject", operator)
                .input((results, context) -> context.get("input"))
                .build();
    }

    private static Graph withOperatorRef(Graph graph, String operatorRef) {
        var node = graph.nodes().get("subject").toBuilder().operatorRef(operatorRef).build();
        return new Graph(graph.name(), Map.of("subject", node), graph.edges(), graph.sourceNodes(),
                graph.terminalNodes(), graph.schemaValidationLevel(), graph.embeddedOperators(),
                graph.declaredInputSchema(), graph.declaredOutputSchema(), graph.sagaConfig(),
                graph.definitionSource(), graph.streamingOutputNodeId(), graph.streamingInputs());
    }

    private static TestExecutionRequest request(Graph graph, FixtureBundle bundle) {
        return new TestExecutionRequest(graph, new GraphContext(Map.of("input", "hello")), bundle,
                "GRAPH_CONTRACT_TEST", TARGET, TestExecutionRequest.FixtureSource.INLINE, Map.of());
    }

    private static FixtureBundle bundle(FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1, TARGET,
                "INTERNAL", null, null, List.of(rules), List.of(), Map.of());
    }

    private static FixtureBundle logicalBundle(Instant origin, FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "logical-fixture", 1, TARGET,
                "INTERNAL", origin, null, List.of(rules), List.of(), Map.of());
    }

    private static FixtureRule rule(String id, FixtureRule.Selector selector,
                                    FixtureRule.Behavior behavior) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, id, selector, behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static class PureOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            return "real:" + input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static final class CountingExternalOperator implements Operator<Object, Object> {
        private final AtomicInteger calls;

        private CountingExternalOperator(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Object execute(Object input, OperatorContext ctx) {
            calls.incrementAndGet();
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.EXTERNAL_CALL;
        }
    }
}
