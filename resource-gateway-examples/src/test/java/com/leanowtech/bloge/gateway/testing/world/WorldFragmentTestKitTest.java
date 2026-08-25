package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.core.engine.GraphEngine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldFragmentTestKitTest {
    private static final String DECISION_TABLE = """
            graph customerWorld {
              decision_table response(type = ctx.type) hit=first -> String {
                rule (type: type == "vip") -> "priority"
                otherwise -> "standard"
              }
            }
            """;

    @Test
    void executesRenderedRequestAndKeepsTheSameResultAcrossTwentyRealReplays() {
        BlogeFragmentRef fragment = BlogeFragmentRef.frozen("customer-world.bloge", DECISION_TABLE);
        WorldFragmentTestKit testKit = new WorldFragmentTestKit();

        WorldFragmentTestKit.ReplayResult replay = testKit.execute(
                fragment, Map.of("type", "vip"), 20);

        assertThat(replay.response()).isInstanceOf(Map.class);
        Map<?, ?> response = (Map<?, ?>) replay.response();
        assertThat(response.get("value")).isEqualTo("priority");
        assertThat(replay.replayCount()).isEqualTo(20);
        assertThat(replay.responseFingerprint()).isNotBlank();
        assertThat(replay.state()).isEmpty();
        assertThatThrownBy(() -> replay.state().put("balance", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<Object, Object>) response).put("value", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<?, ?> fallback = testKit.execute(fragment, Map.of("type", "ordinary"), Map.class);
        assertThat(fallback.get("value")).isEqualTo("standard");
    }

    @Test
    void firstHitPolicyUsesBlogeDeclarationOrderAsPriority() {
        BlogeFragmentRef fragment = BlogeFragmentRef.frozen("ordered-world.bloge", """
                graph orderedWorld {
                  decision_table response(score = ctx.score) hit=first -> String {
                    rule (score: score >= 0) -> "first-declared"
                    rule (score: score >= 0) -> "second-declared"
                    otherwise -> "default"
                  }
                }
                """);

        Map<?, ?> response = new WorldFragmentTestKit().execute(
                fragment, Map.of("score", 10), Map.class);
        assertThat(response.get("value")).isEqualTo("first-declared");
    }

    @Test
    void uniqueHitPolicyFailsClosedWhenTwoRulesMatch() {
        BlogeFragmentRef fragment = BlogeFragmentRef.frozen("ambiguous-world.bloge", """
                graph ambiguousWorld {
                  decision_table response(score = ctx.score) hit=unique -> String {
                    rule (score: score >= 0) -> "first"
                    rule (score: score >= 0) -> "second"
                    otherwise -> "default"
                  }
                }
                """);

        assertThatThrownBy(() -> new WorldFragmentTestKit().execute(fragment, Map.of("score", 10)))
                .isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(WorldModelException.Code.FRAGMENT_AMBIGUOUS);
                    assertThat(error.getMessage()).isEqualTo("RG.WORLD.FRAGMENT_AMBIGUOUS")
                            .doesNotContain("10");
                });
    }

    @Test
    void enforcesNodeDepthTimeoutAndOutputSizeLimits() {
        BlogeFragmentRef twoNodes = BlogeFragmentRef.frozen("two-nodes.bloge", """
                graph twoNodes {
                  transform first {
                    value = ctx.value
                  }
                  transform second {
                    value = first.output.value
                  }
                }
                """, "second");
        assertRejected(new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                        1_000_000, 1, 32, 1_000_000, 1_000_000, 100,
                        Duration.ofSeconds(2))), twoNodes, Map.of("value", "safe"),
                WorldModelException.Code.LIMIT_NODE_EXCEEDED);
        assertRejected(new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                        1_000_000, 64, 2, 1_000_000, 1_000_000, 100,
                        Duration.ofSeconds(2))), decisionFragment(),
                Map.of("type", Map.of("nested", Map.of("value", "secret"))),
                WorldModelException.Code.LIMIT_DEPTH_EXCEEDED);
        assertRejected(new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                        1_000_000, 64, 32, 1_000_000, 1_000_000, 100,
                        Duration.ofNanos(1))), decisionFragment(),
                Map.of("type", "vip"), WorldModelException.Code.LIMIT_TIMEOUT);
        assertRejected(new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                        1_000_000, 64, 32, 1_000_000, 4, 100,
                        Duration.ofSeconds(2))), decisionFragment(),
                Map.of("type", "vip"), WorldModelException.Code.LIMIT_OUTPUT_EXCEEDED);
    }

    @Test
    void enforcesSourceInputAndReplayLimitsBeforeRunningTheEngine() {
        assertRejected(new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                        8, 64, 32, 1_000_000, 1_000_000, 100, Duration.ofSeconds(2))),
                decisionFragment(), Map.of("type", "vip"), WorldModelException.Code.LIMIT_EXCEEDED);
        assertRejected(new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                        1_000_000, 64, 32, 4, 1_000_000, 100, Duration.ofSeconds(2))),
                decisionFragment(), Map.of("type", "vip"), WorldModelException.Code.LIMIT_EXCEEDED);
        assertRejected(new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                        1_000_000, 64, 32, 1_000_000, 1_000_000, 1, Duration.ofSeconds(2))),
                decisionFragment(), Map.of("type", "vip"), WorldModelException.Code.LIMIT_EXCEEDED, 2);
    }

    @Test
    void isolatedRegistryContainsOnlyAdmittedPurePrimitives() {
        BlogeFragmentAdmission.Executable executable = BlogeFragmentAdmission.compile(decisionFragment());

        assertThat(executable.registry().discover("*"))
                .containsExactly("__decision_table__", "__transform__");
        assertThat(executable.isolatedRegistry().discover("*"))
                .containsExactly("__decision_table__", "__transform__");
    }

    @Test
    void nestedRequestIsCopiedAndReturnedDeeplyImmutable() {
        BlogeFragmentRef fragment = BlogeFragmentRef.frozen("nested-world.bloge", """
                graph nestedWorld {
                  transform response {
                    value = ctx.request
                  }
                }
                """);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("name", "Ada");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("request", nested);

        WorldFragmentTestKit.ReplayResult result = new WorldFragmentTestKit().execute(
                fragment, request, 1);
        Map<?, ?> response = (Map<?, ?>) result.response();
        Map<?, ?> copied = (Map<?, ?>) response.get("value");
        nested.put("name", "Eve");

        assertThat(copied.get("name")).isEqualTo("Ada");
        assertThatThrownBy(() -> ((Map<Object, Object>) copied).put("name", "Mallory"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void repeatedTimeoutsLeaveNoTrackedExecutionThreads() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        WorldFragmentTestKit.ExecutorFactory executorFactory = () ->
                Executors.newThreadPerTaskExecutor(task -> {
                    created.incrementAndGet();
                    return Thread.ofVirtual().unstarted(() -> {
                        active.incrementAndGet();
                        try {
                            task.run();
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                });
        WorldFragmentTestKit kit = new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                1_000_000, 64, 32, 1_000_000, 1_000_000, 20, Duration.ofNanos(1)),
                executorFactory, WorldFragmentTestKitTest::newEngine);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatThrownBy(() -> kit.execute(decisionFragment(), Map.of("type", "vip")))
                    .isInstanceOfSatisfying(WorldModelException.class, error ->
                            assertThat(error.code()).isEqualTo(WorldModelException.Code.LIMIT_TIMEOUT));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (active.get() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertThat(created.get()).isLessThanOrEqualTo(10);
        assertThat(active.get()).isZero();
    }

    @Test
    void rejectedExternalNodeDoesNotCreateAnEngine() {
        AtomicInteger engines = new AtomicInteger();
        WorldFragmentTestKit.EngineFactory engineFactory = executable -> {
            engines.incrementAndGet();
            return newEngine(executable);
        };
        BlogeFragmentRef fragment = BlogeFragmentRef.frozen("external.bloge",
                "graph external { node call : httpResource { } }");

        assertThatThrownBy(() -> new WorldFragmentTestKit(
                WorldFragmentTestKit.Limits.defaults(),
                Executors::newVirtualThreadPerTaskExecutor, engineFactory)
                .execute(fragment, Map.of()))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.FRAGMENT_NETWORK_FORBIDDEN));
        assertThat(engines).hasValue(0);
    }

    private static GraphEngine newEngine(BlogeFragmentAdmission.Executable executable) {
        return GraphEngine.builder()
                .registry(executable.isolatedRegistry())
                .interceptors(java.util.List.of())
                .listeners(java.util.List.of())
                .maxGlobalConcurrency(1)
                .build();
    }

    private static BlogeFragmentRef decisionFragment() {
        return BlogeFragmentRef.frozen("limits-world.bloge", DECISION_TABLE);
    }

    private static void assertRejected(WorldFragmentTestKit testKit,
                                       BlogeFragmentRef fragment,
                                       Map<String, Object> request,
                                       WorldModelException.Code code) {
        assertRejected(testKit, fragment, request, code, 1);
    }

    private static void assertRejected(WorldFragmentTestKit testKit,
                                       BlogeFragmentRef fragment,
                                       Map<String, Object> request,
                                       WorldModelException.Code code,
                                       int replayCount) {
        assertThatThrownBy(() -> testKit.execute(fragment, request, replayCount))
                .isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.getMessage()).isEqualTo("RG.WORLD." + code.name())
                            .doesNotContain(request.toString());
                });
    }
}
