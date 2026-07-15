package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadConflictException;
import com.leanowtech.bloge.gateway.testing.api.ReplayPayloadDescriptor;
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

/** Database replay vault that retains immutable metadata after expiring the detached value. */
public final class DatabaseReplayPayloadRepository implements ReplayPayloadRepository {

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

    /** Creates the replay vault and bounded retention-sweep index. */
    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_TABLE);
        jdbc.execute(CREATE_EXPIRY_INDEX);
    }

    @Override
    public StoredReplayPayload create(StoredReplayPayload payload) {
        validateCreate(payload);
        ReplayPayloadDescriptor descriptor = payload.descriptor();
        try {
            jdbc.update("""
                    INSERT INTO test_replay_payloads
                        (tenant_id, environment_id, replay_payload_id, revision, fingerprint,
                         classification, state, expires_at, descriptor_json, payload_json,
                         stored_at, stored_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, payload.tenantId(), payload.environmentId(), descriptor.replayPayloadId(),
                    descriptor.revision(), descriptor.fingerprint(), descriptor.classification(),
                    StoredReplayPayload.AVAILABLE, Timestamp.from(descriptor.expiresAt()), json(descriptor),
                    json(payload.value()), Timestamp.from(payload.storedAt()), payload.storedBy());
            return payload;
        } catch (DuplicateKeyException conflict) {
            StoredReplayPayload existing = find(payload.tenantId(), payload.environmentId(),
                    descriptor.replayPayloadId(), descriptor.revision()).orElseThrow(() -> conflict);
            if (descriptor.fingerprint().equals(existing.descriptor().fingerprint())) {
                return existing;
            }
            throw new ReplayPayloadConflictException(
                    "Replay payload revision already identifies different immutable content.");
        }
    }

    @Override
    public Optional<StoredReplayPayload> find(String tenantId, String environmentId,
                                              String replayPayloadId, long revision) {
        Optional<StoredReplayPayload> found = jdbc.query("""
                        SELECT * FROM test_replay_payloads
                        WHERE tenant_id = ? AND environment_id = ?
                          AND replay_payload_id = ? AND revision = ?
                        """, (rs, rowNum) -> read(rs), normalized(tenantId), normalized(environmentId),
                normalized(replayPayloadId), revision).stream().findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StoredReplayPayload payload = found.get();
        if (payload.readable() && !payload.descriptor().expiresAt().isAfter(currentTime())) {
            int changed = jdbc.update("""
                            UPDATE test_replay_payloads
                            SET state = ?, payload_json = NULL
                            WHERE tenant_id = ? AND environment_id = ?
                              AND replay_payload_id = ? AND revision = ? AND state = ?
                            """, StoredReplayPayload.EXPIRED, payload.tenantId(), payload.environmentId(),
                    payload.descriptor().replayPayloadId(), payload.descriptor().revision(),
                    StoredReplayPayload.AVAILABLE);
            if (changed == 1) {
                return Optional.of(payload.expired());
            }
            return jdbc.query("""
                            SELECT * FROM test_replay_payloads
                            WHERE tenant_id = ? AND environment_id = ?
                              AND replay_payload_id = ? AND revision = ?
                            """, (rs, rowNum) -> read(rs), payload.tenantId(), payload.environmentId(),
                    payload.descriptor().replayPayloadId(), payload.descriptor().revision())
                    .stream().findFirst();
        }
        return found;
    }

    @Override
    public int purgeExpired(int limit) {
        int bounded = Math.max(1, Math.min(1_000, limit));
        Instant now = currentTime();
        List<Key> expired = jdbc.query("""
                        SELECT tenant_id, environment_id, replay_payload_id, revision
                        FROM test_replay_payloads
                        WHERE state = ? AND expires_at <= ?
                        ORDER BY expires_at, tenant_id, environment_id, replay_payload_id, revision
                        LIMIT ?
                        """, (rs, rowNum) -> new Key(rs.getString("tenant_id"),
                        rs.getString("environment_id"), rs.getString("replay_payload_id"),
                        rs.getLong("revision")), StoredReplayPayload.AVAILABLE, Timestamp.from(now), bounded);
        int purged = 0;
        for (Key key : expired) {
            purged += jdbc.update("""
                            UPDATE test_replay_payloads
                            SET state = ?, payload_json = NULL
                            WHERE tenant_id = ? AND environment_id = ?
                              AND replay_payload_id = ? AND revision = ?
                              AND state = ? AND expires_at <= ?
                            """, StoredReplayPayload.EXPIRED, key.tenantId(), key.environmentId(),
                    key.replayPayloadId(), key.revision(), StoredReplayPayload.AVAILABLE,
                    Timestamp.from(now));
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

    private StoredReplayPayload read(ResultSet rs) throws SQLException {
        ReplayPayloadDescriptor descriptor = readJson(
                rs.getString("descriptor_json"), ReplayPayloadDescriptor.class);
        String payloadJson = rs.getString("payload_json");
        boolean available = payloadJson != null;
        Object value = available ? readJson(payloadJson, Object.class) : null;
        return new StoredReplayPayload("", rs.getString("tenant_id"),
                rs.getString("environment_id"), descriptor, rs.getString("state"), available,
                value, rs.getTimestamp("stored_at").toInstant(), rs.getString("stored_by"));
    }

    private void validateCreate(StoredReplayPayload payload) {
        if (payload == null || payload.descriptor() == null || payload.tenantId().isBlank()
                || payload.environmentId().isBlank() || payload.storedBy().isBlank()
                || !StoredReplayPayload.AVAILABLE.equals(payload.state()) || !payload.payloadAvailable()
                || payload.descriptor().replayPayloadId().isBlank()
                || payload.descriptor().revision() <= 0
                || payload.descriptor().fingerprint().isBlank()
                || !payload.descriptor().expiresAt().isAfter(payload.storedAt())) {
            throw new IllegalArgumentException("A fully identified, unexpired replay payload is required.");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize replay payload.", failure);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to deserialize replay payload.", failure);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record Key(String tenantId, String environmentId, String replayPayloadId, long revision) {
    }
}
