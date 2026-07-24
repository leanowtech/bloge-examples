package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DatabaseScenarioRehearsalBatchRetentionRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            ScenarioRehearsalBatchEvidenceTestFixtures.SCOPE;
    private static final Instant CREATED =
            ScenarioRehearsalBatchEvidenceTestFixtures.CREATED;
    private static final Instant COMPLETED =
            ScenarioRehearsalBatchEvidenceTestFixtures.COMPLETED;

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            new InMemoryVisualEvidenceSigner();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AtomicReference<Instant> databaseTime;
    private ScenarioRehearsalBatchPolicy policy;
    private DatabaseScenarioRehearsalBatchLifecycleAuditRepository
            lifecycle;
    private DatabaseScenarioRehearsalBatchRepository batches;
    private DatabaseScenarioRehearsalBatchEvidenceRepository
            evidence;
    private DatabaseScenarioRehearsalBatchRetentionRepository
            retention;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(database));
        databaseTime = new AtomicReference<>(CREATED);
        policy = ScenarioRehearsalBatchPolicy.defaults();
        lifecycle =
                new DatabaseScenarioRehearsalBatchLifecycleAuditRepository(
                        jdbc);
        lifecycle.init();
        batches = new DatabaseScenarioRehearsalBatchRepository(
                jdbc,
                mapper,
                new DataSourceTransactionManager(database),
                mock(ScenarioRehearsalBatchEvidencePublisher.class),
                lifecycle,
                databaseTime::get);
        batches.init();
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                new ScenarioRehearsalBatchEvidenceIntegrityService(
                        mapper,
                        signer,
                        Clock.fixed(
                                COMPLETED.plusSeconds(1),
                                ZoneOffset.UTC));
        evidence =
                new DatabaseScenarioRehearsalBatchEvidenceRepository(
                        jdbc, mapper, integrity);
        evidence.init();
        retention =
                new DatabaseScenarioRehearsalBatchRetentionRepository(
                        jdbc, mapper, signer, evidence,
                        databaseTime::get);
        retention.init();
        jdbc.execute("""
                CREATE TABLE scenario_rehearsal_evidence (
                    run_id VARCHAR(512) PRIMARY KEY
                )
                """);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void preservesIndependentHoldsAndIssuesSignedScopedDeletionProof() {
        Registered registered = register();
        databaseTime.set(COMPLETED.plusSeconds(2));
        place(
                registered,
                "hold-a-command",
                "legal-a",
                "RG.MIRROR.REHEARSAL_BATCH.LEGAL_HOLD");
        databaseTime.set(COMPLETED.plusSeconds(3));
        place(
                registered,
                "hold-b-command",
                "legal-b",
                "RG.MIRROR.REHEARSAL_BATCH.INVESTIGATION_HOLD");
        databaseTime.set(COMPLETED.plusSeconds(4));
        release(
                registered,
                "release-a-command",
                "legal-a",
                "RG.MIRROR.REHEARSAL_BATCH.LEGAL_HOLD_RELEASED");

        databaseTime.set(registered.retainUntil().plusSeconds(1));
        assertThatThrownBy(() -> purge(
                registered,
                "purge-blocked"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legal hold");

        databaseTime.set(registered.retainUntil().plusSeconds(2));
        release(
                registered,
                "release-b-command",
                "legal-b",
                "RG.MIRROR.REHEARSAL_BATCH.INVESTIGATION_RELEASED");
        databaseTime.set(registered.retainUntil().plusSeconds(3));
        ScenarioRehearsalBatchRetentionState purged =
                purge(registered, "purge-command");

        assertThat(purged.status()).isEqualTo(
                ScenarioRehearsalBatchRetentionState.Status.PURGED);
        assertThat(purged.activeHoldIds()).isEmpty();
        assertThat(purged.deletionProof()).satisfies(proof -> {
            assertThat(proof.deletionProof()).isTrue();
            assertThat(proof.deletedJobCount()).isOne();
            assertThat(proof.deletedItemCount()).isOne();
            assertThat(proof.deletedBatchEvidenceCount()).isOne();
            assertThat(proof.childEvidenceDisposition())
                    .isEqualTo(
                            ScenarioRehearsalBatchRetentionEvent
                                    .PreservedDisposition.RETAINED);
            assertThat(proof.auditDisposition())
                    .isEqualTo(
                            ScenarioRehearsalBatchRetentionEvent
                                    .PreservedDisposition.RETAINED);
            assertThat(signer.verify(
                    proof.evidenceSeal(),
                    proof.eventFingerprint()).valid()).isTrue();
        });
        assertThat(count("scenario_rehearsal_batch_jobs"))
                .isZero();
        assertThat(count("scenario_rehearsal_batch_items"))
                .isZero();
        assertThat(count("scenario_rehearsal_batch_evidence"))
                .isZero();
        assertThat(count("scenario_rehearsal_evidence"))
                .isOne();
        assertThat(lifecycle.lifecycle(
                SCOPE, registered.jobId(), 20))
                .extracting(
                        ScenarioRehearsalBatchLifecycleAuditEvent
                                ::transition)
                .containsExactly(
                        ScenarioRehearsalBatchLifecycleAuditEvent
                                .Transition.ADMITTED);
        assertThat(retention.events(
                SCOPE, registered.jobId()))
                .extracting(
                        ScenarioRehearsalBatchRetentionEvent::type)
                .containsExactly(
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .RETENTION_REGISTERED,
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .HOLD_PLACED,
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .HOLD_PLACED,
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .HOLD_RELEASED,
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .HOLD_RELEASED,
                        ScenarioRehearsalBatchRetentionEvent.Type.PURGED);
    }

    @Test
    void rejectsEarlyDeletionAndExactCommandSemanticDrift() {
        Registered registered = register();
        ScenarioRehearsalBatchRetentionState first =
                place(
                        registered,
                        "hold-command",
                        "legal-a",
                        "RG.MIRROR.REHEARSAL_BATCH.LEGAL_HOLD");
        ScenarioRehearsalBatchRetentionState replay =
                place(
                        registered,
                        "hold-command",
                        "legal-a",
                        "RG.MIRROR.REHEARSAL_BATCH.LEGAL_HOLD");

        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> place(
                registered,
                "hold-command",
                "legal-b",
                "RG.MIRROR.REHEARSAL_BATCH.LEGAL_HOLD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different semantics");
        databaseTime.set(
                registered.retainUntil().minusSeconds(1));
        assertThatThrownBy(() -> {
            release(
                    registered,
                    "release-command",
                    "legal-a",
                    "RG.MIRROR.REHEARSAL_BATCH.LEGAL_HOLD_RELEASED");
            purge(registered, "purge-early");
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not elapsed");
        assertThat(count("scenario_rehearsal_batch_jobs"))
                .isOne();
        assertThat(count("scenario_rehearsal_batch_evidence"))
                .isOne();
    }

    @Test
    void failsClosedWhenItemOrSignedChainIsTampered() {
        Registered registered = register();
        jdbc.update("""
                UPDATE scenario_rehearsal_batch_items
                SET record_fingerprint = ?
                WHERE job_id = ?
                """,
                ScenarioRehearsalBatchEvidenceTestFixtures
                        .fingerprint('9'),
                registered.jobId());
        databaseTime.set(registered.retainUntil().plusSeconds(1));

        assertThatThrownBy(() -> purge(
                registered, "purge-corrupt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
        assertThat(count("scenario_rehearsal_batch_jobs"))
                .isOne();

        String actual = retention.events(
                SCOPE, registered.jobId())
                .getFirst().eventFingerprint();
        jdbc.update("""
                UPDATE scenario_rehearsal_batch_retention_events
                SET event_fingerprint = ?
                WHERE job_id = ? AND revision = 1
                """,
                ScenarioRehearsalBatchEvidenceTestFixtures
                        .fingerprint('8'),
                registered.jobId());
        assertThatThrownBy(() -> retention.events(
                SCOPE, registered.jobId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index differs");
        jdbc.update("""
                UPDATE scenario_rehearsal_batch_retention_events
                SET event_fingerprint = ?
                WHERE job_id = ? AND revision = 1
                """,
                actual,
                registered.jobId());
    }

    @Test
    void isolatesScopeAndKeepsRetentionTablesPayloadFree() {
        Registered registered = register();
        CapabilitySnapshot.Scope other =
                new CapabilitySnapshot.Scope(
                        "tenant-a", "org-b", "support",
                        "test", "sg");

        assertThat(retention.find(
                SCOPE, registered.jobId())).isPresent();
        assertThat(retention.find(
                other, registered.jobId())).isEmpty();
        assertThat(columns(
                "SCENARIO_REHEARSAL_BATCH_RETENTION_STATES"))
                .noneMatch(
                        DatabaseScenarioRehearsalBatchRetentionRepositoryTest
                                ::businessPayloadColumn);
        assertThat(columns(
                "SCENARIO_REHEARSAL_BATCH_RETENTION_EVENTS"))
                .noneMatch(
                        DatabaseScenarioRehearsalBatchRetentionRepositoryTest
                                ::businessPayloadColumn);
    }

    private Registered register() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                fixture =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        ScenarioRehearsalBatchRepository.Submission submission =
                new ScenarioRehearsalBatchRepository.Submission(
                        fixture.request(),
                        ProtocolFingerprint.ofBounded(
                                mapper,
                                fixture.request(),
                                2 * 1024 * 1024),
                        fixture.manifest(),
                        new ScenarioRehearsalBatchPrincipal(
                                SCOPE,
                                "SERVICE",
                                "batch-owner",
                                "",
                                Set.of("support"),
                                "RESTRICTED",
                                ""));
        ScenarioRehearsalBatchJob queued =
                batches.submit(submission, policy).job();
        ScenarioRehearsalBatchJob terminal =
                ScenarioRehearsalBatchIntegrity.seal(
                        mapper,
                        new ScenarioRehearsalBatchJob(
                                "",
                                queued.jobId(),
                                queued.requestId(),
                                queued.requestFingerprint(),
                                queued.manifestFingerprint(),
                                queued.scope(),
                                ScenarioRehearsalBatchJob.Status.SUCCEEDED,
                                queued.failureMode(),
                                queued.priority(),
                                queued.maximumItemAttempts(),
                                new ScenarioRehearsalBatchJob.Summary(
                                        1, 1, 1, 0, 0, 0),
                                queued.deadlineAt(),
                                "", "", "",
                                queued.createdAt(),
                                COMPLETED,
                                COMPLETED,
                                ""));
        ScenarioRehearsalBatchItemPage.Item item =
                fixture.items().getFirst();
        jdbc.update("""
                        UPDATE scenario_rehearsal_batch_jobs
                        SET status = ?, updated_at = ?,
                            record_fingerprint = ?, job_json = ?
                        WHERE job_id = ?
                        """,
                terminal.status().name(),
                Timestamp.from(COMPLETED),
                terminal.recordFingerprint(),
                json(terminal),
                terminal.jobId());
        LinkedHashMap<String, Object> itemMaterial =
                new LinkedHashMap<>();
        itemMaterial.put("item", item);
        itemMaterial.put(
                "executionTimeout",
                fixture.manifest().entries()
                        .getFirst().executionTimeout());
        String itemFingerprint =
                ProtocolFingerprint.ofBounded(
                        mapper, itemMaterial, 128 * 1024);
        jdbc.update("""
                        UPDATE scenario_rehearsal_batch_items
                        SET status = ?, attempt_count = ?,
                            run_id = ?,
                            evidence_bundle_fingerprint = ?,
                            workbook_seed_fingerprint = ?,
                            failure_code = ?, started_at = ?,
                            completed_at = ?, record_fingerprint = ?
                        WHERE job_id = ? AND item_index = 0
                        """,
                item.status().name(),
                item.attemptCount(),
                item.runId(),
                item.evidenceBundleFingerprint(),
                item.workbookSeedFingerprint(),
                item.failureCode(),
                Timestamp.from(item.startedAt()),
                Timestamp.from(item.completedAt()),
                itemFingerprint,
                terminal.jobId());
        ScenarioRehearsalBatchEvidenceIntegrityService integrity =
                new ScenarioRehearsalBatchEvidenceIntegrityService(
                        mapper,
                        signer,
                        Clock.fixed(
                                COMPLETED.plusSeconds(1),
                                ZoneOffset.UTC));
        ScenarioRehearsalBatchEvidenceBundle bundle =
                integrity.seal(
                        fixture.request(),
                        fixture.manifest(),
                        terminal,
                        List.of(item)).bundle();
        evidence.create(bundle);
        jdbc.update(
                "INSERT INTO scenario_rehearsal_evidence (run_id) VALUES (?)",
                item.runId());
        Instant retainUntil = queued.deadlineAt()
                .plus(policy.terminalRetention());
        databaseTime.set(COMPLETED.plusSeconds(1));
        transactions.execute(status ->
                retention.register(bundle, retainUntil));
        return new Registered(
                terminal.jobId(), retainUntil);
    }

    private ScenarioRehearsalBatchRetentionState place(
            Registered registered,
            String commandId,
            String holdId,
            String reasonCode) {
        return transactions.execute(status ->
                retention.placeHold(
                        SCOPE, registered.jobId(),
                        commandId, holdId,
                        "governance-owner", reasonCode));
    }

    private ScenarioRehearsalBatchRetentionState release(
            Registered registered,
            String commandId,
            String holdId,
            String reasonCode) {
        return transactions.execute(status ->
                retention.releaseHold(
                        SCOPE, registered.jobId(),
                        commandId, holdId,
                        "governance-owner", reasonCode));
    }

    private ScenarioRehearsalBatchRetentionState purge(
            Registered registered,
            String commandId) {
        return transactions.execute(status ->
                retention.purge(
                        SCOPE, registered.jobId(),
                        commandId, "governance-owner",
                        "RG.MIRROR.REHEARSAL_BATCH.RETENTION_EXPIRED"));
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                        SELECT COLUMN_NAME
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME = ?
                        ORDER BY ORDINAL_POSITION
                        """,
                String.class, table);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static boolean businessPayloadColumn(
            String column) {
        String value = column.toLowerCase(
                java.util.Locale.ROOT);
        return value.contains("payload")
                || value.contains("fixture")
                || value.contains("input")
                || value.contains("output")
                || value.contains("secret")
                || value.contains("credential")
                || value.contains("exception")
                || value.contains("stack");
    }

    private record Registered(
            String jobId,
            Instant retainUntil) {
    }
}
