package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadConflictException;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadIntegrityException;
import com.leanowtech.bloge.gateway.testing.api.StoredReplayPayload;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseReplayPayloadRepositoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private DatabaseReplayPayloadRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-replay-vault-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseReplayPayloadRepository(jdbc, MAPPER);
        repository.init();
    }

    @Test
    void immutableRevisionRoundTripsNullValueAndRejectsCrossScopeOrOverwrite() {
        Instant now = repository.currentTime();
        StoredReplayPayload stored = payload("tenant-a", "test", "replay-a", 2,
                null, now, now.plusSeconds(30));

        assertThat(repository.create(stored)).isEqualTo(stored);
        assertThat(repository.find("tenant-a", "test", "replay-a", 2))
                .contains(stored);
        assertThat(repository.find("tenant-b", "test", "replay-a", 2)).isEmpty();
        assertThat(repository.find("tenant-a", "staging", "replay-a", 2)).isEmpty();
        assertThat(repository.create(stored)).isEqualTo(stored);

        StoredReplayPayload conflict = payload("tenant-a", "test", "replay-a", 2,
                Map.of("decision", "different"), now, now.plusSeconds(30));
        assertThatThrownBy(() -> repository.create(conflict))
                .isInstanceOf(ReplayPayloadConflictException.class)
                .hasMessageContaining("different immutable content");
    }

    @Test
    void retentionSweepPhysicallyRemovesValueButPreservesPayloadFreeTombstone() throws Exception {
        Instant now = repository.currentTime();
        StoredReplayPayload stored = payload("tenant-a", "test", "replay-expiring", 1,
                Map.of("secret", "already-redacted"),
                now.minusSeconds(1), now.plusMillis(120));
        repository.create(stored);

        Thread.sleep(180);

        assertThat(repository.purgeExpired(10)).isEqualTo(1);
        StoredReplayPayload tombstone = repository.find(
                "tenant-a", "test", "replay-expiring", 1).orElseThrow();
        assertThat(tombstone.state()).isEqualTo(StoredReplayPayload.EXPIRED);
        assertThat(tombstone.payloadAvailable()).isFalse();
        assertThat(tombstone.value()).isNull();
        assertThat(tombstone.descriptor()).isEqualTo(stored.descriptor());
        assertThat(repository.purgeExpired(10)).isZero();
    }

    @Test
    void lookupAlsoExpiresValueWithoutWaitingForScheduledSweep() throws Exception {
        Instant now = repository.currentTime();
        repository.create(payload("tenant-a", "test", "replay-read-expiry", 1,
                List.of(1, 2, 3), now.minusSeconds(1), now.plusMillis(100)));

        Thread.sleep(160);

        StoredReplayPayload tombstone = repository.find(
                "tenant-a", "test", "replay-read-expiry", 1).orElseThrow();
        assertThat(tombstone.state()).isEqualTo(StoredReplayPayload.EXPIRED);
        assertThat(tombstone.readable()).isFalse();
    }

    @Test
    void rejectsForgedPayloadFingerprintBeforeWriting() {
        Instant now = repository.currentTime();
        StoredReplayPayload valid = payload("tenant-a", "test", "replay-forged", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(30));
        ReplayPayloadDescriptor descriptor = valid.descriptor();
        StoredReplayPayload forged = new StoredReplayPayload("", valid.tenantId(),
                valid.environmentId(), new ReplayPayloadDescriptor("", descriptor.replayPayloadId(),
                descriptor.revision(), fingerprint('0'), descriptor.classification(),
                descriptor.source(), descriptor.redaction(), descriptor.capturedAt(),
                descriptor.expiresAt(), descriptor.certificationEligible(),
                descriptor.certificationGaps()), StoredReplayPayload.AVAILABLE, true,
                valid.value(), valid.storedAt(), valid.storedBy());

        assertThatThrownBy(() -> repository.create(forged))
                .isInstanceOf(ReplayPayloadIntegrityException.class)
                .hasMessageNotContaining("approved");
        assertThat(repository.find("tenant-a", "test", "replay-forged", 1)).isEmpty();
    }

    @Test
    void createReturnsCanonicalSnapshotWithoutCallerAliases() {
        Instant now = repository.currentTime();
        List<Object> decisions = new ArrayList<>(List.of("approved"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("decisions", decisions);
        StoredReplayPayload candidate = payload("tenant-a", "test", "replay-alias", 1,
                value, now, now.plusSeconds(30));

        StoredReplayPayload created = repository.create(candidate);
        decisions.add("denied");
        value.put("late", true);

        assertThat(created.value()).isEqualTo(Map.of("decisions", List.of("approved")));
        assertThatThrownBy(() -> ((Map<String, Object>) created.value()).put("late", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(repository.find("tenant-a", "test", "replay-alias", 1))
                .contains(created);
    }

    @Test
    void rejectsIndexedProjectionAndDescriptorJsonDrift() {
        Instant now = repository.currentTime();
        StoredReplayPayload first = repository.create(payload("tenant-a", "test", "replay-index", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(30)));
        jdbc.update("UPDATE test_replay_payloads SET classification = ? WHERE replay_payload_id = ?",
                "PUBLIC", "replay-index");

        assertThatThrownBy(() -> repository.find("tenant-a", "test", "replay-index", 1))
                .isInstanceOf(ReplayPayloadIntegrityException.class);

        StoredReplayPayload second = repository.create(payload("tenant-a", "test", "replay-json", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(30)));
        ReplayPayloadDescriptor changed = new ReplayPayloadDescriptor("", "replay-other", 1,
                second.descriptor().fingerprint(), second.descriptor().classification(),
                second.descriptor().source(), second.descriptor().redaction(),
                second.descriptor().capturedAt(), second.descriptor().expiresAt(),
                second.descriptor().certificationEligible(), second.descriptor().certificationGaps());
        assertThat(first.descriptor().replayPayloadId()).isEqualTo("replay-index");
        assertThat(jdbc.update("UPDATE test_replay_payloads SET descriptor_json = ? "
                        + "WHERE replay_payload_id = ?", json(changed), "replay-json")).isEqualTo(1);

        assertThatThrownBy(() -> repository.find("tenant-a", "test", "replay-json", 1))
                .isInstanceOf(ReplayPayloadIntegrityException.class);

        repository.create(payload("tenant-a", "test", "replay-commitment", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(30)));
        jdbc.update("UPDATE test_replay_payloads SET record_fingerprint = ? "
                + "WHERE replay_payload_id = ?", fingerprint('9'), "replay-commitment");

        assertThatThrownBy(() -> repository.find(
                "tenant-a", "test", "replay-commitment", 1))
                .isInstanceOf(ReplayPayloadIntegrityException.class);
    }

    @Test
    void tombstoneKeepsVerifiableDescriptorAndLifecycleCommitment() throws Exception {
        Instant now = repository.currentTime();
        StoredReplayPayload stored = repository.create(payload("tenant-a", "test",
                "replay-tombstone-integrity", 1, Map.of("decision", "approved"),
                now.minusSeconds(1), now.plusMillis(100)));
        Thread.sleep(160);
        assertThat(repository.purgeExpired(10)).isEqualTo(1);

        ReplayPayloadDescriptor changed = new ReplayPayloadDescriptor("",
                stored.descriptor().replayPayloadId(), stored.descriptor().revision(),
                stored.descriptor().fingerprint(), "PUBLIC", stored.descriptor().source(),
                stored.descriptor().redaction(), stored.descriptor().capturedAt(),
                stored.descriptor().expiresAt(), stored.descriptor().certificationEligible(),
                stored.descriptor().certificationGaps());
        jdbc.update("UPDATE test_replay_payloads SET descriptor_json = ? "
                + "WHERE replay_payload_id = ?", json(changed), "replay-tombstone-integrity");

        assertThatThrownBy(() -> repository.find(
                "tenant-a", "test", "replay-tombstone-integrity", 1))
                .isInstanceOf(ReplayPayloadIntegrityException.class);
    }

    @Test
    void upgradesCanonicalLegacyValueAndTombstoneBeforeServingReads() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-replay-vault-legacy-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate legacyJdbc = new JdbcTemplate(dataSource);
        legacyJdbc.execute("""
                CREATE TABLE test_replay_payloads (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    replay_payload_id VARCHAR(255) NOT NULL,
                    revision BIGINT NOT NULL,
                    fingerprint VARCHAR(96) NOT NULL,
                    classification VARCHAR(32) NOT NULL,
                    state VARCHAR(32) NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    descriptor_json CLOB NOT NULL,
                    payload_json CLOB,
                    stored_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    stored_by VARCHAR(255) NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, replay_payload_id, revision)
                )
                """);
        Instant now = legacyJdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class)
                .toInstant();
        StoredReplayPayload available = payload("tenant-a", "test", "legacy-available", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(60));
        StoredReplayPayload tombstone = payload("tenant-a", "test", "legacy-expired", 1,
                Map.of("decision", "approved"), now, now.plusSeconds(60)).expired();
        insertLegacy(legacyJdbc, available);
        insertLegacy(legacyJdbc, tombstone);

        DatabaseReplayPayloadRepository upgraded =
                new DatabaseReplayPayloadRepository(legacyJdbc, MAPPER);
        upgraded.init();

        assertThat(upgraded.find("tenant-a", "test", "legacy-available", 1))
                .contains(available);
        assertThat(upgraded.find("tenant-a", "test", "legacy-expired", 1))
                .contains(tombstone);
        assertThat(legacyJdbc.queryForList(
                        "SELECT record_fingerprint FROM test_replay_payloads", String.class))
                .allMatch(value -> value.matches("sha256:[a-f0-9]{64}"));
    }

    private static StoredReplayPayload payload(String tenantId, String environmentId,
                                                String id, long revision, Object value,
                                                Instant storedAt, Instant expiresAt) {
        ReplayPayloadDescriptor.Source source = new ReplayPayloadDescriptor.Source(
                "GOVERNED_RUN_NODE_ATTEMPT", "run-a", "fetch", 1,
                fingerprint('e'), fingerprint('f'), environmentId);
        ReplayPayloadDescriptor.Redaction redaction = new ReplayPayloadDescriptor.Redaction(
                "source@1", 1, "capture@1", 0, false, List.of());
        ReplayPayloadDescriptor draft = new ReplayPayloadDescriptor("", id, revision,
                "", "INTERNAL", source, redaction, storedAt, expiresAt, true, List.of());
        String fingerprint = ProtocolFingerprint.of(MAPPER, Map.of("descriptor", draft,
                "value", value == null ? MAPPER.nullNode() : value));
        ReplayPayloadDescriptor descriptor = new ReplayPayloadDescriptor("", id, revision,
                fingerprint, "INTERNAL", source, redaction, storedAt, expiresAt, true, List.of());
        return new StoredReplayPayload("", tenantId, environmentId, descriptor,
                StoredReplayPayload.AVAILABLE, true, value, storedAt, "runner");
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void insertLegacy(JdbcTemplate legacyJdbc, StoredReplayPayload payload) {
        ReplayPayloadDescriptor descriptor = payload.descriptor();
        legacyJdbc.update("""
                        INSERT INTO test_replay_payloads (
                            tenant_id, environment_id, replay_payload_id, revision, fingerprint,
                            classification, state, expires_at, descriptor_json, payload_json,
                            stored_at, stored_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, payload.tenantId(), payload.environmentId(),
                descriptor.replayPayloadId(), descriptor.revision(), descriptor.fingerprint(),
                descriptor.classification(), payload.state(), Timestamp.from(descriptor.expiresAt()),
                json(descriptor), payload.payloadAvailable() ? json(payload.value()) : null,
                Timestamp.from(payload.storedAt()), payload.storedBy());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
