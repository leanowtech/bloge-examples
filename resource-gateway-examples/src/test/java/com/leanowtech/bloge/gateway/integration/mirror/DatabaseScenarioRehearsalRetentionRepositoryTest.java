package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseScenarioRehearsalRetentionRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            MirrorPersistenceTestFixtures.scope("org-a");
    private static final Instant NOW =
            ScenarioRehearsalEvidenceTestFixtures.STARTED;

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            new InMemoryVisualEvidenceSigner();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AtomicReference<Instant> databaseTime;
    private DatabaseScenarioRehearsalLifecycleAuditRepository
            lifecycleAudit;
    private DatabaseScenarioRehearsalRunRepository requests;
    private DatabaseScenarioRehearsalEvidenceRepository evidence;
    private DatabaseScenarioRehearsalRetentionRepository retention;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(database));
        databaseTime = new AtomicReference<>(NOW);
        lifecycleAudit =
                new DatabaseScenarioRehearsalLifecycleAuditRepository(
                        jdbc);
        lifecycleAudit.init();
        requests = new DatabaseScenarioRehearsalRunRepository(
                jdbc, mapper, lifecycleAudit,
                databaseTime::get);
        requests.init();
        evidence = new DatabaseScenarioRehearsalEvidenceRepository(
                jdbc, mapper, evidenceIntegrity());
        evidence.init();
        retention =
                new DatabaseScenarioRehearsalRetentionRepository(
                        jdbc, mapper, signer, databaseTime::get);
        retention.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void preservesIndependentHoldsAndProducesSignedScopedDeletionProof() {
        ScenarioRehearsalEvidenceBundle bundle =
                register(NOW.plusSeconds(10));
        databaseTime.set(NOW.plusSeconds(2));
        hold(
                "hold-command-a", "legal-a",
                "RG.MIRROR.REHEARSAL.LEGAL_HOLD");
        databaseTime.set(NOW.plusSeconds(3));
        hold(
                "hold-command-b", "legal-b",
                "RG.MIRROR.REHEARSAL.INVESTIGATION_HOLD");
        databaseTime.set(NOW.plusSeconds(4));
        release(
                "release-command-a", "legal-a",
                "RG.MIRROR.REHEARSAL.LEGAL_HOLD_RELEASED");

        ScenarioRehearsalRetentionState oneHeld =
                retention.find(
                        SCOPE, bundle.attestation().runId())
                        .orElseThrow();
        assertThat(oneHeld.activeHoldIds())
                .containsExactly("legal-b");
        databaseTime.set(NOW.plusSeconds(11));
        assertThatThrownBy(() -> purge(
                "purge-blocked",
                "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legal hold");

        databaseTime.set(NOW.plusSeconds(12));
        release(
                "release-command-b", "legal-b",
                "RG.MIRROR.REHEARSAL.INVESTIGATION_RELEASED");
        databaseTime.set(NOW.plusSeconds(13));
        ScenarioRehearsalRetentionState purged =
                purge(
                        "purge-command",
                        "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED");

        assertThat(purged.status()).isEqualTo(
                ScenarioRehearsalRetentionState.Status.PURGED);
        assertThat(purged.activeHoldIds()).isEmpty();
        assertThat(purged.deletionProof()).satisfies(proof -> {
            assertThat(proof.deletionProof()).isTrue();
            assertThat(proof.deletedCaseProgressCount())
                    .isEqualTo(1);
            assertThat(proof.childEvidenceDisposition())
                    .isEqualTo(
                            ScenarioRehearsalRetentionEvent
                                    .ChildEvidenceDisposition.RETAINED);
            assertThat(signer.verify(
                    proof.evidenceSeal(),
                    proof.eventFingerprint()).valid()).isTrue();
        });
        assertThat(evidence.find(
                SCOPE, bundle.attestation().runId()))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_rehearsal_case_progress",
                Integer.class)).isZero();
        assertThat(requests.find(
                SCOPE, bundle.result().requestId())).isPresent();
        assertThat(lifecycleAudit.lifecycle(
                SCOPE, bundle.result().requestId(), 20))
                .isNotEmpty();
        assertThat(retention.events(
                SCOPE, bundle.attestation().runId()))
                .extracting(
                        ScenarioRehearsalRetentionEvent::type)
                .containsExactly(
                        ScenarioRehearsalRetentionEvent.Type
                                .RETENTION_REGISTERED,
                        ScenarioRehearsalRetentionEvent.Type
                                .HOLD_PLACED,
                        ScenarioRehearsalRetentionEvent.Type
                                .HOLD_PLACED,
                        ScenarioRehearsalRetentionEvent.Type
                                .HOLD_RELEASED,
                        ScenarioRehearsalRetentionEvent.Type
                                .HOLD_RELEASED,
                        ScenarioRehearsalRetentionEvent.Type.PURGED);
    }

    @Test
    void rejectsEarlyDeletionWithoutRemovingEvidence() {
        ScenarioRehearsalEvidenceBundle bundle =
                register(NOW.plusSeconds(30));
        databaseTime.set(NOW.plusSeconds(29));

        assertThatThrownBy(() -> purge(
                "purge-early",
                "RG.MIRROR.REHEARSAL.RETENTION_EXPIRED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has not elapsed");
        assertThat(evidence.find(
                SCOPE, bundle.attestation().runId()))
                .contains(bundle);
        assertThat(retention.events(
                SCOPE, bundle.attestation().runId()))
                .hasSize(1);
    }

    @Test
    void replaysExactCommandsAndRejectsCommandSemanticDrift() {
        ScenarioRehearsalEvidenceBundle bundle =
                register(NOW.plusSeconds(30));
        databaseTime.set(NOW.plusSeconds(2));
        ScenarioRehearsalRetentionState first =
                hold(
                        "hold-command", "legal-a",
                        "RG.MIRROR.REHEARSAL.LEGAL_HOLD");
        ScenarioRehearsalRetentionState replay =
                hold(
                        "hold-command", "legal-a",
                        "RG.MIRROR.REHEARSAL.LEGAL_HOLD");

        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() ->
                hold(
                        "hold-command", "legal-b",
                        "RG.MIRROR.REHEARSAL.LEGAL_HOLD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different semantics");
        assertThat(retention.events(
                SCOPE, bundle.attestation().runId()))
                .hasSize(2);
    }

    @Test
    void failsClosedWhenProjectionOrSignedChainIsTampered() {
        ScenarioRehearsalEvidenceBundle bundle =
                register(NOW.plusSeconds(30));
        String actualFingerprint = retention.events(
                SCOPE, bundle.attestation().runId())
                .getFirst().eventFingerprint();
        jdbc.update("""
                UPDATE scenario_rehearsal_retention_events
                SET event_fingerprint = ?
                WHERE run_id = ? AND revision = 1
                """,
                ScenarioRehearsalEvidenceTestFixtures
                        .fingerprint('f'),
                bundle.attestation().runId());

        assertThatThrownBy(() -> retention.events(
                SCOPE, bundle.attestation().runId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index differs");

        jdbc.update("""
                UPDATE scenario_rehearsal_retention_events
                SET event_fingerprint = ?
                WHERE run_id = ? AND revision = 1
                """,
                actualFingerprint,
                bundle.attestation().runId());
        jdbc.update("""
                UPDATE scenario_rehearsal_retention_states
                SET revision = 9
                WHERE run_id = ?
                """, bundle.attestation().runId());
        assertThatThrownBy(() -> retention.find(
                SCOPE, bundle.attestation().runId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index differs");
    }

    @Test
    void isolatesRetentionByCompleteEnterpriseScope() {
        ScenarioRehearsalEvidenceBundle bundle =
                register(NOW.plusSeconds(30));

        assertThat(retention.find(
                SCOPE, bundle.attestation().runId())).isPresent();
        assertThat(retention.find(
                MirrorPersistenceTestFixtures.scope("org-b"),
                bundle.attestation().runId())).isEmpty();
    }

    @Test
    void retentionTablesCannotRepresentBusinessPayload() {
        register(NOW.plusSeconds(30));

        assertThat(columns(
                "SCENARIO_REHEARSAL_RETENTION_STATES"))
                .noneMatch(
                        DatabaseScenarioRehearsalRetentionRepositoryTest
                                ::businessPayloadColumn);
        assertThat(columns(
                "SCENARIO_REHEARSAL_RETENTION_EVENTS"))
                .noneMatch(
                        DatabaseScenarioRehearsalRetentionRepositoryTest
                                ::businessPayloadColumn);
    }

    private ScenarioRehearsalEvidenceBundle register(
            Instant retainUntil) {
        ScenarioRehearsalEvidenceBundle bundle = bundle();
        ScenarioRehearsalRunRepository.Registration registration =
                new ScenarioRehearsalRunRepository.Registration(
                        SCOPE,
                        bundle.result().requestId(),
                        ScenarioRehearsalEvidenceTestFixtures
                                .fingerprint('a'),
                        bundle.result().compiledPlanRef(),
                        bundle.attestation().runId(),
                        bundle.result().caseResults().size(),
                        NOW.plus(Duration.ofDays(30)));
        ScenarioRehearsalRunRepository.Claim claim =
                transactions.execute(status -> requests.claim(
                        registration,
                        "owner-a",
                        Duration.ofSeconds(60)));
        databaseTime.set(NOW.plusSeconds(1));
        transactions.executeWithoutResult(status ->
                requests.checkpoint(
                        claim.lease(),
                        bundle.result().caseResults().getFirst()));
        evidence.create(bundle);
        databaseTime.set(NOW.plusSeconds(2));
        return transactions.execute(status -> {
            retention.register(bundle, retainUntil);
            return bundle;
        });
    }

    private ScenarioRehearsalRetentionState hold(
            String commandId,
            String holdId,
            String reasonCode) {
        return transactions.execute(status ->
                retention.placeHold(
                        SCOPE, bundle().attestation().runId(),
                        commandId, holdId, "governance-owner",
                        reasonCode));
    }

    private ScenarioRehearsalRetentionState release(
            String commandId,
            String holdId,
            String reasonCode) {
        return transactions.execute(status ->
                retention.releaseHold(
                        SCOPE, bundle().attestation().runId(),
                        commandId, holdId, "governance-owner",
                        reasonCode));
    }

    private ScenarioRehearsalRetentionState purge(
            String commandId, String reasonCode) {
        return transactions.execute(status ->
                retention.purge(
                        SCOPE, bundle().attestation().runId(),
                        commandId, "retention-worker",
                        reasonCode));
    }

    private ScenarioRehearsalEvidenceBundle bundle() {
        ScenarioRehearsalResult result =
                ScenarioRehearsalEvidenceTestFixtures.result(
                        mapper, SCOPE, '5');
        return evidenceIntegrity().seal(
                ScenarioRehearsalRunIdentity.derive(
                        mapper, SCOPE, result.requestId()),
                result).bundle();
    }

    private ScenarioRehearsalEvidenceIntegrityService
    evidenceIntegrity() {
        return new ScenarioRehearsalEvidenceIntegrityService(
                mapper, signer,
                Clock.fixed(
                        ScenarioRehearsalEvidenceTestFixtures.COMPLETED
                                .plusSeconds(5),
                        ZoneOffset.UTC));
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """, String.class, table);
    }

    private static boolean businessPayloadColumn(String column) {
        return column.contains("PAYLOAD")
                || column.contains("FIXTURE")
                || column.contains("CONTEXT")
                || column.contains("INPUT")
                || column.contains("OUTPUT")
                || column.contains("ENTITY");
    }
}
