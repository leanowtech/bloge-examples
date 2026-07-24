package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.persistence.MirrorStatePayloadProtector;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateRunSession;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
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
    void graphCommandAdapterReturnsTheDurablePayloadAndExactReplay() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            MirrorSessionDescriptor created =
                    harness.service().create(
                            create(fixture.payload()),
                            identity());
            Map<String, Object> input = Map.of(
                    "requestId", "REQ-GRAPH-1",
                    "orderId", "O-100",
                    "amount", 450);
            MirrorArtifactRef effectRef =
                    WriteEffectSpecIntegrity.reference(
                            fixture.effect());
            String requestFingerprint =
                    "sha256:" + "7".repeat(64);
            MirrorStateRunSession.AttemptContext
                    committedAttempt = attemptContext(
                    "run-request-1", 1,
                    requestFingerprint);
            MirrorStateRunSession.AttemptContext
                    replayedAttempt = attemptContext(
                    "run-request-1", 2,
                    requestFingerprint);

            MirrorStateRunSession.CommandResult committed =
                    harness.service().commandForRun(
                            created.sessionId(),
                            effectRef,
                            input, created.stateFingerprint(),
                            committedAttempt,
                            identity());
            MirrorStateRunSession.CommandResult replayed =
                    harness.service().commandForRun(
                            created.sessionId(),
                            effectRef,
                            input,
                            committed.payload().state()
                                    .fingerprint(),
                            replayedAttempt,
                            identity());
            String committedAttemptId =
                    MirrorStateWriteAttemptIntegrity.attemptId(
                            mapper, created.scope(),
                            created.sessionId(),
                            committedAttempt.coordinate(),
                            effectRef, requestFingerprint);
            String replayedAttemptId =
                    MirrorStateWriteAttemptIntegrity.attemptId(
                            mapper, created.scope(),
                            created.sessionId(),
                            replayedAttempt.coordinate(),
                            effectRef, requestFingerprint);

            assertThat(committed.replayed()).isFalse();
            assertThat(committed.payload().state()
                    .stateRevision()).isEqualTo(1);
            assertThat(committed.descriptor()
                    .stateFingerprint()).isEqualTo(
                    committed.payload().state()
                            .fingerprint());
            assertThat(committed.payload().state()
                    .processedCommands())
                    .contains(committed.receipt());
            assertThat(replayed.replayed()).isTrue();
            assertThat(replayed.payload().fingerprint())
                    .isEqualTo(committed.payload().fingerprint());
            assertThat(replayed.payload().state().fingerprint())
                    .isEqualTo(committed.payload().state().fingerprint());
            assertThat(replayed.payload().state().stateRevision())
                    .isEqualTo(committed.payload().state().stateRevision());
            assertThat(replayed.receipt())
                    .isEqualTo(committed.receipt());
            assertThat(harness.service().writeAttempt(
                    created.sessionId(),
                    committedAttemptId, identity()).outcome())
                    .isEqualTo(
                            MirrorStateWriteOutcomeRunEvidence
                                    .WriteOutcome.COMMITTED);
            assertThat(harness.service().writeAttempt(
                    created.sessionId(),
                    replayedAttemptId, identity()).outcome())
                    .isEqualTo(
                            MirrorStateWriteOutcomeRunEvidence
                                    .WriteOutcome.REPLAYED);
            assertThat(harness.service().snapshotForRun(
                    new MirrorSessionRunBinding(
                            created.sessionId(),
                            committed.payload().state()
                                    .fingerprint()),
                    created.planFingerprint(), identity())
                    .payload().fingerprint())
                    .isEqualTo(committed.payload().fingerprint());
        }
    }

    private static MirrorStateRunSession.AttemptContext
    attemptContext(
            String requestId,
            long leaseEpoch,
            String requestFingerprint) {
        return new MirrorStateRunSession.AttemptContext(
                new MirrorStateWriteAttempt.Coordinate(
                        MirrorStateWriteAttempt.ExecutionKind
                                .GRAPH_RUN,
                        requestId, leaseEpoch,
                        "/root/refund#PRIMARY",
                        "/root", "", 1, 1),
                requestFingerprint);
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
    void bindsOneRunToTheExactAuthenticatedSessionPlanAndStateHead() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            MirrorSessionDescriptor created = harness.service().create(
                    create(fixture.payload()), identity());
            MirrorSessionRunBinding binding = new MirrorSessionRunBinding(
                    created.sessionId(), created.stateFingerprint());

            MirrorSessionStateStore.SessionSnapshot snapshot =
                    harness.service().snapshotForRun(
                            binding, created.planFingerprint(), identity());

            assertThat(snapshot.payload()).isEqualTo(fixture.payload());
            assertThat(snapshot.descriptor()).isEqualTo(created);
            assertProblem(() -> harness.service().snapshotForRun(
                            new MirrorSessionRunBinding(
                                    created.sessionId(),
                                    "sha256:" + "8".repeat(64)),
                            created.planFingerprint(), identity()),
                    409, STATE_CONFLICT.wireCode(), true);
            assertProblem(() -> harness.service().snapshotForRun(
                            binding, "sha256:" + "7".repeat(64), identity()),
                    409, "RG.MIRROR.SESSION.PLAN_CONFLICT", false);
            assertProblem(() -> harness.service().snapshotForRun(
                            binding, created.planFingerprint(),
                            identity("org-b")),
                    404, "RG.MIRROR.SESSION.NOT_FOUND", false);
        }
    }

    @Test
    void signedCheckpointRecoversAfterServiceAndStoreRestart() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            MirrorSessionDescriptor created = harness.service().create(
                    create(fixture.payload()), identity());
            MirrorSessionCheckpointBundle checkpoint =
                    harness.service().checkpoint(
                            created.sessionId(), identity());
            DatabaseMirrorSessionStateStore restartedStore =
                    restartedStore(harness);
            MirrorSessionIntegrationService restartedService =
                    service(restartedStore, harness.signer());

            MirrorSessionRecoveryResult recovered =
                    restartedService.recover(
                            created.sessionId(), checkpoint, identity());

            assertThat(recovered.descriptor()).isEqualTo(created);
            assertThat(recovered.runBinding())
                    .isEqualTo(new MirrorSessionRunBinding(
                            created.sessionId(),
                            created.stateFingerprint()));
            assertThat(recovered.storeGenerationFingerprint())
                    .isEqualTo(restartedStore.generation().fingerprint());
            assertThat(recovered.fingerprint()).startsWith("sha256:");
            assertThat(mapper.writeValueAsString(checkpoint))
                    .doesNotContain("O-100")
                    .doesNotContain("entities")
                    .doesNotContain("processedCommands");
            assertThat(checkpoint.toString())
                    .doesNotContain(checkpoint.attestation().signature())
                    .doesNotContain(checkpoint.checkpoint()
                            .payloadFingerprint());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void recoveryRejectsStaleTamperedAndCrossScopeCheckpoints() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            MirrorSessionDescriptor created = harness.service().create(
                    create(fixture.payload()), identity());
            MirrorSessionCheckpointBundle checkpoint =
                    harness.service().checkpoint(
                            created.sessionId(), identity());

            harness.service().command(
                    created.sessionId(),
                    command(fixture.effect(), "REQ-ADVANCE", 100),
                    identity());
            assertProblem(() -> harness.service().recover(
                            created.sessionId(), checkpoint, identity()),
                    409,
                    "RG.MIRROR.SESSION.CHECKPOINT_STATE_CONFLICT",
                    false);
            assertProblem(() -> harness.service().recover(
                            created.sessionId(), checkpoint,
                            identity("org-b")),
                    404, "RG.MIRROR.SESSION.NOT_FOUND", false);

            com.fasterxml.jackson.databind.node.ObjectNode tampered =
                    mapper.valueToTree(checkpoint);
            ((com.fasterxml.jackson.databind.node.ObjectNode)
                    tampered.path("attestation")).put(
                    "signature", java.util.Base64.getEncoder()
                            .encodeToString(new byte[64]));
            MirrorSessionCheckpointBundle invalid =
                    mapper.treeToValue(
                            tampered,
                            MirrorSessionCheckpointBundle.class);
            assertProblem(() -> harness.service().recover(
                            created.sessionId(), invalid, identity()),
                    400, "RG.MIRROR.SESSION.CHECKPOINT_INVALID",
                    false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void recoveryRejectsAValidCheckpointFromAnotherDataPlaneGeneration() {
        try (Harness source = harness();
             Harness replacement = harness()) {
            Fixture fixture = fixture();
            MirrorSessionDescriptor original = source.service().create(
                    create(fixture.payload()), identity());
            replacement.service().create(
                    create(fixture.payload()), identity());
            MirrorSessionCheckpointBundle checkpoint =
                    source.service().checkpoint(
                            original.sessionId(), identity());
            MirrorSessionIntegrationService trustedReplacement =
                    service(replacement.store(), source.signer());

            assertProblem(() -> trustedReplacement.recover(
                            original.sessionId(), checkpoint, identity()),
                    409,
                    "RG.MIRROR.SESSION.CHECKPOINT_GENERATION_CONFLICT",
                    false);
        }
    }

    @Test
    void checkpointFailsClosedWhenSignerIsUnavailable() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            harness.service().create(
                    create(fixture.payload()), identity());
            MirrorSessionIntegrationService unavailable =
                    new MirrorSessionIntegrationService(
                            mapper, harness.store(),
                            MirrorStateBaselineResolver.none(),
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            "unsigned-replica", 30);

            assertProblem(() -> unavailable.checkpoint(
                            fixture.state().sessionId(), identity()),
                    503,
                    "RG.MIRROR.SESSION.CHECKPOINT_SIGNER_UNAVAILABLE",
                    true);
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
    void refusesWritesWhenDurableAttemptRecoveryIsUnavailable() {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            MirrorSessionDescriptor created =
                    harness.service().create(
                            create(fixture.payload()), identity());
            harness.jdbc().execute(
                    "DROP TABLE mirror_session_write_attempt");

            assertProblem(() -> harness.service().command(
                            created.sessionId(),
                            command(
                                    fixture.effect(),
                                    "REQ-NO-JOURNAL", 100,
                                    created.stateFingerprint()),
                            identity()),
                    503,
                    MirrorSessionStateStoreException.Code
                            .UNAVAILABLE.wireCode(),
                    true);
            assertThat(harness.service().find(
                    created.sessionId(), identity())
                    .stateRevision()).isZero();
        }
    }

    @Test
    void recoversCommittedResultWhenTheDatabaseResponseIsLost()
            throws Exception {
        try (Harness harness = harness()) {
            Fixture fixture = fixture();
            MirrorSessionDescriptor created =
                    harness.service().create(
                            create(fixture.payload()), identity());
            MirrorSessionIntegrationService responseLoss =
                    service(
                            new PostCommitResponseLossStore(
                                    harness.store()),
                            harness.signer());

            MirrorSessionCommandResult committed =
                    responseLoss.command(
                            created.sessionId(),
                            command(
                                    fixture.effect(),
                                    "REQ-RESPONSE-LOSS", 100,
                                    created.stateFingerprint()),
                            identity());

            assertThat(committed.replayed()).isFalse();
            assertThat(committed.descriptor().stateRevision())
                    .isEqualTo(1);
            assertThat(harness.store().snapshot(
                    new MirrorSessionStateStore.SnapshotCommand(
                            created.scope(), created.sessionId()))
                    .payload().state().processedCommands())
                    .hasSize(1);
            assertThat(harness.jdbc().queryForObject("""
                    SELECT COUNT(*)
                      FROM mirror_session_write_attempt
                     WHERE status = 'TERMINAL'
                       AND outcome = 'COMMITTED'
                    """, Long.class)).isEqualTo(1);
            String attemptRecord =
                    harness.jdbc().queryForObject("""
                            SELECT record_json
                              FROM mirror_session_write_attempt
                            """, String.class);
            assertThat(attemptRecord).doesNotContain("corr-1");
            assertThat(mapper.readTree(attemptRecord)
                    .path("coordinate")
                    .path("correlationFingerprint")
                    .asText()).matches("sha256:[a-f0-9]{64}");
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
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        Clock.fixed(NOW, ZoneOffset.UTC));
        MirrorSessionIntegrationService service =
                service(store, signer);
        return new Harness(
                database, jdbc, clock, store, signer, service);
    }

    private DatabaseMirrorSessionStateStore restartedStore(
            Harness harness) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        MirrorStatePayloadProtector protector =
                MirrorStatePayloadProtector.fromConfiguration(
                        "test", "test=" + Base64.getEncoder()
                                .encodeToString(key));
        return new DatabaseMirrorSessionStateStore(
                harness.jdbc(), mapper, protector,
                new DataSourceTransactionManager(harness.database()),
                harness.clock()::get);
    }

    private MirrorSessionIntegrationService service(
            MirrorSessionStateStore store,
            InMemoryVisualEvidenceSigner signer) {
        MirrorSessionCapacityTelemetry telemetry =
                MirrorSessionCapacityTelemetry.noop();
        return new MirrorSessionIntegrationService(
                mapper, store, MirrorStateBaselineResolver.none(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "replica-" + java.util.UUID.randomUUID(), 30,
                new MirrorSessionCommandAdmission(64, telemetry),
                telemetry,
                new MirrorSessionCheckpointIntegrityService(
                        mapper, signer,
                        Clock.fixed(NOW, ZoneOffset.UTC)));
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
            AtomicReference<Instant> clock,
            MirrorSessionStateStore store,
            InMemoryVisualEvidenceSigner signer,
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
        public MirrorSessionStoreGeneration generation() {
            return delegate.generation();
        }

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
        public SessionSnapshot snapshot(SnapshotCommand command) {
            return delegate.snapshot(command);
        }

        @Override
        public CheckpointSnapshot checkpointSnapshot(
                SnapshotCommand command) {
            return delegate.checkpointSnapshot(command);
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
        public MirrorStateWriteAttempt beginWriteAttempt(
                BeginWriteAttemptCommand command) {
            return delegate.beginWriteAttempt(command);
        }

        @Override
        public MirrorStateWriteAttempt completeWriteAttempt(
                CompleteWriteAttemptCommand command) {
            return delegate.completeWriteAttempt(command);
        }

        @Override
        public java.util.Optional<MirrorStateWriteAttempt>
        findWriteAttempt(
                CapabilitySnapshot.Scope scope,
                String sessionId,
                String attemptId) {
            return delegate.findWriteAttempt(
                    scope, sessionId, attemptId);
        }

        @Override
        public boolean writeAttemptReconciliationReady() {
            return true;
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

    private record PostCommitResponseLossStore(
            MirrorSessionStateStore delegate
    ) implements MirrorSessionStateStore {
        @Override
        public MirrorSessionStoreGeneration generation() {
            return delegate.generation();
        }

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
        public SessionSnapshot snapshot(SnapshotCommand command) {
            return delegate.snapshot(command);
        }

        @Override
        public CheckpointSnapshot checkpointSnapshot(
                SnapshotCommand command) {
            return delegate.checkpointSnapshot(command);
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
            delegate.compareAndSet(command);
            throw new MirrorSessionStateStoreException(
                    MirrorSessionStateStoreException.Code
                            .UNAVAILABLE,
                    1);
        }

        @Override
        public MirrorStateWriteAttempt beginWriteAttempt(
                BeginWriteAttemptCommand command) {
            return delegate.beginWriteAttempt(command);
        }

        @Override
        public MirrorStateWriteAttempt completeWriteAttempt(
                CompleteWriteAttemptCommand command) {
            return delegate.completeWriteAttempt(command);
        }

        @Override
        public java.util.Optional<MirrorStateWriteAttempt>
        findWriteAttempt(
                CapabilitySnapshot.Scope scope,
                String sessionId,
                String attemptId) {
            return delegate.findWriteAttempt(
                    scope, sessionId, attemptId);
        }

        @Override
        public int reconcileWriteAttempts(int limit) {
            return delegate.reconcileWriteAttempts(limit);
        }

        @Override
        public boolean writeAttemptReconciliationReady() {
            return true;
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
