package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.example.DatabaseDynamicRunControlRepository;
import com.leanowtech.bloge.gateway.example.DynamicRunControlRecoveryAdapter;
import com.leanowtech.bloge.gateway.example.DynamicRunControlRepository;
import com.leanowtech.bloge.gateway.example.DynamicRunControlView;
import com.leanowtech.bloge.gateway.example.DynamicRunIntent;
import com.leanowtech.bloge.gateway.integration.DatabaseIntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.FailingIntegrationChangeEventOutbox;
import com.leanowtech.bloge.gateway.integration.IntegrationChangeEvent;
import com.leanowtech.bloge.gateway.integration.RunEvidenceBundle;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisualRunEvidenceRecoveryServiceTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private ObjectMapper objectMapper;
    private DatabaseDynamicRunControlRepository controls;
    private DatabaseIntegrationChangeEventOutbox outbox;
    private DatabaseVisualGraphRunRepository runs;
    private VisualRunEvidenceRecoveryService recovery;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        controls = controls();
        outbox = new DatabaseIntegrationChangeEventOutbox(jdbc, objectMapper);
        ReflectionTestUtils.invokeMethod(outbox, "init");
        runs = runs();
        recovery = recovery(0, 0, controls, runs);
    }

    @Test
    void abandonedOwnerBecomesSignedScopedQuarantinedEvidenceAndOutboxFact() {
        Instant now = Instant.now();
        VisualRunIntent visualIntent = visualIntent("abandoned-evidence");
        VisualRunRecoveryReservation reservation = recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT,
                draft(), "", "", Map.of("score", 720, "apiToken", "plain-secret"), "response", visualIntent)
                .orElseThrow();
        controls.claim(dynamicIntent(visualIntent), "dead-owner", now.minusSeconds(1));

        VisualRunEvidenceRecoveryService.RecoverySweepResult result = recovery.sweepNow(10);

        assertThat(result.recovered()).isEqualTo(1);
        VisualGraphRunRecord record = runs.find(reservation.runId()).orElseThrow();
        assertThat(record.recovery().mode()).isEqualTo(VisualRunRecoveryMetadata.MODE_OWNER_ABANDONED);
        assertThat(record.runControl().reasonCode()).isEqualTo("OWNER_LEASE_EXPIRED");
        assertThat(record.runControl().terminationConfirmed()).isFalse();
        assertThat(record.contextPayload().get("apiToken")).isEqualTo("[REDACTED]");
        assertThat(record.evidenceSeal().signed()).isTrue();
        assertThat(runs.evidenceSigner().verify(record.evidenceSeal(), record.evidenceMaterialFingerprint()).valid())
                .isTrue();

        RunEvidenceBundle evidence = RunEvidenceBundle.from(record, runs.evidenceSigner());
        assertThat(evidence.schemaVersion()).isEqualTo(RunEvidenceBundle.SCHEMA_VERSION);
        assertThat(evidence.recovery().mode()).isEqualTo(VisualRunRecoveryMetadata.MODE_OWNER_ABANDONED);
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("QUARANTINED");
        assertThat(evidence.manifest().signatureStatus()).isEqualTo("VERIFIED");
        assertThat(outbox.read(0, outbox.highWaterSequence(), "tenant-a", "prod", 10))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("RUN_ABANDONED");
                    assertThat(event.aggregate().id()).isEqualTo(record.runId());
                    assertThat(event.payloadRef()).endsWith("/evidence");
                });
    }

    @Test
    void repeatedAndRestartedSweepCannotDuplicateEvidenceOrEvent() {
        VisualRunIntent intent = visualIntent("restart-recovery");
        VisualRunRecoveryReservation reservation = recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT,
                draft(), "", "", Map.of("score", 700), "response", intent).orElseThrow();
        controls.claim(dynamicIntent(intent), "dead-owner", Instant.now().minusSeconds(1));
        assertThat(recovery.sweepNow(10).recovered()).isEqualTo(1);

        DatabaseVisualGraphRunRepository restartedRuns = runs();
        VisualRunEvidenceRecoveryService restarted = recovery(0, 0, controls(), restartedRuns);

        assertThat(restarted.sweepNow(10).recovered()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_graph_run_records", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_change_outbox", Long.class)).isEqualTo(1);
        assertThat(restartedRuns.find(reservation.runId())).isPresent();
    }

    @Test
    void concurrentSweepersHaveExactlyOneTransactionalWinner() {
        VisualRunIntent intent = visualIntent("concurrent-recovery");
        recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "", Map.of(), "response", intent);
        controls.claim(dynamicIntent(intent), "dead-owner", Instant.now().minusSeconds(1));
        VisualRunEvidenceRecoveryService second = recovery(0, 0, controls(), runs());

        CompletableFuture<VisualRunEvidenceRecoveryService.RecoverySweepResult> first =
                CompletableFuture.supplyAsync(() -> recovery.sweepNow(10));
        CompletableFuture<VisualRunEvidenceRecoveryService.RecoverySweepResult> other =
                CompletableFuture.supplyAsync(() -> second.sweepNow(10));

        assertThat(first.join().recovered() + other.join().recovered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_graph_run_records", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_change_outbox", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentEquivalentReservationsConvergeOnOneDeterministicRunId() {
        VisualRunIntent intent = visualIntent("concurrent-reservation");
        VisualRunEvidenceRecoveryService second = recovery(0, 0, controls(), runs());

        CompletableFuture<VisualRunRecoveryReservation> first = CompletableFuture.supplyAsync(() ->
                recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "",
                        Map.of("score", 700), "response", intent).orElseThrow());
        CompletableFuture<VisualRunRecoveryReservation> other = CompletableFuture.supplyAsync(() ->
                second.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "",
                        Map.of("score", 700), "response", intent).orElseThrow());

        assertThat(first.join().runId()).isEqualTo(other.join().runId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_run_recovery_reservations", Long.class))
                .isEqualTo(1);
    }

    @Test
    void normalCompletionConsumesReservationBeforeSweeperAndKeepsRichEvidence() {
        Instant now = Instant.now();
        VisualRunIntent intent = visualIntent("normal-completion");
        recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "",
                Map.of("score", 720), "response", intent);
        DynamicRunControlRepository.Claim claim = controls.claim(dynamicIntent(intent), "owner-a", now.plusSeconds(5));
        DynamicRunControlRepository.State running = controls.start(intent.requestId(), claim.state().owner(), now,
                now.plusSeconds(5)).orElseThrow();
        DynamicRunControlRepository.State finished = controls.finish(intent.requestId(), running.owner(), "SUCCEEDED",
                "EXECUTION_COMPLETED", now.plusMillis(20)).orElseThrow();
        VisualGraphRunResponse response = successfulResponse(visualControl(finished.view()));

        VisualGraphRunRecord stored = recovery.complete(VisualGraphRunRecord.storedDraft(
                draft(), Map.of("score", 720), response));

        assertThat(stored.recovery().recovered()).isFalse();
        assertThat(stored.outputPayload()).isEqualTo(Map.of("decision", "approved"));
        assertThat(recovery.sweepNow(10).recovered()).isZero();
        assertThat(outbox.read(0, outbox.highWaterSequence(), "tenant-a", "prod", 10))
                .extracting(IntegrationChangeEvent::eventType)
                .containsExactly("RUN_COMPLETED");
    }

    @Test
    void terminalControlWithoutEvidenceIsRecoveredAfterCommitGrace() {
        Instant now = Instant.now();
        VisualRunIntent intent = visualIntent("terminal-gap");
        recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "", Map.of(), "response", intent);
        DynamicRunControlRepository.Claim claim = controls.claim(dynamicIntent(intent), "owner-a", now.plusSeconds(5));
        controls.start(intent.requestId(), claim.state().owner(), now, now.plusSeconds(5));
        controls.finish(intent.requestId(), claim.state().owner(), "FAILED", "ENGINE_EXECUTION_FAILED",
                now.minusSeconds(1));

        assertThat(recovery.sweepNow(10).recovered()).isEqualTo(1);
        VisualGraphRunRecord record = runs.all().iterator().next();
        assertThat(record.recovery().mode()).isEqualTo(VisualRunRecoveryMetadata.MODE_TERMINAL_EVIDENCE_GAP);
        RunEvidenceBundle evidence = RunEvidenceBundle.from(record, runs.evidenceSigner());
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("QUARANTINED");
        assertThat(evidence.manifest().gaps()).contains(
                "Run evidence was synthesized from durable recovery facts after the normal evidence transaction did not complete.");
        assertThat(outbox.read(0, outbox.highWaterSequence(), "tenant-a", "prod", 10))
                .extracting(IntegrationChangeEvent::eventType)
                .containsExactly("RUN_EVIDENCE_RECOVERED");
    }

    @Test
    void runRecordAndReservationRollBackWhenRecoveryOutboxAppendFailsThenRetrySucceeds() {
        VisualRunIntent intent = visualIntent("outbox-retry");
        DatabaseVisualGraphRunRepository failingRuns = new DatabaseVisualGraphRunRepository(jdbc, objectMapper,
                new DatabaseVisualEvidenceSigner(jdbc), new FailingIntegrationChangeEventOutbox());
        failingRuns.init();
        VisualRunEvidenceRecoveryService failingRecovery = recovery(0, 0, controls, failingRuns);
        failingRecovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "", Map.of(),
                "response", intent);
        controls.claim(dynamicIntent(intent), "dead-owner", Instant.now().minusSeconds(1));

        VisualRunEvidenceRecoveryService.RecoverySweepResult failed = failingRecovery.sweepNow(10);

        assertThat(failed.failures()).hasSize(1);
        assertThat(failed.failures().getFirst()).contains("simulated outbox failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_graph_run_records", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM visual_run_recovery_reservations WHERE request_id = ?",
                String.class, intent.requestId())).isEqualTo("PENDING");

        assertThat(recovery.sweepNow(10).recovered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_graph_run_records", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_change_outbox", Long.class)).isEqualTo(1);
    }

    @Test
    void missingControlAfterReservationIsRecoveredInsteadOfLeakingPendingState() {
        VisualRunIntent intent = visualIntent("missing-control");
        recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "", Map.of(), "response", intent);

        assertThat(recovery.sweepNow(10).recovered()).isEqualTo(1);
        assertThat(runs.all()).singleElement()
                .satisfies(record -> {
                    assertThat(record.recovery().mode()).isEqualTo(VisualRunRecoveryMetadata.MODE_CONTROL_MISSING);
                    assertThat(record.runControl().status()).isEqualTo("TERMINATION_UNCONFIRMED");
                });
    }

    @Test
    void validationFailureBeforeControlClaimConsumesReservationWithoutSyntheticDuplicate() {
        VisualRunIntent intent = visualIntent("validation-blocked");
        recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "", Map.of(), "response", intent);
        VisualGraphRunResponse blocked = new VisualGraphRunResponse(false, false, false, "visualPolicy", "response",
                null, Map.of(), Map.of(), 0, Map.of(), List.of(), List.of("validation failed"), null, null, "",
                new VisualValidationResult(false, List.of()), "", Map.of(), Map.of(),
                VisualRunControlView.unmanaged());

        VisualGraphRunRecord stored = recovery.complete(
                VisualGraphRunRecord.storedDraft(draft(), Map.of(), blocked), intent.requestId());

        assertThat(stored.recovery().recovered()).isFalse();
        assertThat(recovery.sweepNow(10).recovered()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_graph_run_records", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM visual_run_recovery_reservations WHERE request_id = ?",
                String.class, intent.requestId())).isEqualTo("COMPLETED");
    }

    @Test
    void requestIdCannotBeReusedForDifferentMaterial() {
        VisualRunIntent intent = visualIntent("material-fence");
        recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "",
                Map.of("score", 700), "response", intent);

        assertThatThrownBy(() -> recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "",
                Map.of("score", 701), "response", intent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different run material");
    }

    @Test
    void redactedSecretChangesStillProduceDifferentNonReversibleInputFingerprints() {
        VisualRunIntent intent = visualIntent("secret-material-fence");
        VisualRunRecoveryReservation stored = recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(),
                "", "", Map.of("apiToken", "first-secret"), "response", intent).orElseThrow();

        assertThat(stored.contextPayload().get("apiToken")).isEqualTo("[REDACTED]");
        assertThat(stored.inputFingerprint()).startsWith("sha256:").doesNotContain("first-secret");
        assertThatThrownBy(() -> recovery.reserve(VisualGraphRunRecord.SOURCE_STORED_DRAFT, draft(), "", "",
                Map.of("apiToken", "second-secret"), "response", intent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different run material");
        String json = jdbc.queryForObject(
                "SELECT reservation_json FROM visual_run_recovery_reservations WHERE request_id = ?",
                String.class, intent.requestId());
        assertThat(json).doesNotContain("first-secret").doesNotContain("second-secret");
    }

    private DatabaseDynamicRunControlRepository controls() {
        DatabaseDynamicRunControlRepository repository =
                new DatabaseDynamicRunControlRepository(jdbc, transactions);
        ReflectionTestUtils.invokeMethod(repository, "init");
        return repository;
    }

    private DatabaseVisualGraphRunRepository runs() {
        DatabaseVisualGraphRunRepository repository = new DatabaseVisualGraphRunRepository(jdbc, objectMapper,
                new DatabaseVisualEvidenceSigner(jdbc), outbox);
        repository.init();
        return repository;
    }

    private VisualRunEvidenceRecoveryService recovery(long evidenceGraceMs,
                                                       long missingGraceMs,
                                                       DynamicRunControlRepository controlRepository,
                                                       VisualGraphRunRepository runRepository) {
        VisualRunEvidenceRecoveryService service = new VisualRunEvidenceRecoveryService(jdbc, objectMapper,
                new DynamicRunControlRecoveryAdapter(jdbc, controlRepository), runRepository, transactions,
                evidenceGraceMs, missingGraceMs);
        service.init();
        return service;
    }

    private static VisualRunIntent visualIntent(String requestId) {
        return new VisualRunIntent("", requestId, Instant.now().plusSeconds(30), "fence-" + requestId, 2_000);
    }

    private static DynamicRunIntent dynamicIntent(VisualRunIntent intent) {
        return new DynamicRunIntent("", intent.requestId(), intent.deadlineAt(), intent.fencingToken(),
                intent.cancellationGraceMs());
    }

    private static GraphDraft draft() {
        return new GraphDraft("", "draft-recovery", 7, "visualPolicy", "tenant-a", "local", "prod", "",
                SchemaEnvelope.opaque(), List.of(), List.of(), Map.of(),
                new GraphDraft.OutputSelection("response", ""));
    }

    private static VisualGraphRunResponse successfulResponse(VisualRunControlView control) {
        return new VisualGraphRunResponse(true, true, true, "visualPolicy", "response",
                Map.of("decision", "approved"), Map.of("response", Map.of("decision", "approved")),
                Map.of("response", "COMPLETED"), 20, Map.of("response", 10L), List.of(), List.of(), null, null,
                "graph visualPolicy {}", new VisualValidationResult(true, List.of()), "", Map.of(), Map.of(),
                control);
    }

    private static VisualRunControlView visualControl(DynamicRunControlView source) {
        return new VisualRunControlView("", source.requestId(), source.engineExecutionId(), source.status(),
                source.reasonCode(), source.revision(), source.deadlineAt(), source.startedAt(),
                source.cancelRequestedAt(), source.terminalAt(), source.terminationConfirmed(),
                source.sideEffectsMayBeInFlight());
    }
}
