package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadConflictException;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadIntegrity;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadIntegrityException;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadRepository;
import com.leanowtech.bloge.gateway.testing.api.StoredReplayPayload;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Database replay vault that retains a verifiable immutable descriptor after value expiry.
 *
 * <p>Every write is detached through canonical JSON before persistence. Reads bind descriptor and
 * envelope content to every searchable column and to a payload-free record commitment. Available
 * rows additionally recompute the descriptor's value fingerprint. Expiry changes state, removes
 * the value, and replaces the record commitment in one compare-and-set update.</p>
 */
public final class DatabaseReplayPayloadRepository implements ReplayPayloadRepository {

    private static final int MIGRATION_PAGE_SIZE = 1_000;
    private static final String COLUMN_LIST = """
            tenant_id, environment_id, replay_payload_id, revision, fingerprint,
            classification, state, expires_at, descriptor_json, payload_json,
            stored_at, stored_by, record_fingerprint
            """;
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS test_replay_payloads (
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
                record_fingerprint VARCHAR(96) NOT NULL,
                PRIMARY KEY (tenant_id, environment_id, replay_payload_id, revision)
            )
            """;
    private static final String CREATE_EXPIRY_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_test_replay_payload_expiry
            ON test_replay_payloads (state, expires_at)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Creates a replay vault over the isolated test-runtime database.
     *
     * @param jdbc isolated test-runtime JDBC client
     * @param objectMapper canonical protocol mapper
     */
    public DatabaseReplayPayloadRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Creates the vault, adds the payload-free commitment column, and upgrades legacy rows.
     *
     * <p>An available legacy row is admitted only after its value fingerprint is recomputed.
     * Historical tombstones have no value to recompute, so migration treats their current canonical
     * descriptor/index projection as the upgrade baseline and commits it before normal reads begin.</p>
     */
    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_TABLE);
        jdbc.execute("""
                ALTER TABLE test_replay_payloads
                ADD COLUMN IF NOT EXISTS record_fingerprint VARCHAR(96) NOT NULL DEFAULT ''
                """);
        migrateLegacyRecordFingerprints();
        jdbc.execute(CREATE_EXPIRY_INDEX);
    }

    @Override
    public StoredReplayPayload create(StoredReplayPayload payload) {
        StoredReplayPayload snapshot = ReplayPayloadIntegrity.verifiedAvailableSnapshot(
                objectMapper, payload);
        ReplayPayloadDescriptor descriptor = snapshot.descriptor();
        String recordFingerprint = ReplayPayloadIntegrity.recordFingerprint(objectMapper, snapshot);
        try {
            jdbc.update("""
                    INSERT INTO test_replay_payloads
                        (tenant_id, environment_id, replay_payload_id, revision, fingerprint,
                         classification, state, expires_at, descriptor_json, payload_json,
                         stored_at, stored_by, record_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, snapshot.tenantId(), snapshot.environmentId(),
                    descriptor.replayPayloadId(), descriptor.revision(), descriptor.fingerprint(),
                    descriptor.classification(), snapshot.state(),
                    Timestamp.from(descriptor.expiresAt()), json(descriptor), json(snapshot.value()),
                    Timestamp.from(snapshot.storedAt()), snapshot.storedBy(), recordFingerprint);
            return snapshot;
        } catch (DuplicateKeyException conflict) {
            StoredReplayPayload existing = find(snapshot.tenantId(), snapshot.environmentId(),
                    descriptor.replayPayloadId(), descriptor.revision()).orElseThrow(() -> conflict);
            if (existing.equals(snapshot)) {
                return ReplayPayloadIntegrity.verifiedCreateReceipt(
                        objectMapper, existing, snapshot);
            }
            throw new ReplayPayloadConflictException(
                    "Replay payload revision already identifies different immutable content.");
        }
    }

    @Override
    public Optional<StoredReplayPayload> find(String tenantId, String environmentId,
                                              String replayPayloadId, long revision) {
        String tenant = normalized(tenantId);
        String environment = normalized(environmentId);
        String payloadId = normalized(replayPayloadId);
        Optional<StoredRow> found = queryExact(tenant, environment, payloadId, revision);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StoredReplayPayload payload = verifiedLookup(found.get(), tenant, environment,
                payloadId, revision);
        Instant observedAt = currentTime();
        if (payload.readable() && !payload.descriptor().expiresAt().isAfter(observedAt)) {
            StoredReplayPayload tombstone = payload.expired();
            String successorFingerprint = ReplayPayloadIntegrity.recordFingerprint(
                    objectMapper, tombstone);
            int changed = jdbc.update("""
                            UPDATE test_replay_payloads
                            SET state = ?, payload_json = NULL, record_fingerprint = ?
                            WHERE tenant_id = ? AND environment_id = ?
                              AND replay_payload_id = ? AND revision = ? AND state = ?
                              AND record_fingerprint = ? AND expires_at <= ?
                            """, StoredReplayPayload.EXPIRED, successorFingerprint,
                    payload.tenantId(), payload.environmentId(),
                    payload.descriptor().replayPayloadId(), payload.descriptor().revision(),
                    StoredReplayPayload.AVAILABLE, found.get().recordFingerprint(),
                    Timestamp.from(observedAt));
            if (changed == 1) {
                return Optional.of(ReplayPayloadIntegrity.verifiedLookup(objectMapper, tombstone,
                        tenant, environment, payloadId, revision));
            }
            return queryExact(tenant, environment, payloadId, revision)
                    .map(row -> verifiedLookup(row, tenant, environment, payloadId, revision));
        }
        return Optional.of(payload);
    }

    @Override
    public int purgeExpired(int limit) {
        int bounded = Math.max(1, Math.min(1_000, limit));
        Instant observedAt = currentTime();
        List<StoredRow> expired = jdbc.query("""
                        SELECT %s FROM test_replay_payloads
                        WHERE state = ? AND expires_at <= ?
                        ORDER BY expires_at, tenant_id, environment_id, replay_payload_id, revision
                        LIMIT ?
                        """.formatted(COLUMN_LIST), (rs, rowNum) -> storedRow(rs),
                StoredReplayPayload.AVAILABLE, Timestamp.from(observedAt), bounded);
        int purged = 0;
        for (StoredRow row : expired) {
            StoredReplayPayload payload = verifyStoredRow(row);
            StoredReplayPayload tombstone = payload.expired();
            String successorFingerprint = ReplayPayloadIntegrity.recordFingerprint(
                    objectMapper, tombstone);
            purged += jdbc.update("""
                            UPDATE test_replay_payloads
                            SET state = ?, payload_json = NULL, record_fingerprint = ?
                            WHERE tenant_id = ? AND environment_id = ?
                              AND replay_payload_id = ? AND revision = ?
                              AND state = ? AND record_fingerprint = ? AND expires_at <= ?
                            """, StoredReplayPayload.EXPIRED, successorFingerprint,
                    payload.tenantId(), payload.environmentId(),
                    payload.descriptor().replayPayloadId(), payload.descriptor().revision(),
                    StoredReplayPayload.AVAILABLE, row.recordFingerprint(),
                    Timestamp.from(observedAt));
        }
        return purged;
    }

    @Override
    public Instant currentTime() {
        Timestamp timestamp = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (timestamp == null) {
            throw new IllegalStateException("Replay payload database did not return current time.");
        }
        return timestamp.toInstant();
    }

    private Optional<StoredRow> queryExact(String tenantId, String environmentId,
                                           String replayPayloadId, long revision) {
        return jdbc.query("""
                        SELECT %s FROM test_replay_payloads
                        WHERE tenant_id = ? AND environment_id = ?
                          AND replay_payload_id = ? AND revision = ?
                        """.formatted(COLUMN_LIST), (rs, rowNum) -> storedRow(rs),
                tenantId, environmentId, replayPayloadId, revision).stream().findFirst();
    }

    private StoredReplayPayload verifiedLookup(StoredRow row, String tenantId,
                                                String environmentId, String replayPayloadId,
                                                long revision) {
        return ReplayPayloadIntegrity.verifiedLookup(objectMapper, verifyStoredRow(row),
                tenantId, environmentId, replayPayloadId, revision);
    }

    private StoredReplayPayload verifyStoredRow(StoredRow row) {
        StoredReplayPayload snapshot = ReplayPayloadIntegrity.verifiedSnapshot(
                objectMapper, row.payload());
        verifyProjection(row, snapshot);
        String actual = ReplayPayloadIntegrity.recordFingerprint(objectMapper, snapshot);
        if (!Objects.equals(actual, row.recordFingerprint())) {
            throw new ReplayPayloadIntegrityException();
        }
        return snapshot;
    }

    private void verifyProjection(StoredRow row, StoredReplayPayload snapshot) {
        ReplayPayloadDescriptor descriptor = snapshot.descriptor();
        if (!Objects.equals(row.tenantId(), snapshot.tenantId())
                || !Objects.equals(row.environmentId(), snapshot.environmentId())
                || !Objects.equals(row.replayPayloadId(), descriptor.replayPayloadId())
                || row.revision() != descriptor.revision()
                || !Objects.equals(row.fingerprint(), descriptor.fingerprint())
                || !Objects.equals(row.classification(), descriptor.classification())
                || !Objects.equals(row.state(), snapshot.state())
                || !Objects.equals(row.expiresAt(), descriptor.expiresAt())) {
            throw new ReplayPayloadIntegrityException();
        }
    }

    private StoredRow storedRow(ResultSet rs) throws SQLException {
        try {
            ReplayPayloadDescriptor descriptor = readJson(
                    rs.getString("descriptor_json"), ReplayPayloadDescriptor.class);
            String payloadJson = rs.getString("payload_json");
            boolean available = payloadJson != null;
            Object value = available ? readJson(payloadJson, Object.class) : null;
            StoredReplayPayload payload = new StoredReplayPayload("",
                    rs.getString("tenant_id"), rs.getString("environment_id"), descriptor,
                    rs.getString("state"), available, value,
                    rs.getTimestamp("stored_at").toInstant(), rs.getString("stored_by"));
            return new StoredRow(rs.getString("tenant_id"), rs.getString("environment_id"),
                    rs.getString("replay_payload_id"), rs.getLong("revision"),
                    rs.getString("fingerprint"), rs.getString("classification"),
                    rs.getString("state"), rs.getTimestamp("expires_at").toInstant(), payload,
                    rs.getString("record_fingerprint"));
        } catch (ReplayPayloadIntegrityException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new ReplayPayloadIntegrityException(invalid);
        }
    }

    private void migrateLegacyRecordFingerprints() {
        while (true) {
            List<StoredRow> legacy = jdbc.query("""
                            SELECT %s FROM test_replay_payloads
                            WHERE record_fingerprint = ''
                            ORDER BY tenant_id, environment_id, replay_payload_id, revision
                            LIMIT ?
                            """.formatted(COLUMN_LIST), (rs, rowNum) -> storedRow(rs),
                    MIGRATION_PAGE_SIZE);
            if (legacy.isEmpty()) {
                return;
            }
            int progressed = 0;
            for (StoredRow row : legacy) {
                StoredReplayPayload snapshot = ReplayPayloadIntegrity.verifiedSnapshot(
                        objectMapper, row.payload());
                verifyProjection(row, snapshot);
                String fingerprint = ReplayPayloadIntegrity.recordFingerprint(
                        objectMapper, snapshot);
                progressed += jdbc.update("""
                                UPDATE test_replay_payloads SET record_fingerprint = ?
                                WHERE tenant_id = ? AND environment_id = ?
                                  AND replay_payload_id = ? AND revision = ?
                                  AND record_fingerprint = ''
                                """, fingerprint, row.tenantId(), row.environmentId(),
                        row.replayPayloadId(), row.revision());
            }
            if (progressed == 0) {
                throw new ReplayPayloadIntegrityException();
            }
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new ReplayPayloadIntegrityException(failure);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new ReplayPayloadIntegrityException(failure);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record StoredRow(
            String tenantId,
            String environmentId,
            String replayPayloadId,
            long revision,
            String fingerprint,
            String classification,
            String state,
            Instant expiresAt,
            StoredReplayPayload payload,
            String recordFingerprint
    ) {
    }
}
