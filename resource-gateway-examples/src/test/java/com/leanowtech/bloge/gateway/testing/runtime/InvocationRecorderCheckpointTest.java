package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvocationRecorderCheckpointTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void capturesAndRestoresMonotonicRuleSiteAndGraphCursorsWithoutRawCorrelation() throws Exception {
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        InvocationSite firstSite = site("first").withCorrelationKey("customer-secret-42");
        InvocationSite secondSite = site("second").withCorrelationKey("customer-secret-42");
        GraphContext firstGraph = new GraphContext();

        assertThat(recorder.consume("rule-a")).isEqualTo(1);
        assertThat(recorder.consume("rule-a")).isEqualTo(2);
        assertThat(completedBinding(recorder, firstSite, firstGraph))
                .extracting(InvocationRecorder.InvocationBinding::occurrence,
                        InvocationRecorder.InvocationBinding::graphOccurrence)
                .containsExactly(1, 1);
        assertThat(completedBinding(recorder, secondSite, firstGraph))
                .extracting(InvocationRecorder.InvocationBinding::occurrence,
                        InvocationRecorder.InvocationBinding::graphOccurrence)
                .containsExactly(1, 1);
        assertThat(completedBinding(recorder, firstSite, new GraphContext()))
                .extracting(InvocationRecorder.InvocationBinding::occurrence,
                        InvocationRecorder.InvocationBinding::graphOccurrence)
                .containsExactly(2, 2);

        FixtureConsumptionStateSnapshot snapshot = recorder.captureFixtureState();

        assertThat(snapshot.ruleUses()).containsExactly(Map.entry("rule-a", 2L));
        assertThat(snapshot.siteOccurrenceCursors()).hasSize(2).containsValues(1L, 2L);
        assertThat(snapshot.graphOccurrenceCursors()).hasSize(1).containsValue(2L);
        assertThat(snapshot.stateFingerprint()).matches("sha256:[a-f0-9]{64}");
        String wire = objectMapper.writeValueAsString(snapshot);
        assertThat(wire).doesNotContain("customer-secret-42", "/root", "first", "second");

        InvocationRecorder restored = new InvocationRecorder(objectMapper);
        restored.restoreFixtureState(snapshot);
        GraphContext resumedGraph = new GraphContext();
        assertThat(completedBinding(restored, firstSite, resumedGraph))
                .extracting(InvocationRecorder.InvocationBinding::occurrence,
                        InvocationRecorder.InvocationBinding::graphOccurrence)
                .containsExactly(3, 3);
        assertThat(completedBinding(restored, secondSite, resumedGraph))
                .extracting(InvocationRecorder.InvocationBinding::occurrence,
                        InvocationRecorder.InvocationBinding::graphOccurrence)
                .containsExactly(2, 3);
        assertThat(restored.consume("rule-a")).isEqualTo(3);
    }

    @Test
    void rejectsTamperedSnapshotBeforeRestoringAnyCursor() {
        InvocationRecorder source = new InvocationRecorder(objectMapper);
        source.consume("rule-a");
        FixtureConsumptionStateSnapshot sealed = source.captureFixtureState();
        FixtureConsumptionStateSnapshot tampered = new FixtureConsumptionStateSnapshot(
                sealed.schemaVersion(), Map.of("rule-a", 99L), sealed.siteOccurrenceCursors(),
                sealed.graphOccurrenceCursors(), sealed.stateFingerprint());
        InvocationRecorder target = new InvocationRecorder(objectMapper);

        assertThatThrownBy(() -> target.restoreFixtureState(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThat(target.captureFixtureState().ruleUses()).isEmpty();
    }

    @Test
    void refusesToMergeCheckpointStateIntoAnAlreadyUsedRecorder() {
        InvocationRecorder source = new InvocationRecorder(objectMapper);
        source.consume("rule-a");
        FixtureConsumptionStateSnapshot snapshot = source.captureFixtureState();
        InvocationRecorder target = new InvocationRecorder(objectMapper);
        target.consume("rule-b");

        assertThatThrownBy(() -> target.restoreFixtureState(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty recorder");
        assertThat(target.uses("rule-b")).isEqualTo(1);
        assertThat(target.uses("rule-a")).isZero();
    }

    @Test
    void refusesRestoreAfterAnyNonCursorRuntimeFactWasRecorded() {
        InvocationRecorder source = new InvocationRecorder(objectMapper);
        FixtureConsumptionStateSnapshot snapshot = source.captureFixtureState();
        InvocationRecorder target = new InvocationRecorder(objectMapper);
        target.markControlMode(site("first"), "RETURN");

        assertThatThrownBy(() -> target.restoreFixtureState(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty recorder");
        assertThat(target.controlModes()).containsValue("RETURN");
    }

    @Test
    void rejectedBindingCannotAdvanceOnlyOneSideOfTheCursorPair() {
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        InvocationSite site = site("first").withCorrelationKey("secret");

        assertThatThrownBy(() -> recorder.bind(site, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graphContext");
        FixtureConsumptionStateSnapshot afterFailure = recorder.captureFixtureState();
        assertThat(afterFailure.siteOccurrenceCursors()).isEmpty();
        assertThat(afterFailure.graphOccurrenceCursors()).isEmpty();
        assertThat(completedBinding(recorder, site, new GraphContext()))
                .extracting(InvocationRecorder.InvocationBinding::occurrence,
                        InvocationRecorder.InvocationBinding::graphOccurrence)
                .containsExactly(1, 1);
    }

    @Test
    void rejectsCaptureUntilBoundInvocationReachesAnAttemptBoundary() {
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        InvocationRecorder.InvocationBinding binding = recorder.bind(
                site("first"), new GraphContext());

        assertThatThrownBy(recorder::captureFixtureState)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiescent invocation boundary");
        recorder.beginAttempt(binding);
        assertThatThrownBy(recorder::captureFixtureState)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quiescent invocation boundary");
        recorder.endAttempt(binding);

        assertThat(recorder.captureFixtureState().siteOccurrenceCursors())
                .containsValue(1L);
    }

    @Test
    void captureNeverObservesAHeadlessSiteOrGraphCursorDuringConcurrentBinding() throws Exception {
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        InvocationSite site = site("parallel").withCorrelationKey("batch-secret");
        int workers = 4;
        int bindingsPerWorker = 500;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            @SuppressWarnings("unchecked")
            Future<Void>[] futures = new Future[workers];
            for (int worker = 0; worker < workers; worker++) {
                futures[worker] = executor.submit(() -> {
                    start.await();
                    for (int index = 0; index < bindingsPerWorker; index++) {
                        InvocationRecorder.InvocationBinding binding = recorder.bind(
                                site, new GraphContext());
                        recorder.beginAttempt(binding);
                        recorder.endAttempt(binding);
                    }
                    return null;
                });
            }
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (java.util.Arrays.stream(futures).anyMatch(future -> !future.isDone())) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("Concurrent bindings did not finish within 10 seconds");
                }
                try {
                    assertCursorMapsAdvanceTogether(recorder.captureFixtureState());
                } catch (IllegalStateException activeInvocation) {
                    assertThat(activeInvocation).hasMessageContaining("quiescent invocation boundary");
                }
            }
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        FixtureConsumptionStateSnapshot terminal = recorder.captureFixtureState();
        assertCursorMapsAdvanceTogether(terminal);
        assertThat(terminal.siteOccurrenceCursors()).containsValue(2000L);
        assertThat(terminal.graphOccurrenceCursors()).containsValue(2000L);
    }

    @Test
    void concurrentConsumptionAndCaptureRemainMonotonicWithoutLosingUses() throws Exception {
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        int workers = 4;
        int usesPerWorker = 500;
        CountDownLatch start = new CountDownLatch(1);
        long observed = 0;
        try (var executor = Executors.newFixedThreadPool(workers)) {
            @SuppressWarnings("unchecked")
            Future<Void>[] futures = new Future[workers];
            for (int worker = 0; worker < workers; worker++) {
                futures[worker] = executor.submit(() -> {
                    start.await();
                    for (int index = 0; index < usesPerWorker; index++) {
                        recorder.consume("rule-a");
                    }
                    return null;
                });
            }
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (java.util.Arrays.stream(futures).anyMatch(future -> !future.isDone())) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("Concurrent consumption did not finish within 10 seconds");
                }
                long current = recorder.captureFixtureState().ruleUses()
                        .getOrDefault("rule-a", 0L);
                assertThat(current).isGreaterThanOrEqualTo(observed);
                observed = current;
            }
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        FixtureConsumptionStateSnapshot terminal = recorder.captureFixtureState();
        assertThat(terminal.ruleUses()).containsEntry("rule-a", 2000L);
        InvocationRecorder restored = new InvocationRecorder(objectMapper);
        restored.restoreFixtureState(terminal);
        assertThat(restored.consume("rule-a")).isEqualTo(2001);
    }

    @Test
    void boundedConsumptionCannotOvershootUnderConcurrency() throws Exception {
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        int workers = 8;
        int maximum = 37;
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            @SuppressWarnings("unchecked")
            Future<Void>[] futures = new Future[workers];
            for (int worker = 0; worker < workers; worker++) {
                futures[worker] = executor.submit(() -> {
                    start.await();
                    for (int index = 0; index < 100; index++) {
                        if (recorder.consumeIfAvailable("bounded-rule", maximum) > 0) {
                            accepted.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(accepted).hasValue(maximum);
        assertThat(recorder.uses("bounded-rule")).isEqualTo(maximum);
        assertThat(recorder.consumeIfAvailable("bounded-rule", maximum)).isEqualTo(-1);
    }

    private static InvocationRecorder.InvocationBinding completedBinding(
            InvocationRecorder recorder, InvocationSite site, GraphContext graphContext) {
        InvocationRecorder.InvocationBinding binding = recorder.bind(site, graphContext);
        recorder.beginAttempt(binding);
        recorder.endAttempt(binding);
        return binding;
    }

    private static void assertCursorMapsAdvanceTogether(FixtureConsumptionStateSnapshot snapshot) {
        long siteCursor = snapshot.siteOccurrenceCursors().values().stream()
                .findFirst().orElse(0L);
        long graphCursor = snapshot.graphOccurrenceCursors().values().stream()
                .findFirst().orElse(0L);
        assertThat(siteCursor).isEqualTo(graphCursor);
    }

    private static InvocationSite site(String nodeId) {
        return new InvocationSite(InvocationSite.SCHEMA_VERSION, SHA_A, "/root", nodeId,
                "operator." + nodeId, "", "", SHA_A, InvocationSite.InvocationKind.PRIMARY,
                null, "", null);
    }
}
