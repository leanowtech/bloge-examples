package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.test.MockOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeStartEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for browser-submitted gateway DSL execution.
 */
class DynamicGatewayComposerServiceTest {

    private static final String DECISION_TABLE_DSL = """
            graph customLoanPolicy {
              decision_table loanPolicy(
                score  = ctx.score,
                amount = ctx.amount
              ) hit=unique -> { decision: String, rate: Decimal, maxTerm: Int, reviewLane: String, ruleId: String } {
                rule (score: score >= 760, amount: amount <= 500000)       -> { decision: "approved", rate: 3.5,  maxTerm: 360, reviewLane: "auto-approve",       ruleId: "R1" }
                rule (score: 700 <= score < 760, amount: amount <= 300000) -> { decision: "approved", rate: 4.5,  maxTerm: 300, reviewLane: "standard",           ruleId: "R2" }
                rule (score: 650 <= score < 700, amount: amount <= 200000) -> { decision: "manual_review", rate: 5.75, maxTerm: 240, reviewLane: "senior-underwriter", ruleId: "R3" }
                otherwise                                                  -> { decision: "declined", rate: 0.0,  maxTerm: 0,   reviewLane: "decline",            ruleId: "R4" }
              }

              transform response {
                applicant       = { score: ctx.score, segment: ctx.segment }
                requestedAmount = ctx.amount
                policy          = loanPolicy.output
              }
            }
            """;

    private final DynamicGatewayComposerService service =
            new DynamicGatewayComposerService(MockOperator.returning(null));

    @Test
    void runsSubmittedDecisionTableAndReturnsVisualModels() {
        DynamicGraphRunResponse response = service.run(new DynamicGraphRunRequest(
                DECISION_TABLE_DSL,
                Map.of("score", 670, "amount", 180_000, "segment", "existing"),
                "response"
        ));

        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.graphName()).isEqualTo("customLoanPolicy");
        assertThat(response.outputNode()).isEqualTo("response");
        assertThat(response.layout()).isNotNull();
        assertThat(response.layout().rootId()).isEqualTo("customLoanPolicy");
        assertThat(response.layout().nodes())
                .extracting(ExampleVisualLayout.Node::kind)
                .contains("decision-table", "transform");

        assertThat(response.decisionTable()).isNotNull();
        assertThat(response.decisionTable().hitPolicy()).isEqualTo("unique");
        assertThat(response.decisionTable().rows())
                .extracting(GatewayDecisionTable.Row::id)
                .containsExactly("R1", "R2", "R3", "R4");

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.output();
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) output.get("policy");
        assertThat(policy)
                .containsEntry("decision", "manual_review")
                .containsEntry("ruleId", "R3");
        assertThat(response.nodeAttempts()).containsKeys("loanPolicy", "response");
        assertThat(response.nodeAttempts().get("loanPolicy")).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo("SUCCESS");
            assertThat(attempt.input()).isEqualTo(Map.of("score", 670, "amount", 180_000));
            assertThat(attempt.output()).isEqualTo(policy);
            assertThat(attempt.startedAt()).isNotNull();
        });
        assertThat(response.nodeExecutionFacts()).containsKeys("loanPolicy", "response");
        assertThat(response.nodeExecutionFacts().get("loanPolicy")).satisfies(fact -> {
            assertThat(fact.status()).isEqualTo("SUCCESS");
            assertThat(fact.reasonCode()).isEqualTo("NONE");
            assertThat(fact.observationSource()).isEqualTo("ENGINE_STATUS");
            assertThat(fact.retry().configuredMaxAttempts()).isEqualTo(1);
        });
    }

    @Test
    void capturesRetryAndFallbackFromEngineEvents() {
        AtomicInteger calls = new AtomicInteger();
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("unstable", MockOperator.of(input -> {
            calls.incrementAndGet();
            throw new IllegalStateException("provider unavailable");
        }));
        DynamicGatewayComposerService resilientService = new DynamicGatewayComposerService(registry);

        DynamicGraphRunResponse response = resilientService.run(new DynamicGraphRunRequest("""
                graph resilientGraph {
                  node guarded : unstable {
                    input { value = ctx.value }
                    retry = { attempts: 2, backoff: 1ms }
                    fallback = { value: "degraded" }
                  }
                }
                """, Map.of("value", "request"), "guarded"));

        assertThat(response.success()).isTrue();
        assertThat(calls).hasValue(3);
        assertThat(response.nodeExecutionFacts().get("guarded")).satisfies(fact -> {
            assertThat(fact.status()).isEqualTo("FALLBACK");
            assertThat(fact.reasonCode()).isEqualTo("FALLBACK_SUCCEEDED");
            assertThat(fact.retry().configuredMaxAttempts()).isEqualTo(3);
            assertThat(fact.retry().observedAttempts()).isEqualTo(3);
            assertThat(fact.retry().exhausted()).isTrue();
            assertThat(fact.fallback().configured()).isTrue();
            assertThat(fact.fallback().used()).isTrue();
            assertThat(fact.fallback().strategy()).isEqualTo("FIXED_VALUE");
            assertThat(fact.events()).extracting(DynamicGraphRunResponse.Event::type)
                    .containsExactly("RETRY_SCHEDULED", "RETRY_SCHEDULED", "RETRY_EXHAUSTED", "FALLBACK");
        });
    }

    @Test
    void capturesTimeoutAsAnEngineEventWithoutParsingErrorText() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("slow", (com.leanowtech.bloge.core.operator.Operator<Object, Object>) (input, ctx) -> {
            Thread.sleep(50);
            return Map.of("value", "late");
        });
        DynamicGatewayComposerService timeoutService = new DynamicGatewayComposerService(registry);

        DynamicGraphRunResponse response = timeoutService.run(new DynamicGraphRunRequest("""
                graph timeoutGraph {
                  node guarded : slow {
                    input { value = ctx.value }
                    timeout = 5ms
                  }
                }
                """, Map.of("value", "request"), "guarded"));

        assertThat(response.success()).isFalse();
        assertThat(response.nodeExecutionFacts().get("guarded")).satisfies(fact -> {
            assertThat(fact.status()).isEqualTo("TIMEOUT");
            assertThat(fact.reasonCode()).isEqualTo("NODE_TIMEOUT");
            assertThat(fact.timeout().configured()).isTrue();
            assertThat(fact.timeout().configuredTimeoutMs()).isEqualTo(5);
            assertThat(fact.timeout().observed()).isTrue();
            assertThat(fact.events()).extracting(DynamicGraphRunResponse.Event::type).containsExactly("TIMEOUT");
        });
    }

    @Test
    void quarantinesAmbiguousResilienceCorrelationInsteadOfCrossLinkingConcurrentRuns() {
        Graph graph = Graph.builder("sameGraph")
                .node("guarded", MockOperator.returning(Map.of("ok", true)))
                    .timeout(Duration.ofMillis(10))
                    .fallback(() -> Map.of("ok", false))
                .build();
        NodeExecutionCaptureInterceptor capture = new NodeExecutionCaptureInterceptor();
        capture.begin("capture-a", graph);
        capture.begin("capture-b", graph);
        GraphContext first = new GraphContext(new TenantContext("tenant-a", "test"));
        first.put(NodeExecutionCaptureInterceptor.CAPTURE_ID_CONTEXT_KEY, "capture-a");
        GraphContext second = new GraphContext(new TenantContext("tenant-a", "test"));
        second.put(NodeExecutionCaptureInterceptor.CAPTURE_ID_CONTEXT_KEY, "capture-b");
        capture.onNodeStart(new NodeStartEvent("sameGraph", "guarded", graph.nodes().get("guarded"), null, first));
        capture.onNodeStart(new NodeStartEvent("sameGraph", "guarded", graph.nodes().get("guarded"), null, second));

        capture.onNodeFallback("sameGraph", "guarded", new IllegalStateException("ambiguous"));

        assertThat(capture.complete("capture-a", null).facts().get("guarded")).satisfies(fact -> {
            assertThat(fact.reasonCode()).isEqualTo("RESILIENCE_EVENT_CORRELATION_AMBIGUOUS");
            assertThat(fact.observationSource()).isEqualTo("ENGINE_STATUS_WITH_EVENT_GAP");
            assertThat(fact.fallback().used()).isFalse();
        });
        assertThat(capture.complete("capture-b", null).facts().get("guarded")).satisfies(fact -> {
            assertThat(fact.reasonCode()).isEqualTo("RESILIENCE_EVENT_CORRELATION_AMBIGUOUS");
            assertThat(fact.observationSource()).isEqualTo("ENGINE_STATUS_WITH_EVENT_GAP");
        });
    }

    @Test
    void returnsDiagnosticsWhenSubmittedDslDoesNotCompile() {
        DynamicGraphRunResponse response = service.run(new DynamicGraphRunRequest(
                "graph broken { decision_table }",
                Map.of(),
                ""
        ));

        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.diagnostics()).isNotEmpty();
        assertThat(response.errors()).isNotEmpty();
    }
}
