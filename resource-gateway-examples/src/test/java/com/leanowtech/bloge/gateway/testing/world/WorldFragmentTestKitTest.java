package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.core.engine.GraphEngine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Test
    void statefulFragmentUsesDefaultsAndOverridesAndReturnsAnAtomicTransition() {
        BlogeFragmentRef fragment = statefulFragment("""
                graph statefulWorld {
                  transform result {
                    response = { accepted: ctx.request.amount > 0, before: ctx.state.balance }
                    stateWrites = { balance: ctx.state.balance - ctx.request.amount }
                  }
                }
                """);
        StateSpecV2 state = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.READ_WRITE, 100)));

        WorldFragmentTestKit.StatefulReplayResult result = new WorldFragmentTestKit()
                .executeStateful(fragment, state, Map.of("amount", 20), Map.of("/balance", 120), 20);

        assertThat(result.response()).isEqualTo(Map.of("accepted", true, "before", 120));
        assertThat(result.newState()).containsEntry("/balance", 100.0);
        assertThat(result.responseFingerprint()).startsWith("sha256:");
        assertThat(result.stateFingerprint()).startsWith("sha256:");
        assertThat(result.transitionFingerprint()).startsWith("sha256:");
        assertThat(result.replayCount()).isEqualTo(20);
        assertThat(result.state()).isSameAs(result.newState());
        assertThatThrownBy(() -> result.newState().put("/balance", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void writeOnlyStateNeverEntersTheFragmentEnvelope() {
        BlogeFragmentRef fragment = statefulFragment("""
                graph stateVisibility {
                  transform result {
                    response = { visible: ctx.state.balance, hidden: ctx.state.secret == null }
                    stateWrites = { balance: ctx.state.balance }
                  }
                }
                """);
        StateSpecV2 state = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.READ_WRITE, 10),
                stringState("/secret", StateKeySpec.Access.WRITE, "do-not-leak")));

        Map<?, ?> response = (Map<?, ?>) new WorldFragmentTestKit()
                .executeStateful(fragment, state, Map.of(), Map.of(), 1).response();

        assertThat(response.get("visible")).isEqualTo(10);
        assertThat(response.containsKey("hidden")).isTrue();
        assertThat(response.get("hidden")).isEqualTo(true);
    }

    @Test
    void flattensNestedWriteSetsBackToCanonicalPointerKeys() {
        BlogeFragmentRef fragment = statefulFragment("""
                graph nestedState {
                  transform result {
                    response = { before: ctx.state.account.balance }
                    stateWrites = { account: { balance: 80 } }
                  }
                }
                """);
        StateSpecV2 state = StateSpecV2.of(List.of(
                integerState("/account/balance", StateKeySpec.Access.READ_WRITE, 100)));

        WorldFragmentTestKit.StatefulReplayResult result = new WorldFragmentTestKit()
                .executeStateful(fragment, state, Map.of(), Map.of(), 20);

        assertThat(result.response()).isEqualTo(Map.of("before", 100));
        assertThat(result.newState()).containsEntry("/account/balance", 80.0);
    }

    @Test
    void rejectsExtraOutputFieldsAndNonObjectTopLevelOutput() {
        StateSpecV2 state = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.WRITE, 1)));
        WorldFragmentTestKit kit = new WorldFragmentTestKit();
        assertStateRejected(kit, fragmentReturning(
                        "response = true\nstateWrites = { balance: 1 }\nextra = \"reject\""),
                state, Map.of(), WorldModelException.Code.STATE_OUTPUT_EXTRA_FIELDS);
        assertStateRejected(kit, statefulFragment("""
                        graph scalarOutput {
                          transform result {
                            value = true
                          }
                        }
                        """), state, Map.of(), WorldModelException.Code.STATE_OUTPUT_EXTRA_FIELDS);
        assertThatThrownBy(() -> WorldFragmentTestKit.requireStatefulEnvelope("scalar"))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_OUTPUT_MISSING));
    }

    @Test
    void rejectsPointerPrefixConflictAndSupportsEscapedPointerSegments() {
        assertThatThrownBy(() -> StateSpecV2.of(List.of(
                        integerState("/account", StateKeySpec.Access.WRITE, 1),
                        integerState("/account/balance", StateKeySpec.Access.WRITE, 1))))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_NOT_SUPPORTED));
        StateKeySpec escaped = integerState("/account~1balance", StateKeySpec.Access.WRITE, 1);
        assertThat(escaped.key()).isEqualTo("/account~1balance");
        assertThat(StatePointer.decode(escaped.key())).containsExactly("account/balance");
        assertThat(StatePointer.encode(StatePointer.decode(escaped.key())))
                .isEqualTo(escaped.key());
    }

    @Test
    void maxInputBytesCoversTheCompleteRequestAndVisibleStateEnvelope() {
        StateSpecV2 state = StateSpecV2.of(List.of(
                stringState("/large", StateKeySpec.Access.READ, "12345678901234567890")));
        WorldFragmentTestKit kit = new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                1_000_000, 64, 32, 16, 1_000_000, 10, Duration.ofSeconds(2)));
        assertStateRejected(kit, fragmentReturning(
                        "response = true\nstateWrites = { }"), state, Map.of(),
                WorldModelException.Code.LIMIT_EXCEEDED);
    }

    @Test
    void statefulReplayRejectsAControlledNonDeterministicResponse() {
        BlogeFragmentRef fragment = fragmentReturning(
                "response = ctx.request.value\nstateWrites = { balance: 1 }");
        StateSpecV2 state = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.WRITE, 1)));
        assertThatThrownBy(() -> new WorldFragmentTestKit().executeStateful(
                        fragment, state, Map.of("value", new FlakyNumber()), Map.of(), 2))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.NON_DETERMINISTIC_REPLAY));
    }

    @Test
    void stateErrorsNeverEchoSecretPayloads() {
        StateSpecV2 state = StateSpecV2.of(List.of(
                stringState("/token", StateKeySpec.Access.WRITE, "safe")));
        String secret = "secret-token-should-not-appear";
        assertThatThrownBy(() -> new WorldFragmentTestKit().executeStateful(
                        fragmentReturning("response = true\nstateWrites = { token: \"safe\" }"),
                        state, Map.of(), Map.of("/unknown", secret), 1))
                .isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_INPUT_INVALID);
                    assertThat(error.getMessage()).doesNotContain(secret);
                });
    }

    @Test
    void rejectsInvalidStateWriteShapesKeysAccessAndSchemas() {
        WorldFragmentTestKit kit = new WorldFragmentTestKit();
        StateSpecV2 readOnly = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.READ, 10)));
        StateSpecV2 writable = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.WRITE, 10)));

        assertStateRejected(kit, fragmentReturning("response = true"), writable, Map.of(),
                WorldModelException.Code.STATE_OUTPUT_MISSING);
        assertStateRejected(kit, fragmentReturning("response = true\nstateWrites = \"nope\""), writable,
                Map.of(), WorldModelException.Code.STATE_WRITESET_INVALID);
        assertStateRejected(kit, fragmentReturning("response = true\nstateWrites = { balance: 11 }"), readOnly,
                Map.of(), WorldModelException.Code.STATE_READ_ONLY_WRITE);
        assertStateRejected(kit, fragmentReturning("response = true\nstateWrites = { unknown: 11 }"), writable,
                Map.of(), WorldModelException.Code.STATE_UNKNOWN_WRITE);
        assertStateRejected(kit, fragmentReturning("response = true\nstateWrites = { balance: \"bad\" }"), writable,
                Map.of(), WorldModelException.Code.STATE_SCHEMA_MISMATCH);
    }

    @Test
    void validatesEveryWriteBeforeExposingAnyNewState() {
        BlogeFragmentRef fragment = fragmentReturning(
                "response = true\nstateWrites = { balance: 20, status: 123 }");
        StateSpecV2 state = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.READ_WRITE, 10),
                stringState("/status", StateKeySpec.Access.READ_WRITE, "OPEN")));
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("/balance", 10);
        overrides.put("/status", "OPEN");

        assertThatThrownBy(() -> new WorldFragmentTestKit().executeStateful(
                        fragment, state, Map.of(), overrides, 1))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SCHEMA_MISMATCH));
        assertThat(overrides).containsEntry("/balance", 10).containsEntry("/status", "OPEN");
    }

    @Test
    void rejectsInvalidOverridesBeforeTheFragmentRunsAndDoesNotMutateCallerData() {
        AtomicInteger engines = new AtomicInteger();
        WorldFragmentTestKit.EngineFactory engineFactory = executable -> {
            engines.incrementAndGet();
            return newEngine(executable);
        };
        WorldFragmentTestKit kit = new WorldFragmentTestKit(WorldFragmentTestKit.Limits.defaults(),
                Executors::newVirtualThreadPerTaskExecutor, engineFactory);
        StateSpecV2 state = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.WRITE, 10)));
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("/balance", "wrong");

        assertThatThrownBy(() -> kit.executeStateful(fragmentReturning(
                        "response = true\nstateWrites = { balance: 11 }"), state, Map.of(), overrides, 1))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_SCHEMA_MISMATCH));
        assertThat(overrides).containsEntry("/balance", "wrong");
        assertThat(engines).hasValue(0);
    }

    @Test
    void canonicalFingerprintsIgnoreMapInsertionOrder() {
        BlogeFragmentRef fragment = statefulFragment("""
                graph stableState {
                  transform result {
                    response = { accepted: true, before: ctx.state.balance }
                    stateWrites = { balance: 42 }
                  }
                }
                """);
        StateSpecV2 left = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.READ_WRITE, 10),
                stringState("/status", StateKeySpec.Access.READ, "OPEN")));
        StateSpecV2 right = StateSpecV2.of(List.of(
                stringState("/status", StateKeySpec.Access.READ, "OPEN"),
                integerState("/balance", StateKeySpec.Access.READ_WRITE, 10)));
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("/status", "OPEN");
        first.put("/balance", 10);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("/balance", 10);
        second.put("/status", "OPEN");

        WorldFragmentTestKit kit = new WorldFragmentTestKit();
        WorldFragmentTestKit.StatefulReplayResult a = kit.executeStateful(fragment, left, Map.of(), first, 1);
        WorldFragmentTestKit.StatefulReplayResult b = kit.executeStateful(fragment, right, Map.of(), second, 1);

        assertThat(a.responseFingerprint()).isEqualTo(b.responseFingerprint());
        assertThat(a.stateFingerprint()).isEqualTo(b.stateFingerprint());
        assertThat(a.transitionFingerprint()).isEqualTo(b.transitionFingerprint());
        WorldFragmentTestKit.StatefulReplayResult differentRequest = kit.executeStateful(
                fragment, left, Map.of("ignored", 1), first, 1);
        assertThat(differentRequest.responseFingerprint()).isEqualTo(a.responseFingerprint());
        assertThat(differentRequest.transitionFingerprint()).isNotEqualTo(a.transitionFingerprint());
    }

    @Test
    void appliesOutputAndDepthLimitsToStatefulResults() {
        BlogeFragmentRef fragment = statefulFragment("""
                graph boundedState {
                  transform result {
                    response = { nested: { value: "too-deep" } }
                    stateWrites = { balance: 10 }
                  }
                }
                """);
        StateSpecV2 state = StateSpecV2.of(List.of(
                integerState("/balance", StateKeySpec.Access.WRITE, 1)));
        WorldFragmentTestKit depthKit = new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                1_000_000, 64, 2, 1_000_000, 1_000_000, 10, Duration.ofSeconds(2)));
        assertThatThrownBy(() -> depthKit.executeStateful(fragment, state, Map.of(), Map.of(), 1))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.LIMIT_DEPTH_EXCEEDED));

        WorldFragmentTestKit outputKit = new WorldFragmentTestKit(new WorldFragmentTestKit.Limits(
                1_000_000, 64, 32, 1_000_000, 4, 10, Duration.ofSeconds(2)));
        assertThatThrownBy(() -> outputKit.executeStateful(fragmentReturning(
                        "response = \"long-response\"\nstateWrites = { balance: 1 }"), state,
                        Map.of(), Map.of(), 1))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.LIMIT_OUTPUT_EXCEEDED));
    }

    @Test
    void statefulResultConstructorSanitizesUnsupportedJsonValues() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        assertThatThrownBy(() -> new WorldFragmentTestKit.StatefulReplayResult(
                        cyclic, Map.of(), "response", "state", "transition", 1, Duration.ZERO))
                .isInstanceOfSatisfying(WorldModelException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldModelException.Code.STATE_OUTPUT_INVALID));
    }

    private static void assertStateRejected(WorldFragmentTestKit kit,
                                             BlogeFragmentRef fragment,
                                             StateSpecV2 state,
                                             Map<String, Object> overrides,
                                             WorldModelException.Code code) {
        assertThatThrownBy(() -> kit.executeStateful(fragment, state, Map.of(), overrides, 1))
                .isInstanceOfSatisfying(WorldModelException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.getMessage()).isEqualTo("RG.WORLD." + code.name());
                });
    }

    private static BlogeFragmentRef statefulFragment(String body) {
        return BlogeFragmentRef.frozen("stateful-world.bloge", body);
    }

    private static BlogeFragmentRef fragmentReturning(String fields) {
        return statefulFragment("graph statefulResult { transform result { " + fields + " } }");
    }

    private static StateKeySpec integerState(String key, StateKeySpec.Access access, int value) {
        return new StateKeySpec(key, access, Map.of("type", "integer"), value);
    }

    private static StateKeySpec stringState(String key, StateKeySpec.Access access, String value) {
        return new StateKeySpec(key, access, Map.of("type", "string"), value);
    }

    private static final class FlakyNumber extends Number {
        private final AtomicInteger value = new AtomicInteger();

        @Override
        public int intValue() {
            return value.incrementAndGet();
        }

        @Override
        public long longValue() {
            return intValue();
        }

        @Override
        public float floatValue() {
            return intValue();
        }

        @Override
        public double doubleValue() {
            return intValue();
        }

        @Override
        public String toString() {
            return Integer.toString(value.incrementAndGet());
        }
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
