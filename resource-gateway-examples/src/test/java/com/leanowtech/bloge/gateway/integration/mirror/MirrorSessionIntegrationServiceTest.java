package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStatePayloadProtector;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionStateStoreException.Code.STATE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorSessionIntegrationServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T02:00:00Z");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void executesCompleteCreateCommandReplayReadDestroyLifecycle() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            MirrorSessionDescriptor created = harness.service().create(
                    create(fixture.payload()), identity());
            MirrorSessionCommandRequest fencedCommand = command(
                    fixture.effect(), "REQ-1", 450,
                    created.stateFingerprint());
            MirrorSessionCommandResult committed = harness.service().command(
                    created.sessionId(),
                    fencedCommand,
                    identity());
            MirrorSessionCommandResult replayed = harness.service().command(
                    created.sessionId(),
                    fencedCommand,
                    identity());
            MirrorSessionDescriptor found = harness.service().find(
                    created.sessionId(), identity());
            MirrorSessionDescriptor destroyed = harness.service().destroy(
                    created.sessionId(), identity());

            assertThat(created.stateRevision()).isZero();
            assertThat(committed.replayed()).isFalse();
            assertThat(committed.receipt().revisionAfter()).isEqualTo(1);
            assertThat(replayed.replayed()).isTrue();
            assertThat(replayed.receipt()).isEqualTo(committed.receipt());
            assertThat(found.stateRevision()).isEqualTo(1);
            assertThat(destroyed.status())
                    .isEqualTo(MirrorSessionDescriptor.Status.DESTROYED);
            assertThat(harness.jdbc().queryForObject(
                    "SELECT payload_envelope IS NULL "
                            + "FROM mirror_session_state",
                    Boolean.class)).isTrue();
        }
    }

    @Test
    void serializesConcurrentCommandsInsideOneReplicaBeforeDatabaseLeasing()
            throws Exception {
        try (Harness harness = harness();
             var executor = Executors.newFixedThreadPool(2)) {
            Fixture fixture = fixture();
            harness.service().create(create(fixture.payload()), identity());
            CountDownLatch start = new CountDownLatch(1);
            Future<MirrorSessionCommandResult> first = executor.submit(() -> {
                start.await();
                return harness.service().command(
                        fixture.state().sessionId(),
                        command(fixture.effect(), "REQ-A", 100),
                        identity());
            });
            Future<MirrorSessionCommandResult> second = executor.submit(() -> {
                start.await();
                return harness.service().command(
                        fixture.state().sessionId(),
                        command(fixture.effect(), "REQ-B", 100),
                        identity());
            });

            start.countDown();
            List<Long> revisions = List.of(
                            first.get().descriptor().stateRevision(),
                            second.get().descriptor().stateRevision())
                    .stream().sorted().toList();

            assertThat(revisions).containsExactly(1L, 2L);
            assertThat(harness.service().find(
                    fixture.state().sessionId(), identity()).stateRevision())
                    .isEqualTo(2);
        }
    }

    @Test
    void completedCommandReleasesLeaseForImmediateCrossReplicaRouting() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.service().create(create(fixture.payload()), identity());
            MirrorSessionIntegrationService replicaB =
                    new MirrorSessionIntegrationService(
                            mapper, harness.store(),
                            MirrorStateBaselineResolver.none(),
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            "replica-b", 30);

            MirrorSessionCommandResult first = harness.service().command(
                    fixture.state().sessionId(),
                    command(fixture.effect(), "REQ-A", 100),
                    identity());
            MirrorSessionCommandResult second = replicaB.command(
                    fixture.state().sessionId(),
                    command(fixture.effect(), "REQ-B", 100),
                    identity());

            assertThat(first.descriptor().stateRevision()).isEqualTo(1);
            assertThat(second.descriptor().stateRevision()).isEqualTo(2);
        }
    }

    @Test
    void hidesCrossScopeAggregatesAndRejectsStaleStateFences() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            assertProblem(() -> harness.service().create(
                    create(fixture.payload()), identity("org-b")),
                    404, "RG.MIRROR.SESSION.NOT_FOUND", false);

            harness.service().create(create(fixture.payload()), identity());
            MirrorSessionCommandRequest stale = new MirrorSessionCommandRequest(
                    MirrorSessionCommandRequest.SCHEMA_VERSION,
                    WriteEffectSpecIntegrity.reference(fixture.effect()),
                    "sha256:" + "8".repeat(64),
                    Map.of(
                            "requestId", "REQ-1",
                            "orderId", "O-100",
                            "amount", 100));
            assertProblem(() -> harness.service().command(
                    fixture.state().sessionId(), stale, identity()),
                    409, STATE_CONFLICT.wireCode(), true);
        }
    }

    @Test
    void preservesDatabaseCasFailureInsteadOfCollapsingItToKernelCommitFailed() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.service().create(create(fixture.payload()), identity());
            MirrorSessionIntegrationService failing =
                    new MirrorSessionIntegrationService(
                            mapper,
                            new FailingCommitStore(harness.store()),
                            MirrorStateBaselineResolver.none(),
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            "replica-a", 30);

            assertProblem(() -> failing.command(
                    fixture.state().sessionId(),
                    command(fixture.effect(), "REQ-1", 100),
                    identity()),
                    409, STATE_CONFLICT.wireCode(), true);
        }
    }

    @Test
    void mapsReplicaCommandBackpressureToStableRetryableCapacityProblem() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.service().create(create(fixture.payload()), identity());
            MirrorSessionCapacityTelemetry telemetry =
                    new MirrorSessionCapacityTelemetry(
                            new SimpleMeterRegistry());
            MirrorSessionCommandAdmission admission =
                    new MirrorSessionCommandAdmission(1, telemetry);
            MirrorSessionIntegrationService bounded =
                    new MirrorSessionIntegrationService(
                            mapper, harness.store(),
                            MirrorStateBaselineResolver.none(),
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            "bounded-replica", 30,
                            admission, telemetry);

            try (MirrorSessionCommandAdmission.Permit ignored =
                         admission.tryAcquire().orElseThrow()) {
                assertProblem(() -> bounded.command(
                        fixture.state().sessionId(),
                        command(fixture.effect(), "REQ-1", 100),
                        identity()),
                        429,
                        MirrorSessionStateStoreException.Code
                                .CAPACITY_EXCEEDED.wireCode(),
                        true);
            }

            assertThat(bounded.command(
                    fixture.state().sessionId(),
                    command(fixture.effect(), "REQ-1", 100),
                    identity()).descriptor().stateRevision()).isEqualTo(1);
        }
    }

    private Fixture fixture() {
        StateModel model = StateModelIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.stateModel());
        WriteEffectSpec effect = WriteEffectSpecIntegrity.seal(
                mapper, StatefulMirrorProtocolTest.refundEffect(model));
        SessionStateSpace state = StatefulMirrorProtocolTest.initialState(
                mapper, model, effect);
        MirrorSessionPayload payload =
                MirrorSessionProtocolIntegrity.sealInitial(
                        mapper,
                        new MirrorSessionPayload(
                                "", model, List.of(effect), state, ""),
                        NOW);
        return new Fixture(effect, state, payload);
    }

    private static MirrorSessionCreateRequest create(
            MirrorSessionPayload payload) {
        return new MirrorSessionCreateRequest(
                MirrorSessionCreateRequest.SCHEMA_VERSION,
                "create-1", payload);
    }

    private static MirrorSessionCommandRequest command(
            WriteEffectSpec effect,
            String requestId,
            int amount) {
        return command(effect, requestId, amount, "");
    }

    private static MirrorSessionCommandRequest command(
            WriteEffectSpec effect,
            String requestId,
            int amount,
            String expectedStateFingerprint) {
        return new MirrorSessionCommandRequest(
                MirrorSessionCommandRequest.SCHEMA_VERSION,
                WriteEffectSpecIntegrity.reference(effect),
                expectedStateFingerprint,
                Map.of(
                        "requestId", requestId,
                        "orderId", "O-100",
                        "amount", amount));
    }

    private static IntegrationRequestContext identity() {
        return identity("org-a");
    }

    private static IntegrationRequestContext identity(String organizationId) {
        return new IntegrationRequestContext(
                "tenant-a", organizationId, "tool-studio", "test", "sg",
                "SERVICE", "mirror-client", "", "MIRROR_REHEARSAL",
                "corr-1", Set.of(), "CONFIDENTIAL", "");
    }

    private Harness harness() {
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
                        clock::get);
        MirrorSessionIntegrationService service =
                new MirrorSessionIntegrationService(
                        mapper, store, MirrorStateBaselineResolver.none(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        "replica-a", 30);
        return new Harness(database, jdbc, store, service);
    }

    private static void assertProblem(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            int status,
            String code,
            boolean retryable) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(status);
                            assertThat(failure.problem().code())
                                    .isEqualTo(code);
                            assertThat(failure.problem().retryable())
                                    .isEqualTo(retryable);
                        });
    }

    private record Fixture(
            WriteEffectSpec effect,
            SessionStateSpace state,
            MirrorSessionPayload payload) {
    }

    private record Harness(
            EmbeddedDatabase database,
            JdbcTemplate jdbc,
            MirrorSessionStateStore store,
            MirrorSessionIntegrationService service
    ) implements AutoCloseable {
        @Override
        public void close() {
            database.shutdown();
        }
    }

    private record FailingCommitStore(
            MirrorSessionStateStore delegate
    ) implements MirrorSessionStateStore {
        @Override
        public CreateResult create(CreateCommand command) {
            return delegate.create(command);
        }

        @Override
        public java.util.Optional<MirrorSessionDescriptor> find(
                CapabilitySnapshot.Scope scope, String sessionId) {
            return delegate.find(scope, sessionId);
        }

        @Override
        public ClaimedSession claim(ClaimCommand command) {
            return delegate.claim(command);
        }

        @Override
        public boolean release(Lease lease) {
            return delegate.release(lease);
        }

        @Override
        public CommitResult compareAndSet(CommitCommand command) {
            throw new MirrorSessionStateStoreException(
                    STATE_CONFLICT, 1);
        }

        @Override
        public DestroyResult destroy(
                CapabilitySnapshot.Scope scope, String sessionId) {
            return delegate.destroy(scope, sessionId);
        }

        @Override
        public int expireDue(int limit) {
            return delegate.expireDue(limit);
        }

        @Override
        public List<OperationAudit> recentAudit(
                CapabilitySnapshot.Scope scope,
                String sessionId,
                int limit) {
            return delegate.recentAudit(scope, sessionId, limit);
        }

        @Override
        public CapacitySnapshot capacity() {
            return delegate.capacity();
        }

        @Override
        public boolean ready() {
            return delegate.ready();
        }
    }
}
