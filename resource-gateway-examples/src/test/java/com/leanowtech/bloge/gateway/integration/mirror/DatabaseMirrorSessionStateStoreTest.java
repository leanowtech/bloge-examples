package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStatePayloadProtector;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateTransactionEngine;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.CAPACITY_EXCEEDED;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.CORRUPT;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.CREATE_CONFLICT;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.GONE;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.LEASE_BUSY;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.LEASE_LOST;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.SESSION_ID_CONFLICT;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.STATE_CONFLICT;
import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMirrorSessionStateStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void createsEncryptedSessionAndExactlyReplaysCreate() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            MirrorSessionStateStore.CreateCommand create =
                    create("create-1", fixture.payload());

            MirrorSessionStateStore.CreateResult first =
                    harness.store().create(create);
            MirrorSessionStateStore.CreateResult replay =
                    harness.store().create(create);
            String envelope = harness.jdbc().queryForObject(
                    "SELECT payload_envelope FROM mirror_session_state",
                    String.class);

            assertThat(first.disposition())
                    .isEqualTo(MirrorSessionStateStore.CreateDisposition.CREATED);
            assertThat(replay.disposition())
                    .isEqualTo(MirrorSessionStateStore.CreateDisposition.REPLAYED);
            assertThat(replay.descriptor()).isEqualTo(first.descriptor());
            assertThat(envelope).doesNotContain("O-100")
                    .doesNotContain("paidAmount");
            assertThat(harness.store().recentAudit(
                    fixture.state().scope(), fixture.state().sessionId(), 10))
                    .extracting(MirrorSessionStateStore.OperationAudit::operation)
                    .containsExactly(MirrorSessionStateStore.Operation.CREATE);
        }
    }

    @Test
    void rejectsCreateKeyDriftSessionIdCollisionAndCrossScopeReads() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));

            assertStoreFailure(() -> harness.store().create(
                    new MirrorSessionStateStore.CreateCommand(
                            "create-1", "sha256:" + "9".repeat(64),
                            fixture.payload())), CREATE_CONFLICT);
            assertStoreFailure(() -> harness.store().create(
                    create("create-2", fixture.payload())),
                    SESSION_ID_CONFLICT);
            CapabilitySnapshot.Scope other = new CapabilitySnapshot.Scope(
                    "tenant-a", "org-b", "tool-studio", "test", "sg");
            assertThat(harness.store().find(
                    other, fixture.state().sessionId())).isEmpty();
        }
    }

    @Test
    void leaseFencedKernelCommitAtomicallyAdvancesStateAndAudit() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            MirrorSessionStateStore.ClaimedSession claimed =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-a", 30));

            SessionStateSpace.TransactionReceipt receipt =
                    executeRefund(harness, claimed, fixture.effect(), 450);
            MirrorSessionDescriptor current = harness.store().find(
                    fixture.state().scope(),
                    fixture.state().sessionId()).orElseThrow();

            assertThat(receipt.revisionAfter()).isEqualTo(1);
            assertThat(current.stateRevision()).isEqualTo(1);
            assertThat(current.stateFingerprint())
                    .isNotEqualTo(fixture.state().fingerprint());
            assertThat(harness.store().recentAudit(
                    current.scope(), current.sessionId(), 10))
                    .extracting(MirrorSessionStateStore.OperationAudit::operation)
                    .containsExactly(
                            MirrorSessionStateStore.Operation.COMMIT,
                            MirrorSessionStateStore.Operation.CREATE);
        }
    }

    @Test
    void databaseLeaseRejectsBusyAndSupersededOwners() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            MirrorSessionStateStore.ClaimedSession first =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-a", 30));

            assertStoreFailure(() -> harness.store().claim(claim(
                    fixture.state().scope(),
                    fixture.state().sessionId(), "worker-b", 30)),
                    LEASE_BUSY);

            MirrorSessionStateStore.ClaimedSession replacement =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-a", 30));
            MirrorSessionPayload candidate = committedPayload(
                    first.payload(), fixture.effect(), 100);
            assertStoreFailure(() -> harness.store().compareAndSet(
                    new MirrorSessionStateStore.CommitCommand(
                            first.lease(),
                            first.payload().state().fingerprint(),
                            candidate)), LEASE_LOST);
            assertThat(replacement.lease().fence())
                    .isGreaterThan(first.lease().fence());
        }
    }

    @Test
    void exactReleaseAllowsImmediateTakeoverAndStaleFenceCannotClearNewOwner() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            MirrorSessionStateStore.ClaimedSession first =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-a", 30));

            assertThat(harness.store().release(first.lease())).isTrue();
            assertThat(harness.store().release(first.lease())).isFalse();
            MirrorSessionStateStore.ClaimedSession takeover =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-b", 30));

            assertThat(harness.store().release(first.lease())).isFalse();
            assertStoreFailure(() -> harness.store().claim(claim(
                    fixture.state().scope(),
                    fixture.state().sessionId(), "worker-c", 30)),
                    LEASE_BUSY);
            assertThat(harness.store().release(takeover.lease())).isTrue();
        }
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleStateCannotCommit() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            MirrorSessionStateStore.ClaimedSession first =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-a", 1));
            harness.clock().set(NOW.plusSeconds(2));
            MirrorSessionStateStore.ClaimedSession takeover =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-b", 30));

            assertThat(takeover.lease().fence())
                    .isGreaterThan(first.lease().fence());
            MirrorSessionPayload candidate = committedPayload(
                    takeover.payload(), fixture.effect(), 100);
            assertStoreFailure(() -> harness.store().compareAndSet(
                    new MirrorSessionStateStore.CommitCommand(
                            takeover.lease(),
                            "sha256:" + "8".repeat(64),
                            candidate)), STATE_CONFLICT);
        }
    }

    @Test
    void ttlAndDestroyErasePayloadAndRemainIdempotent() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));

            MirrorSessionStateStore.DestroyResult destroyed =
                    harness.store().destroy(
                            fixture.state().scope(),
                            fixture.state().sessionId());
            MirrorSessionStateStore.DestroyResult replay =
                    harness.store().destroy(
                            fixture.state().scope(),
                            fixture.state().sessionId());

            assertThat(destroyed.disposition())
                    .isEqualTo(MirrorSessionStateStore.DestroyDisposition.DESTROYED);
            assertThat(replay.disposition())
                    .isEqualTo(MirrorSessionStateStore.DestroyDisposition.ALREADY_DESTROYED);
            assertThat(harness.jdbc().queryForObject(
                    "SELECT payload_envelope IS NULL FROM mirror_session_state",
                    Boolean.class)).isTrue();
            assertStoreFailure(() -> harness.store().claim(claim(
                    fixture.state().scope(),
                    fixture.state().sessionId(), "worker-a", 30)), GONE);
        }

        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            harness.clock().set(fixture.state().expiresAt());

            MirrorSessionDescriptor expired = harness.store().find(
                    fixture.state().scope(),
                    fixture.state().sessionId()).orElseThrow();

            assertThat(expired.status())
                    .isEqualTo(MirrorSessionDescriptor.Status.EXPIRED);
            assertThat(harness.jdbc().queryForObject(
                    "SELECT payload_envelope IS NULL FROM mirror_session_state",
                    Boolean.class)).isTrue();
            assertThat(harness.store().recentAudit(
                    expired.scope(), expired.sessionId(), 10))
                    .extracting(MirrorSessionStateStore.OperationAudit::operation)
                    .containsExactly(
                            MirrorSessionStateStore.Operation.EXPIRE,
                            MirrorSessionStateStore.Operation.CREATE);
        }
    }

    @Test
    void claimAtSessionTtlCommitsErasureBeforeReturningGone() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            harness.clock().set(fixture.state().expiresAt());

            assertStoreFailure(() -> harness.store().claim(claim(
                    fixture.state().scope(),
                    fixture.state().sessionId(), "worker-a", 30)), GONE);

            MirrorSessionDescriptor current = harness.store().find(
                    fixture.state().scope(),
                    fixture.state().sessionId()).orElseThrow();
            assertThat(current.status())
                    .isEqualTo(MirrorSessionDescriptor.Status.EXPIRED);
            assertThat(harness.jdbc().queryForObject(
                    "SELECT payload_envelope IS NULL FROM mirror_session_state",
                    Boolean.class)).isTrue();
            assertThat(harness.store().recentAudit(
                    current.scope(), current.sessionId(), 10))
                    .extracting(MirrorSessionStateStore.OperationAudit::operation)
                    .containsExactly(
                            MirrorSessionStateStore.Operation.EXPIRE,
                            MirrorSessionStateStore.Operation.CREATE);
        }
    }

    @Test
    void commitAtSessionTtlCommitsErasureBeforeReturningGone() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            harness.clock().set(fixture.state().expiresAt().minusSeconds(1));
            MirrorSessionStateStore.ClaimedSession claimed =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-a", 30));
            MirrorSessionPayload candidate = committedPayload(
                    claimed.payload(), fixture.effect(), 100);
            harness.clock().set(fixture.state().expiresAt());

            assertStoreFailure(() -> harness.store().compareAndSet(
                    new MirrorSessionStateStore.CommitCommand(
                            claimed.lease(),
                            claimed.payload().state().fingerprint(),
                            candidate)), GONE);

            MirrorSessionDescriptor current = harness.store().find(
                    fixture.state().scope(),
                    fixture.state().sessionId()).orElseThrow();
            assertThat(current.status())
                    .isEqualTo(MirrorSessionDescriptor.Status.EXPIRED);
            assertThat(current.stateRevision()).isZero();
            assertThat(harness.jdbc().queryForObject(
                    "SELECT payload_envelope IS NULL FROM mirror_session_state",
                    Boolean.class)).isTrue();
            assertThat(harness.store().recentAudit(
                    current.scope(), current.sessionId(), 10))
                    .extracting(MirrorSessionStateStore.OperationAudit::operation)
                    .containsExactly(
                            MirrorSessionStateStore.Operation.EXPIRE,
                            MirrorSessionStateStore.Operation.CREATE);
        }
    }

    @Test
    void auditFailureRollsBackStateHeadAndCiphertextTogether() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            MirrorSessionStateStore.ClaimedSession claimed =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-a", 30));
            MirrorSessionPayload candidate = committedPayload(
                    claimed.payload(), fixture.effect(), 100);
            harness.jdbc().execute(
                    "DROP TABLE mirror_session_operation_audit");

            assertStoreFailure(() -> harness.store().compareAndSet(
                    new MirrorSessionStateStore.CommitCommand(
                            claimed.lease(),
                            claimed.payload().state().fingerprint(),
                            candidate)), UNAVAILABLE);

            MirrorSessionDescriptor current = harness.store().find(
                    fixture.state().scope(),
                    fixture.state().sessionId()).orElseThrow();
            assertThat(current.stateRevision()).isZero();
            assertThat(current.stateFingerprint())
                    .isEqualTo(fixture.state().fingerprint());
        }
    }

    @Test
    void authenticatedEnvelopeTamperingFailsClosedWithoutPayloadDetails() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.store().create(create("create-1", fixture.payload()));
            harness.jdbc().update("""
                    UPDATE mirror_session_state
                       SET payload_envelope = payload_envelope || 'x'
                    """);

            assertStoreFailure(() -> harness.store().claim(claim(
                    fixture.state().scope(),
                    fixture.state().sessionId(), "worker-a", 30)), CORRUPT);
        }
    }

    @Test
    void capacityAdmissionPreservesExactReplayAndRecoversAfterDestroy() throws Exception {
        Fixture firstFixture = fixture("refund-session-capacity-a");
        int payloadBytes = mapper.writeValueAsBytes(firstFixture.payload()).length;
        MirrorSessionCapacityPolicy policy = new MirrorSessionCapacityPolicy(
                1, 1, payloadBytes * 4L, payloadBytes * 4L);
        try (Harness harness = harness(policy)) {
            MirrorSessionStateStore.CreateCommand first =
                    create("create-capacity-a", firstFixture.payload());
            Fixture secondFixture = fixture("refund-session-capacity-b");

            assertThat(harness.store().create(first).disposition())
                    .isEqualTo(MirrorSessionStateStore.CreateDisposition.CREATED);
            assertThat(harness.store().create(first).disposition())
                    .isEqualTo(MirrorSessionStateStore.CreateDisposition.REPLAYED);
            assertStoreFailure(() -> harness.store().create(
                    create("create-capacity-b", secondFixture.payload())),
                    CAPACITY_EXCEEDED);

            MirrorSessionStateStore.CapacitySnapshot capacity =
                    harness.store().capacity();
            assertThat(capacity.activeSessions()).isEqualTo(1);
            assertThat(capacity.retainedPayloadBytes()).isEqualTo(payloadBytes);
            assertThat(capacity.expiredRetainedPayloadBytes()).isZero();
            assertThat(capacity.maximumActiveSessions()).isEqualTo(1);
            assertThat(capacity.maximumRetainedPayloadBytes())
                    .isEqualTo(payloadBytes * 4L);

            harness.store().destroy(
                    firstFixture.state().scope(),
                    firstFixture.state().sessionId());
            assertThat(harness.store().create(
                    create("create-capacity-b", secondFixture.payload()))
                    .disposition())
                    .isEqualTo(MirrorSessionStateStore.CreateDisposition.CREATED);
        }
    }

    @Test
    void competingReplicasCannotOverAdmitTheLastGlobalSession() throws Exception {
        Fixture firstFixture = fixture("refund-session-race-a");
        Fixture secondFixture = fixture("refund-session-race-b");
        int payloadBytes = mapper.writeValueAsBytes(firstFixture.payload()).length;
        MirrorSessionCapacityPolicy policy = new MirrorSessionCapacityPolicy(
                1, 1, payloadBytes * 4L, payloadBytes * 4L);
        try (Harness harness = harness(policy);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            DatabaseMirrorSessionStateStore replica =
                    store(harness, policy);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> first = executor.submit(() -> createAfterBarrier(
                    harness.store(),
                    create("create-race-a", firstFixture.payload()),
                    ready, start));
            Future<Object> second = executor.submit(() -> createAfterBarrier(
                    replica,
                    create("create-race-b", secondFixture.payload()),
                    ready, start));

            assertThat(ready.await(
                    5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(
                            MirrorSessionStateStore.CreateDisposition.CREATED,
                            CAPACITY_EXCEEDED);
            assertThat(harness.store().capacity().activeSessions())
                    .isEqualTo(1);
        }
    }

    @Test
    void payloadGrowthIsRejectedBeforeChangingStateOrCiphertext() throws Exception {
        Fixture fixture = fixture();
        int initialBytes = mapper.writeValueAsBytes(fixture.payload()).length;
        MirrorSessionCapacityPolicy policy = new MirrorSessionCapacityPolicy(
                10, 10, initialBytes, initialBytes);
        try (Harness harness = harness(policy)) {
            harness.store().create(create("create-1", fixture.payload()));
            MirrorSessionStateStore.ClaimedSession claimed =
                    harness.store().claim(claim(
                            fixture.state().scope(),
                            fixture.state().sessionId(), "worker-a", 30));
            MirrorSessionPayload candidate = committedPayload(
                    claimed.payload(), fixture.effect(), 100);

            assertThat(mapper.writeValueAsBytes(candidate).length)
                    .isGreaterThan(initialBytes);
            assertStoreFailure(() -> harness.store().compareAndSet(
                    new MirrorSessionStateStore.CommitCommand(
                            claimed.lease(),
                            claimed.payload().state().fingerprint(),
                            candidate)), CAPACITY_EXCEEDED);

            MirrorSessionDescriptor current = harness.store().find(
                    fixture.state().scope(),
                    fixture.state().sessionId()).orElseThrow();
            assertThat(current.stateRevision()).isZero();
            assertThat(harness.jdbc().queryForObject(
                    "SELECT payload_bytes FROM mirror_session_state",
                    Long.class)).isEqualTo(initialBytes);
        }
    }

    @Test
    void expiredButUnerasedCiphertextStillConsumesPhysicalByteCapacity()
            throws Exception {
        Fixture firstFixture = fixture("refund-session-expired-a");
        Fixture secondFixture = fixture(
                "refund-session-expired-b", NOW.plusSeconds(7_200));
        int payloadBytes = mapper.writeValueAsBytes(firstFixture.payload()).length;
        MirrorSessionCapacityPolicy policy = new MirrorSessionCapacityPolicy(
                2, 2, payloadBytes, payloadBytes);
        try (Harness harness = harness(policy)) {
            harness.store().create(
                    create("create-expired-a", firstFixture.payload()));
            harness.clock().set(firstFixture.state().expiresAt());

            MirrorSessionStateStore.CapacitySnapshot retained =
                    harness.store().capacity();
            assertThat(retained.activeSessions()).isZero();
            assertThat(retained.retainedPayloadBytes()).isEqualTo(payloadBytes);
            assertThat(retained.expiredRetainedPayloadBytes())
                    .isEqualTo(payloadBytes);
            assertStoreFailure(() -> harness.store().create(
                    create("create-expired-b", secondFixture.payload())),
                    CAPACITY_EXCEEDED);

            harness.store().find(
                    firstFixture.state().scope(),
                    firstFixture.state().sessionId()).orElseThrow();
            assertThat(harness.store().create(
                    create("create-expired-b", secondFixture.payload()))
                    .disposition())
                    .isEqualTo(MirrorSessionStateStore.CreateDisposition.CREATED);
        }
    }

    @Test
    void boundedExpirySweepErasesDueCiphertextAndReleasesRetainedBytes()
            throws Exception {
        Fixture firstFixture = fixture("refund-session-sweep-a");
        Fixture secondFixture = fixture("refund-session-sweep-b");
        int payloadBytes = mapper.writeValueAsBytes(firstFixture.payload()).length;
        MirrorSessionCapacityPolicy policy = new MirrorSessionCapacityPolicy(
                2, 2, payloadBytes * 2L, payloadBytes * 2L);
        try (Harness harness = harness(policy)) {
            harness.store().create(
                    create("create-sweep-a", firstFixture.payload()));
            harness.store().create(
                    create("create-sweep-b", secondFixture.payload()));
            harness.clock().set(firstFixture.state().expiresAt());

            assertThat(harness.store().expireDue(1)).isEqualTo(1);
            MirrorSessionStateStore.CapacitySnapshot halfway =
                    harness.store().capacity();
            assertThat(halfway.activeSessions()).isZero();
            assertThat(halfway.retainedPayloadBytes()).isEqualTo(payloadBytes);
            assertThat(halfway.expiredRetainedPayloadBytes())
                    .isEqualTo(payloadBytes);

            assertThat(harness.store().expireDue(10)).isEqualTo(1);
            assertThat(harness.store().expireDue(10)).isZero();
            MirrorSessionStateStore.CapacitySnapshot complete =
                    harness.store().capacity();
            assertThat(complete.retainedPayloadBytes()).isZero();
            assertThat(complete.expiredRetainedPayloadBytes()).isZero();
            assertThat(harness.jdbc().queryForObject("""
                    SELECT COUNT(*)
                      FROM mirror_session_state
                     WHERE status = 'EXPIRED'
                       AND payload_envelope IS NULL
                       AND payload_bytes = 0
                    """, Long.class)).isEqualTo(2);
        }

        try (Harness harness = harness()) {
            assertThatThrownBy(() -> harness.store().expireDue(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> harness.store().expireDue(1_001))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private SessionStateSpace.TransactionReceipt executeRefund(
            Harness harness,
            MirrorSessionStateStore.ClaimedSession claimed,
            WriteEffectSpec effect,
            int amount) {
        MirrorSessionPayload payload = claimed.payload();
        MirrorStateTransactionEngine engine = new MirrorStateTransactionEngine(
                mapper, payload.stateModel(), payload.state(),
                MirrorStateBaselineResolver.none(),
                Clock.fixed(harness.clock().get(), ZoneOffset.UTC),
                (expected, candidate) -> {
                    MirrorSessionPayload sealed =
                            MirrorSessionProtocolIntegrity.seal(
                                    mapper, payload.withState(candidate));
                    harness.store().compareAndSet(
                            new MirrorSessionStateStore.CommitCommand(
                                    claimed.lease(), expected.fingerprint(), sealed));
                });
        return engine.execute(effect, Map.of(
                "requestId", "REQ-1",
                "orderId", "O-100",
                "amount", amount));
    }

    private MirrorSessionPayload committedPayload(
            MirrorSessionPayload payload,
            WriteEffectSpec effect,
            int amount) {
        MirrorStateTransactionEngine engine = new MirrorStateTransactionEngine(
                mapper, payload.stateModel(), payload.state(),
                MirrorStateBaselineResolver.none(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                (expected, candidate) -> {
                });
        engine.execute(effect, Map.of(
                "requestId", "REQ-X",
                "orderId", "O-100",
                "amount", amount));
        return MirrorSessionProtocolIntegrity.seal(
                mapper, payload.withState(engine.snapshot()));
    }

    private MirrorSessionStateStore.CreateCommand create(
            String requestId, MirrorSessionPayload payload) {
        MirrorSessionCreateRequest request =
                new MirrorSessionCreateRequest("", requestId, payload);
        return new MirrorSessionStateStore.CreateCommand(
                requestId,
                MirrorSessionProtocolIntegrity.createFingerprint(mapper, request),
                payload);
    }

    private static MirrorSessionStateStore.ClaimCommand claim(
            CapabilitySnapshot.Scope scope,
            String sessionId,
            String ownerId,
            long leaseSeconds) {
        return new MirrorSessionStateStore.ClaimCommand(
                scope, sessionId, ownerId, leaseSeconds);
    }

    private Fixture fixture() {
        return fixture("refund-session-1");
    }

    private Fixture fixture(String sessionId) {
        return fixture(sessionId, NOW.plusSeconds(3_600));
    }

    private Fixture fixture(String sessionId, Instant expiresAt) {
        StateModel model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        SessionStateSpace initial = StatefulMirrorProtocolTest.initialState(
                mapper, model, effect);
        SessionStateSpace state = SessionStateSpaceIntegrity.seal(
                mapper, new SessionStateSpace(
                        initial.schemaVersion(), sessionId, initial.scope(),
                        initial.planFingerprint(), initial.stateModelRef(),
                        initial.writeEffectRefs(), initial.stateRevision(),
                        initial.logicalClock(), initial.randomSeed(),
                        initial.entities(), initial.tombstones(),
                        initial.businessKeyIndex(), initial.committedEvents(),
                        initial.processedCommands(), expiresAt,
                        "", ""));
        MirrorSessionPayload payload = MirrorSessionProtocolIntegrity.sealInitial(
                mapper, new MirrorSessionPayload(
                        "", model, List.of(effect), state, ""), NOW);
        return new Fixture(effect, state, payload);
    }

    private Harness harness() {
        return harness(MirrorSessionCapacityPolicy.defaults());
    }

    private Harness harness(MirrorSessionCapacityPolicy policy) {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        AtomicReference<Instant> clock = new AtomicReference<>(NOW);
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        MirrorStatePayloadProtector protector =
                MirrorStatePayloadProtector.fromConfiguration(
                        "test", "test=" + Base64.getEncoder()
                                .encodeToString(key));
        DatabaseMirrorSessionStateStore store =
                new DatabaseMirrorSessionStateStore(
                        jdbc, mapper, protector,
                        new DataSourceTransactionManager(database),
                        clock::get, policy,
                        MirrorSessionCapacityTelemetry.noop());
        return new Harness(database, jdbc, clock, store);
    }

    private DatabaseMirrorSessionStateStore store(
            Harness harness, MirrorSessionCapacityPolicy policy) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        MirrorStatePayloadProtector protector =
                MirrorStatePayloadProtector.fromConfiguration(
                        "test", "test=" + Base64.getEncoder()
                                .encodeToString(key));
        return new DatabaseMirrorSessionStateStore(
                harness.jdbc(), mapper, protector,
                new DataSourceTransactionManager(harness.database()),
                harness.clock()::get, policy,
                MirrorSessionCapacityTelemetry.noop());
    }

    private static Object createAfterBarrier(
            DatabaseMirrorSessionStateStore store,
            MirrorSessionStateStore.CreateCommand command,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return store.create(command).disposition();
        } catch (MirrorSessionStateStoreException failure) {
            return failure.code();
        }
    }

    private static void assertStoreFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            MirrorSessionStateStoreException.Code code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        MirrorSessionStateStoreException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code))
                .hasMessage(code.wireCode());
    }

    private record Fixture(
            WriteEffectSpec effect,
            SessionStateSpace state,
            MirrorSessionPayload payload) {
    }

    private record Harness(
            EmbeddedDatabase database,
            JdbcTemplate jdbc,
            AtomicReference<Instant> clock,
            DatabaseMirrorSessionStateStore store
    ) implements AutoCloseable {
        @Override
        public void close() {
            database.shutdown();
        }
    }
}
