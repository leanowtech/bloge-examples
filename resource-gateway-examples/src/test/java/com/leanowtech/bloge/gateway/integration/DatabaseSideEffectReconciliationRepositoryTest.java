package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseSideEffectReconciliationRepositoryTest {
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private DatabaseIntegrationChangeEventOutbox outbox;
    private DataSourceTransactionManager transactionManager;
    private VisualEvidenceSigner signer;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        transactionManager = new DataSourceTransactionManager(dataSource);
        signer = new DatabaseVisualEvidenceSigner(jdbc);
        outbox = new DatabaseIntegrationChangeEventOutbox(jdbc, objectMapper);
        outbox.init();
    }

    @Test
    void persistsSignedResultAcrossRepositoryRestartAndPublishesAtomicEvent() {
        DatabaseSideEffectReconciliationRepository first = repository(outbox);
        SideEffectReconciliationRepository.ClaimRequest claim = claim(
                "request-1", "request-fingerprint-1", "owner-1",
                Instant.parse("2026-07-12T12:00:00Z"), Instant.parse("2026-07-12T12:00:30Z"));
        assertThat(first.claim(claim).status())
                .isEqualTo(SideEffectReconciliationRepository.ClaimStatus.ACQUIRED);
        SideEffectReconciliationRecord record = signedRecord("request-1", "request-fingerprint-1");

        first.complete("run-1", "attempt-1", "owner-1", record);
        DatabaseSideEffectReconciliationRepository restarted = repository(outbox);

        assertThat(restarted.find("run-1", "attempt-1")).contains(record);
        assertThat(restarted.findByRequestId("request-1")).contains(record);
        assertThat(restarted.claim(claim).status())
                .isEqualTo(SideEffectReconciliationRepository.ClaimStatus.RESOLVED);
        assertThat(outbox.read(0, outbox.highWaterSequence(), "tenant-a", "prod", 10))
                .singleElement().satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo("SIDE_EFFECT_RECONCILED");
                    assertThat(event.aggregate().fingerprint()).isEqualTo(record.recordFingerprint());
                    assertThat(event.payloadRef()).contains("run-1/side-effects/reconciliations");
                });
    }

    @Test
    void expiredLeaseCanBeTakenOverAndFencingRejectsTheOldOwner() {
        DatabaseSideEffectReconciliationRepository instanceA = repository(outbox);
        DatabaseSideEffectReconciliationRepository instanceB = repository(outbox);
        assertThat(instanceA.claim(claim(
                "request-a", "fingerprint-a", "owner-a",
                Instant.parse("2026-07-12T12:00:00Z"), Instant.parse("2026-07-12T12:00:10Z"))).status())
                .isEqualTo(SideEffectReconciliationRepository.ClaimStatus.ACQUIRED);
        assertThat(instanceB.claim(claim(
                "request-b", "fingerprint-b", "owner-b",
                Instant.parse("2026-07-12T12:00:11Z"), Instant.parse("2026-07-12T12:00:41Z"))).status())
                .isEqualTo(SideEffectReconciliationRepository.ClaimStatus.ACQUIRED);

        assertThatThrownBy(() -> instanceA.complete(
                "run-1", "attempt-1", "owner-a", signedRecord("request-a", "fingerprint-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer owned");
        SideEffectReconciliationRecord winner = signedRecord("request-b", "fingerprint-b");
        assertThat(instanceB.complete("run-1", "attempt-1", "owner-b", winner)).isEqualTo(winner);
    }

    @Test
    void unexpiredSharedClaimPreventsDuplicateProviderWorkAndRequestReuseAcrossTargetsFails() {
        DatabaseSideEffectReconciliationRepository instanceA = repository(outbox);
        DatabaseSideEffectReconciliationRepository instanceB = repository(outbox);
        SideEffectReconciliationRepository.ClaimRequest first = claim(
                "request-1", "fingerprint-1", "owner-a",
                Instant.parse("2026-07-12T12:00:00Z"), Instant.parse("2026-07-12T12:00:30Z"));
        assertThat(instanceA.claim(first).status())
                .isEqualTo(SideEffectReconciliationRepository.ClaimStatus.ACQUIRED);

        SideEffectReconciliationRepository.Claim pending = instanceB.claim(claim(
                "request-2", "fingerprint-2", "owner-b",
                Instant.parse("2026-07-12T12:00:01Z"), Instant.parse("2026-07-12T12:00:31Z")));
        assertThat(pending.status()).isEqualTo(SideEffectReconciliationRepository.ClaimStatus.IN_PROGRESS);
        assertThat(pending.leaseUntil()).isEqualTo(Instant.parse("2026-07-12T12:00:30Z"));

        SideEffectReconciliationRepository.Claim reused = instanceB.claim(new SideEffectReconciliationRepository.ClaimRequest(
                "run-2", "attempt-2", "request-1", "fingerprint-1", "tenant-a", "prod", "owner-b",
                Instant.parse("2026-07-12T12:00:01Z"), Instant.parse("2026-07-12T12:00:31Z")));
        assertThat(reused.status()).isEqualTo(SideEffectReconciliationRepository.ClaimStatus.REQUEST_CONFLICT);
    }

    @Test
    void outboxFailureRollsBackResultAndPersistedTamperingIsRejected() {
        IntegrationChangeEventOutbox failingOutbox = new FailingOutbox(outbox);
        DatabaseSideEffectReconciliationRepository failing = repository(failingOutbox);
        failing.claim(claim("request-1", "fingerprint-1", "owner-1",
                Instant.parse("2026-07-12T12:00:00Z"), Instant.parse("2026-07-12T12:00:30Z")));

        assertThatThrownBy(() -> failing.complete(
                "run-1", "attempt-1", "owner-1", signedRecord("request-1", "fingerprint-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox unavailable");
        assertThat(failing.find("run-1", "attempt-1")).isEmpty();
        assertThat(outbox.highWaterSequence()).isZero();

        DatabaseSideEffectReconciliationRepository healthy = repository(outbox);
        SideEffectReconciliationRecord record = signedRecord("request-1", "fingerprint-1");
        healthy.complete("run-1", "attempt-1", "owner-1", record);
        jdbc.update("""
                UPDATE visual_side_effect_reconciliations
                SET record_json = REPLACE(record_json, 'PROVIDER_STATUS_CONFIRMED', 'TAMPERED_RESULT')
                WHERE run_id = 'run-1' AND attempt_id = 'attempt-1'
                """);

        assertThatThrownBy(() -> healthy.find("run-1", "attempt-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint is invalid");
    }

    @Test
    void reconciliationSchemasMatchSerializedProtocolFields() throws Exception {
        SideEffectReconciliationRequest request = new SideEffectReconciliationRequest(
                "", "request-1", "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64));
        SideEffectReconciliationRecord record = signedRecord(
                request.requestId(), request.requestFingerprint());
        SideEffectReconciliationSummary summary = new SideEffectReconciliationSummary(
                "", "run-1", "evidence:run-1", "sha256:" + "b".repeat(64),
                "RESOLVED", "READY", List.of(), List.of(), List.of(record));

        assertSchemaProperties(objectMapper.valueToTree(request), schema("request").path("properties"));
        JsonNode recordSchema = schema("record");
        JsonNode serializedRecord = objectMapper.valueToTree(record);
        assertSchemaProperties(serializedRecord, recordSchema.path("properties"));
        assertSchemaProperties(serializedRecord.path("baseEvidence"),
                recordSchema.at("/$defs/baseEvidence/properties"));
        assertSchemaProperties(serializedRecord.path("target"), recordSchema.at("/$defs/target/properties"));
        assertSchemaProperties(serializedRecord.path("resolution"),
                recordSchema.at("/$defs/resolution/properties"));
        assertSchemaProperties(serializedRecord.path("evidenceSeal"),
                recordSchema.at("/$defs/evidenceSeal/properties"));
        assertSchemaProperties(objectMapper.valueToTree(summary), schema("summary").path("properties"));
    }

    private DatabaseSideEffectReconciliationRepository repository(IntegrationChangeEventOutbox eventOutbox) {
        DatabaseSideEffectReconciliationRepository repository =
                new DatabaseSideEffectReconciliationRepository(
                        jdbc, objectMapper, eventOutbox, transactionManager);
        repository.init();
        return repository;
    }

    private SideEffectReconciliationRecord signedRecord(String requestId, String requestFingerprint) {
        SideEffectReconciliationRecord unsigned = SideEffectReconciliationRecord.create(
                requestId, requestFingerprint,
                new SideEffectReconciliationRecord.BaseEvidence(
                        "run-1", "evidence:run-1", "sha256:" + "b".repeat(64),
                        "tenant-a", "payments", "prod"),
                new SideEffectReconciliationRecord.Target(
                        "charge", "attempt-1", "sha256:" + "c".repeat(64), "payments.charge",
                        "sha256:" + "A".repeat(43), "payments.status", "vault://commands/charge-42"),
                new SideEffectReconciliationRecord.Resolution(
                        "COMMITTED", new RunEvidenceBundle.SideEffectReceipt(
                                "receipt-42", "payments", "txn-42", Instant.parse("2026-07-12T12:00:20Z"),
                                new RunEvidenceBundle.SideEffectProof(
                                        "kms://receipts/42", "sha256:" + "d".repeat(64))),
                        "PROVIDER_STATUS_CONFIRMED", Instant.parse("2026-07-12T12:00:21Z")),
                new SideEffectReconciliationRecord.Actor(
                        "WORKLOAD", "reconciliation-worker", "", "corr-1"),
                new SideEffectReconciliationRecord.Chain(1, ""));
        return unsigned.withEvidenceSeal(signer.seal(unsigned.recordFingerprint()));
    }

    private static SideEffectReconciliationRepository.ClaimRequest claim(
            String requestId,
            String requestFingerprint,
            String ownerToken,
            Instant claimedAt,
            Instant leaseUntil) {
        return new SideEffectReconciliationRepository.ClaimRequest(
                "run-1", "attempt-1", requestId, requestFingerprint,
                "tenant-a", "prod", ownerToken, claimedAt, leaseUntil);
    }

    private JsonNode schema(String kind) throws Exception {
        return objectMapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "tool-studio-resource-gateway", "side-effect-reconciliation-" + kind + "-v1.schema.json")));
    }

    private static void assertSchemaProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private record FailingOutbox(IntegrationChangeEventOutbox delegate)
            implements IntegrationChangeEventOutbox {
        @Override
        public IntegrationChangeEvent append(IntegrationChangeEvent event) {
            throw new IllegalStateException("outbox unavailable");
        }

        @Override
        public List<IntegrationChangeEvent> read(long afterSequence, long throughSequence,
                                                 String tenantId, String environmentId, int limit) {
            return delegate.read(afterSequence, throughSequence, tenantId, environmentId, limit);
        }

        @Override
        public boolean hasAfter(long afterSequence, long throughSequence,
                                String tenantId, String environmentId) {
            return delegate.hasAfter(afterSequence, throughSequence, tenantId, environmentId);
        }

        @Override
        public long highWaterSequence() {
            return delegate.highWaterSequence();
        }

        @Override
        public boolean available() {
            return delegate.available();
        }
    }
}
