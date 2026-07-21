package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationEventWatcherTest {

    private static final String SCOPE = "resource-gateway-prod";
    private static final String INSTANCE = "replica-a";
    private static final String HEAD = fingerprint('0');
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void noChangeIsHealthyAndLeavesTheCursorUntouched() {
        FakeSource source = source(noChange());
        FakeCursor cursor = new FakeCursor();
        var watcher = watcher(source, cursor, accepted(), () -> true, 4);

        var descriptor = watcher.pollOnce();

        assertThat(descriptor.status()).isEqualTo("IDLE");
        assertThat(descriptor.ready()).isTrue();
        assertThat(descriptor.committedSequence()).isZero();
        assertThat(descriptor.stagedPage()).isFalse();
        assertThat(source.fetches).isEqualTo(1);
    }

    @Test
    void servingFencePreventsRemoteIoAndRemainsAnIntentionalHealthyPause() {
        FakeSource source = source(noChange());
        var watcher = watcher(source, new FakeCursor(), accepted(), () -> false, 1);

        var descriptor = watcher.pollOnce();

        assertThat(descriptor.status()).isEqualTo("RUNTIME_FENCED");
        assertThat(descriptor.reasonCode()).isEqualTo("RUNTIME_SERVING_FENCED");
        assertThat(descriptor.ready()).isTrue();
        assertThat(source.fetches).isZero();
    }

    @Test
    void appliesAndCommitsOneExactPage() {
        ControlPlaneCertificateRotationEventPage page = page(1, HEAD,
                List.of(event("rotation-002", "target-a")));
        FakeCursor cursor = new FakeCursor();
        var watcher = watcher(source(pageResult(page)), cursor, accepted(), () -> true, 1);

        var descriptor = watcher.pollOnce();

        assertThat(descriptor.status()).isEqualTo("APPLIED");
        assertThat(descriptor.committedSequence()).isEqualTo(1);
        assertThat(descriptor.stagedPage()).isFalse();
        assertThat(descriptor.appliedPageCount()).isEqualTo(1);
        assertThat(descriptor.appliedEventCount()).isEqualTo(1);
        assertThat(cursor.commitCalls).isEqualTo(1);
    }

    @Test
    void reconcilesLocallyWhenAnotherServingSlotProcessAlreadyCommittedThePage() {
        ControlPlaneCertificateRotationEventPage page = page(1, HEAD,
                List.of(event("rotation-002", "target-a")));
        FakeCursor cursor = new FakeCursor();
        cursor.alreadyCommittedStage = true;
        AtomicInteger applications = new AtomicInteger();
        var watcher = watcher(source(pageResult(page)), cursor, event -> {
            applications.incrementAndGet();
            return accepted().apply(event);
        }, () -> true, 1);

        var descriptor = watcher.pollOnce();

        assertThat(descriptor.status()).isEqualTo("APPLIED");
        assertThat(descriptor.committedSequence()).isEqualTo(1);
        assertThat(descriptor.stagedPage()).isFalse();
        assertThat(applications).hasValue(1);
        assertThat(cursor.commitCalls).isEqualTo(1);
    }

    @Test
    void drainsOnlyTheConfiguredNumberOfContiguousPages() {
        var first = page(1, HEAD, List.of(event("rotation-002", "target-a")));
        var second = page(2, first.pageFingerprint(),
                List.of(event("rotation-003", "target-b")));
        FakeCursor cursor = new FakeCursor();
        var watcher = watcher(source(pageResult(first), pageResult(second), noChange()),
                cursor, accepted(), () -> true, 2);

        var descriptor = watcher.pollOnce();

        assertThat(descriptor.reasonCode()).isEqualTo("POLL_PAGE_LIMIT_REACHED");
        assertThat(descriptor.committedSequence()).isEqualTo(2);
        assertThat(descriptor.appliedPageCount()).isEqualTo(2);
        assertThat(descriptor.appliedEventCount()).isEqualTo(2);
    }

    @Test
    void partialApplicationKeepsThePageStagedAndExactReplayRepairsIt() {
        var page = page(1, HEAD, List.of(
                event("rotation-002", "target-a"),
                event("rotation-003", "target-b")));
        FakeCursor cursor = new FakeCursor();
        AtomicInteger attempts = new AtomicInteger();
        var watcher = watcher(source(pageResult(page), pageResult(page)), cursor,
                event -> attempts.getAndIncrement() == 1
                        ? rejected() : accepted().apply(event), () -> true, 1);

        var blocked = watcher.pollOnce();
        var repaired = watcher.pollOnce();

        assertThat(blocked.status()).isEqualTo("APPLY_BLOCKED");
        assertThat(blocked.committedSequence()).isZero();
        assertThat(blocked.stagedPage()).isTrue();
        assertThat(repaired.status()).isEqualTo("APPLIED");
        assertThat(repaired.committedSequence()).isEqualTo(1);
        assertThat(repaired.stagedPage()).isFalse();
        assertThat(cursor.stageCalls).isEqualTo(2);
        assertThat(cursor.commitCalls).isEqualTo(1);
    }

    @Test
    void sourceProtocolAndTransportFailuresNeverStageOrCommit() {
        FakeCursor cursor = new FakeCursor();
        var protocolWatcher = watcher(source(without(
                ControlPlaneCertificateRotationEventSource.FetchStatus.PROTOCOL_REJECTED,
                "EVENT_SOURCE_PAGE_INVALID")), cursor, accepted(), () -> true, 1);
        assertThat(protocolWatcher.pollOnce().status()).isEqualTo("PROTOCOL_REJECTED");

        var unavailableWatcher = watcher(source(without(
                ControlPlaneCertificateRotationEventSource.FetchStatus.SOURCE_UNAVAILABLE,
                "EVENT_SOURCE_UNAVAILABLE")), cursor, accepted(), () -> true, 1);
        assertThat(unavailableWatcher.pollOnce().status()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(cursor.stageCalls).isZero();
        assertThat(cursor.commitCalls).isZero();
    }

    @Test
    void cursorConflictAndCursorOutageRemainDistinctFromEventRejection() {
        var page = page(1, HEAD, List.of(event("rotation-002", "target-a")));
        FakeCursor conflicting = new FakeCursor();
        conflicting.conflictStage = true;
        var conflictWatcher = watcher(source(pageResult(page)), conflicting,
                accepted(), () -> true, 1);
        assertThat(conflictWatcher.pollOnce().status()).isEqualTo("CURSOR_CONFLICT");

        FakeCursor unavailable = new FakeCursor();
        unavailable.failSnapshot = true;
        var watcher = watcherAfterConstruction(source(pageResult(page)), unavailable,
                accepted(), () -> true, 1);
        assertThat(watcher.pollOnce().status()).isEqualTo("CURSOR_UNAVAILABLE");
    }

    @Test
    void runtimeAndEventApplierOutagesAreFailClosedWithoutCursorAdvance() {
        var page = page(1, HEAD, List.of(event("rotation-002", "target-a")));
        var runtimeWatcher = watcher(source(pageResult(page)), new FakeCursor(), accepted(),
                () -> { throw new IllegalStateException("sensitive runtime detail"); }, 1);
        assertThat(runtimeWatcher.pollOnce().status()).isEqualTo("RUNTIME_UNAVAILABLE");

        FakeCursor cursor = new FakeCursor();
        var applyWatcher = watcher(source(pageResult(page)), cursor,
                event -> { throw new IllegalStateException("sensitive resolver detail"); },
                () -> true, 1);
        assertThat(applyWatcher.pollOnce()).satisfies(descriptor -> {
            assertThat(descriptor.status()).isEqualTo("APPLY_BLOCKED");
            assertThat(descriptor.reasonCode()).isEqualTo("EVENT_APPLY_UNAVAILABLE");
            assertThat(descriptor.committedSequence()).isZero();
            assertThat(descriptor.stagedPage()).isTrue();
        });
    }

    @Test
    void descriptorIsCachedAndCloseIsTerminal() {
        FakeCursor cursor = new FakeCursor();
        var watcher = watcher(source(noChange()), cursor, accepted(), () -> true, 1);
        watcher.pollOnce();
        cursor.failSnapshot = true;

        assertThat(watcher.descriptor().status()).isEqualTo("IDLE");
        watcher.close();
        assertThat(watcher.pollOnce()).satisfies(descriptor -> {
            assertThat(descriptor.status()).isEqualTo("CLOSED");
            assertThat(descriptor.ready()).isFalse();
        });
    }

    @Test
    void rejectsNonDurableCursorAndUnboundedSchedulerSettings() {
        FakeCursor cursor = new FakeCursor();
        cursor.durable = false;
        assertThatThrownBy(() -> watcher(source(noChange()), cursor,
                accepted(), () -> true, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationEventWatcher(
                source(noChange()), new FakeCursor(), accepted(), () -> true,
                Duration.ofMillis(999), 1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ControlPlaneCertificateRotationEventWatcher watcherAfterConstruction(
            FakeSource source,
            FakeCursor cursor,
            ControlPlaneCertificateRotationEventWatcher.EventApplier applier,
            java.util.function.BooleanSupplier admission,
            int maximumPages) {
        cursor.failSnapshot = false;
        var watcher = watcher(source, cursor, applier, admission, maximumPages);
        cursor.failSnapshot = true;
        return watcher;
    }

    private ControlPlaneCertificateRotationEventWatcher watcher(
            FakeSource source,
            FakeCursor cursor,
            ControlPlaneCertificateRotationEventWatcher.EventApplier applier,
            java.util.function.BooleanSupplier admission,
            int maximumPages) {
        return new ControlPlaneCertificateRotationEventWatcher(
                source, cursor, applier, admission, Duration.ofSeconds(5),
                maximumPages, false);
    }

    private ControlPlaneCertificateRotationEventWatcher.EventApplier accepted() {
        return event -> new ControlPlaneCertificateRotationController.ApplyResult(
                ControlPlaneCertificateRotationController.ApplyResult.SCHEMA_VERSION,
                ControlPlaneCertificateRotationController.ApplyStatus.APPLIED,
                "APPLIED", event.material().eventId(), event.materialFingerprint(), 1, 2);
    }

    private ControlPlaneCertificateRotationController.ApplyResult rejected() {
        return new ControlPlaneCertificateRotationController.ApplyResult(
                ControlPlaneCertificateRotationController.ApplyResult.SCHEMA_VERSION,
                ControlPlaneCertificateRotationController.ApplyStatus.GENERATION_CONFLICT,
                "CERTIFICATE_ROTATION_GENERATION_CONFLICT", "", "", 1, 0);
    }

    private FakeSource source(
            ControlPlaneCertificateRotationEventSource.FetchResult... results) {
        return new FakeSource(List.of(results));
    }

    private static ControlPlaneCertificateRotationEventSource.FetchResult pageResult(
            ControlPlaneCertificateRotationEventPage page) {
        return ControlPlaneCertificateRotationEventSource.FetchResult.page(page);
    }

    private static ControlPlaneCertificateRotationEventSource.FetchResult noChange() {
        return without(ControlPlaneCertificateRotationEventSource.FetchStatus.NO_CHANGE,
                "NO_EVENTS");
    }

    private static ControlPlaneCertificateRotationEventSource.FetchResult without(
            ControlPlaneCertificateRotationEventSource.FetchStatus status,
            String reason) {
        return ControlPlaneCertificateRotationEventSource.FetchResult.withoutPage(status, reason);
    }

    private ControlPlaneCertificateRotationEventPage page(
            long sequence,
            String predecessor,
            List<ControlPlaneCertificateRotationEvent> events) {
        var material = new ControlPlaneCertificateRotationEventPage.Material(
                ControlPlaneCertificateRotationEventPage.Material.SCHEMA_VERSION,
                SCOPE, sequence, predecessor, NOW, NOW.plusSeconds(60), events);
        return new ControlPlaneCertificateRotationEventPage(
                ControlPlaneCertificateRotationEventPage.SCHEMA_VERSION,
                material, ProtocolFingerprint.of(objectMapper, material));
    }

    private ControlPlaneCertificateRotationEvent event(String eventId, String targetId) {
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "certificate-authority", eventId, SCOPE, targetId, 2,
                fingerprint('a'), "candidate-b", fingerprint('b'), fingerprint('f'),
                NOW, NOW, NOW.plusSeconds(10), NOW.plusSeconds(120));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(objectMapper, material),
                List.of(new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", NOW,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class FakeSource
            implements ControlPlaneCertificateRotationEventSource {
        private final Deque<FetchResult> results;
        private int fetches;

        private FakeSource(List<FetchResult> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public FetchResult fetch(Position position) {
            fetches++;
            return results.isEmpty() ? noChange() : results.removeFirst();
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(Descriptor.SCHEMA_VERSION,
                    true, true, true, true, true);
        }
    }

    private static final class FakeCursor
            implements ControlPlaneCertificateRotationEventCursor {
        private Snapshot snapshot = new Snapshot(Snapshot.SCHEMA_VERSION,
                SCOPE, INSTANCE, 0, HEAD, 0, HEAD, 0, "", "");
        private boolean durable = true;
        private boolean failSnapshot;
        private boolean conflictStage;
        private boolean alreadyCommittedStage;
        private int stageCalls;
        private int commitCalls;

        @Override
        public StageResult stage(ControlPlaneCertificateRotationEventPage page) {
            stageCalls++;
            if (conflictStage) {
                return new StageResult(StageStatus.CONFLICT, snapshot);
            }
            if (alreadyCommittedStage) {
                snapshot = new Snapshot(Snapshot.SCHEMA_VERSION, SCOPE, INSTANCE,
                        0, HEAD, page.material().sequence(), page.pageFingerprint(),
                        0, "", "");
                return new StageResult(StageStatus.ALREADY_COMMITTED, snapshot);
            }
            if (snapshot.hasStagedPage()) {
                return new StageResult(snapshot.stagedPageFingerprint()
                        .equals(page.pageFingerprint()) ? StageStatus.REPLAYED
                        : StageStatus.CONFLICT, snapshot);
            }
            snapshot = new Snapshot(Snapshot.SCHEMA_VERSION, SCOPE, INSTANCE,
                    0, HEAD, snapshot.committedSequence(),
                    snapshot.committedPageFingerprint(), page.material().sequence(),
                    page.material().previousPageFingerprint(), page.pageFingerprint());
            return new StageResult(StageStatus.STAGED, snapshot);
        }

        @Override
        public CommitResult commit(String pageFingerprint) {
            commitCalls++;
            if (!snapshot.hasStagedPage()
                    && snapshot.committedPageFingerprint().equals(pageFingerprint)) {
                return new CommitResult(CommitStatus.REPLAYED, snapshot);
            }
            if (!snapshot.hasStagedPage()
                    || !snapshot.stagedPageFingerprint().equals(pageFingerprint)) {
                return new CommitResult(CommitStatus.CONFLICT, snapshot);
            }
            snapshot = new Snapshot(Snapshot.SCHEMA_VERSION, SCOPE, INSTANCE,
                    0, HEAD, snapshot.stagedSequence(), snapshot.stagedPageFingerprint(),
                    0, "", "");
            return new CommitResult(CommitStatus.COMMITTED, snapshot);
        }

        @Override
        public Snapshot snapshot() {
            if (failSnapshot) {
                throw new IllegalStateException("sensitive database detail");
            }
            return snapshot;
        }

        @Override
        public boolean durable() {
            return durable;
        }
    }
}
