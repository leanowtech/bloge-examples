package com.leanowtech.bloge.gateway.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.test.MockOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.TenantContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.event.NodeEvent.NodeStartEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.time.Instant;

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
    void enforcesGraphDeadlineAndConfirmsCooperativeTermination() {
        CountDownLatch started = new CountDownLatch(1);
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("slow", (com.leanowtech.bloge.core.operator.Operator<Object, Object>) (input, ctx) -> {
            started.countDown();
            Thread.sleep(10_000);
            return input;
        });
        DynamicGatewayComposerService controlled = new DynamicGatewayComposerService(registry);

        DynamicGraphRunResponse response = controlled.run(new DynamicGraphRunRequest(simpleNodeDsl(),
                Map.of("value", "request"), "work",
                new DynamicRunIntent("", "deadline-run", Instant.now().plusMillis(300), "fence-deadline", 1_000)));

        assertThat(started.getCount()).isZero();
        assertThat(response.success()).isFalse();
        assertThat(response.runControl().status()).isEqualTo("TIMED_OUT");
        assertThat(response.runControl().reasonCode()).isEqualTo("GRAPH_DEADLINE_TERMINATED");
        assertThat(response.runControl().terminationConfirmed()).isTrue();
        assertThat(response.runControl().sideEffectsMayBeInFlight()).isFalse();
        assertThat(response.runControl().engineExecutionId()).isNotBlank();
    }

    @Test
    void propagatesDeadlineAndFinalizationReserveIntoOperatorContext() {
        AtomicReference<Duration> observedBudget = new AtomicReference<>();
        AtomicReference<Instant> observedDeadline = new AtomicReference<>();
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("budgetProbe",
                (com.leanowtech.bloge.core.operator.Operator<Object, Object>) (input, ctx) -> {
                    observedBudget.set(ctx.remainingBudget().orElseThrow());
                    observedDeadline.set(ctx.deadlineAt().orElseThrow());
                    return input;
                });
        DynamicGatewayComposerService controlled = new DynamicGatewayComposerService(
                registry, new ObjectMapper(), new InMemoryDynamicRunControlRepository(), 100);
        Instant deadline = Instant.now().plusSeconds(2);

        DynamicGraphRunResponse response = controlled.run(new DynamicGraphRunRequest("""
                graph budgetGraph {
                  node work : budgetProbe { input { value = ctx.value } }
                }
                """, Map.of("value", "request"), "work",
                new DynamicRunIntent("", "budget-run", deadline, "fence-budget", 1_000)));

        assertThat(response.success()).isTrue();
        assertThat(observedDeadline.get()).isEqualTo(deadline);
        assertThat(observedBudget.get()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(1_900));
    }

    @Test
    void exhaustedDeadlineIsCapturedAsEngineAdmissionFactWithoutInvokingOperator() {
        AtomicInteger invocations = new AtomicInteger();
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("budgetProbe",
                (com.leanowtech.bloge.core.operator.Operator<Object, Object>) (input, ctx) -> {
                    invocations.incrementAndGet();
                    return input;
                });
        DynamicGatewayComposerService controlled = new DynamicGatewayComposerService(
                registry, new ObjectMapper(), new InMemoryDynamicRunControlRepository(), 2_000);

        DynamicGraphRunResponse response = controlled.run(new DynamicGraphRunRequest("""
                graph expiredBudgetGraph {
                  node work : budgetProbe { input { value = ctx.value } }
                }
                """, Map.of("value", "request"), "work",
                new DynamicRunIntent("", "expired-budget-run", Instant.now().plusSeconds(1),
                        "fence-expired-budget", 1_000)));

        assertThat(response.success()).isFalse();
        assertThat(invocations).hasValue(0);
        assertThat(response.nodeExecutionFacts().get("work")).satisfies(fact -> {
            assertThat(fact.status()).isEqualTo("CANCELLED");
            assertThat(fact.reasonCode()).isEqualTo("DEADLINE_EXHAUSTED");
            assertThat(fact.observationSource()).isEqualTo("ENGINE_ADMISSION");
            assertThat(fact.events()).extracting(DynamicGraphRunResponse.Event::type)
                    .containsExactly("DEADLINE_EXHAUSTED");
        });
    }

    @Test
    void acceptsFencedUserCancellationAndRejectsWrongFenceAndStaleRevision() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("slow", (com.leanowtech.bloge.core.operator.Operator<Object, Object>) (input, ctx) -> {
            started.countDown();
            Thread.sleep(10_000);
            return input;
        });
        DynamicGatewayComposerService controlled = new DynamicGatewayComposerService(registry);
        DynamicGraphRunRequest request = new DynamicGraphRunRequest(simpleNodeDsl(), Map.of("value", "request"),
                "work", new DynamicRunIntent("", "cancel-run", null, "fence-cancel", 1_000));

        CompletableFuture<DynamicGraphRunResponse> response = CompletableFuture.supplyAsync(() -> controlled.run(request));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(controlled.runControl("cancel-run", "wrong-fence")).satisfies(result -> {
            assertThat(result.accepted()).isFalse();
            assertThat(result.code()).isEqualTo("RG.RUN_CONTROL.FENCE_MISMATCH");
        });
        DynamicRunControlResult current = controlled.runControl("cancel-run", "fence-cancel");
        assertThat(current.accepted()).isTrue();
        assertThat(controlled.cancel(new DynamicRunControlCommand("cancel-run", "fence-cancel",
                current.control().revision() + 1, "stale command"))).satisfies(result -> {
            assertThat(result.accepted()).isFalse();
            assertThat(result.code()).isEqualTo("RG.RUN_CONTROL.REVISION_CONFLICT");
        });
        assertThat(controlled.cancel(new DynamicRunControlCommand("cancel-run", "fence-cancel",
                current.control().revision(), "author cancelled"))).satisfies(result -> {
            assertThat(result.accepted()).isTrue();
            assertThat(result.control().status()).isEqualTo("CANCEL_REQUESTED");
        });

        DynamicGraphRunResponse cancelled = response.get(3, TimeUnit.SECONDS);
        assertThat(cancelled.success()).isFalse();
        assertThat(cancelled.runControl().status()).isEqualTo("CANCELLED");
        assertThat(cancelled.runControl().terminationConfirmed()).isTrue();
    }

    @RepeatedTest(10)
    void observesCancellationPersistedByAnotherServiceInstance() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("slow", (com.leanowtech.bloge.core.operator.Operator<Object, Object>) (input, ctx) -> {
            started.countDown();
            Thread.sleep(10_000);
            return input;
        });
        var dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        DatabaseDynamicRunControlRepository repository =
                new DatabaseDynamicRunControlRepository(jdbc, transactionManager);
        repository.init();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DynamicGatewayComposerService owner = new DynamicGatewayComposerService(registry, objectMapper, repository);
        DynamicGatewayComposerService remote = new DynamicGatewayComposerService(registry, objectMapper,
                new DatabaseDynamicRunControlRepository(jdbc, transactionManager));
        DynamicGraphRunRequest request = new DynamicGraphRunRequest(simpleNodeDsl(), Map.of("value", "request"),
                "work", new DynamicRunIntent("", "cross-instance-run", null, "shared-fence", 1_000));

        CompletableFuture<DynamicGraphRunResponse> response = CompletableFuture.supplyAsync(() -> owner.run(request));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        DynamicRunControlResult current = remote.runControl("cross-instance-run", "shared-fence");

        assertThat(current.accepted()).isTrue();
        assertThat(remote.cancel(new DynamicRunControlCommand("cross-instance-run", "shared-fence",
                current.control().revision(), "remote operator"))).satisfies(result -> {
                    assertThat(result.accepted()).isTrue();
                    assertThat(result.control().status()).isEqualTo("CANCEL_REQUESTED");
                });

        DynamicGraphRunResponse cancelled = response.get(3, TimeUnit.SECONDS);
        assertThat(cancelled.runControl().status()).isEqualTo("CANCELLED");
        assertThat(cancelled.runControl().terminationConfirmed()).isTrue();
        assertThat(remote.runControl("cross-instance-run", "shared-fence").control().status())
                .isEqualTo("CANCELLED");
    }

    @Test
    void reportsUnconfirmedTerminationWhileAnOperatorIgnoresInterrupts() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.registerRaw("slow", (com.leanowtech.bloge.core.operator.Operator<Object, Object>) (input, ctx) -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // Deliberately violates cooperative cancellation to prove fail-closed reporting.
                }
            }
            return input;
        });
        DynamicGatewayComposerService controlled = new DynamicGatewayComposerService(registry);
        DynamicGraphRunRequest request = new DynamicGraphRunRequest(simpleNodeDsl(), Map.of("value", "request"),
                "work", new DynamicRunIntent("", "unconfirmed-run", null, "fence-unconfirmed", 50));

        CompletableFuture<DynamicGraphRunResponse> response = CompletableFuture.supplyAsync(() -> controlled.run(request));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(controlled.cancel(new DynamicRunControlCommand("unconfirmed-run", "fence-unconfirmed", 0,
                "stop uncooperative operator")).accepted()).isTrue();

        DynamicGraphRunResponse unconfirmed = response.get(2, TimeUnit.SECONDS);
        assertThat(unconfirmed.runControl().status()).isEqualTo("TERMINATION_UNCONFIRMED");
        assertThat(unconfirmed.runControl().terminationConfirmed()).isFalse();
        assertThat(unconfirmed.runControl().sideEffectsMayBeInFlight()).isTrue();

        release.countDown();
        awaitControlStatus(controlled, "unconfirmed-run", "fence-unconfirmed", "CANCELLED");
        assertThat(controlled.runControl("unconfirmed-run", "fence-unconfirmed").control())
                .satisfies(control -> {
                    assertThat(control.terminationConfirmed()).isTrue();
                    assertThat(control.sideEffectsMayBeInFlight()).isFalse();
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

    private static String simpleNodeDsl() {
        return """
                graph controlledGraph {
                  node work : slow {
                    input { value = ctx.value }
                  }
                }
                """;
    }

    private static void awaitControlStatus(DynamicGatewayComposerService service,
                                           String requestId,
                                           String fence,
                                           String expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(deadline)) {
            if (expected.equals(service.runControl(requestId, fence).control().status())) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(service.runControl(requestId, fence).control().status()).isEqualTo(expected);
    }
}
