package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeEventPublisher;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeFact;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Database-backed payload vault with row-fenced lifecycle transitions and append-only signed events. */
public class DatabaseVisualRunPayloadRepository implements VisualRunPayloadRepository {

    private static final String CREATE_STATE = """
            CREATE TABLE IF NOT EXISTS visual_run_payload_states (
                run_id VARCHAR(255) PRIMARY KEY,
                state VARCHAR(32) NOT NULL,
                revision BIGINT NOT NULL,
                expires_at VARCHAR(64) NOT NULL,
                state_json CLOB NOT NULL
            )
            """;
    private static final String CREATE_BLOB = """
            CREATE TABLE IF NOT EXISTS visual_run_payload_blobs (
                run_id VARCHAR(255) PRIMARY KEY,
                payload_json CLOB NOT NULL
            )
            """;
    private static final String CREATE_EVENTS = """
            CREATE TABLE IF NOT EXISTS visual_run_payload_events (
                run_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                request_id VARCHAR(255) NOT NULL,
                event_json CLOB NOT NULL,
                PRIMARY KEY (run_id, revision),
                UNIQUE (run_id, request_id)
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VisualPayloadGovernancePolicy policy;
    private final VisualEvidenceSigner signer;
    private final VisualChangeEventPublisher changePublisher;
    private final Clock clock;

    public DatabaseVisualRunPayloadRepository(JdbcTemplate jdbc,
                                              ObjectMapper objectMapper,
                                              VisualPayloadGovernancePolicy policy,
                                              VisualEvidenceSigner signer) {
        this(jdbc, objectMapper, policy, signer, VisualChangeEventPublisher.unavailable(), Clock.systemUTC());
    }

    public DatabaseVisualRunPayloadRepository(JdbcTemplate jdbc,
                                              ObjectMapper objectMapper,
                                              VisualPayloadGovernancePolicy policy,
                                              VisualEvidenceSigner signer,
                                              VisualChangeEventPublisher changePublisher) {
        this(jdbc, objectMapper, policy, signer, changePublisher, Clock.systemUTC());
    }

    DatabaseVisualRunPayloadRepository(JdbcTemplate jdbc,
                                       ObjectMapper objectMapper,
                                       VisualPayloadGovernancePolicy policy,
                                       VisualEvidenceSigner signer,
                                       VisualChangeEventPublisher changePublisher,
                                       Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.changePublisher = changePublisher == null
                ? VisualChangeEventPublisher.unavailable() : changePublisher;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @PostConstruct
    void init() {
        jdbc.execute(CREATE_STATE);
        jdbc.execute(CREATE_BLOB);
        jdbc.execute(CREATE_EVENTS);
    }

    @Override
    @Transactional
    public Capture capture(VisualGraphRunRecord record) {
        if (status(record.runId()).isPresent()) {
            throw failure(VisualPayloadGovernanceException.Reason.ALREADY_EXISTS,
                    "Run payload already exists: " + record.runId());
        }
        Instant observedAt = record.createdAt().equals(Instant.EPOCH) ? clock.instant() : record.createdAt();
        VisualPayloadGovernancePolicy.Decision decision = policy.decide(record, observedAt);
        VisualRunPayloadSnapshot snapshot = VisualRunPayloadSnapshot.from(record);
        VisualPayloadRetentionDescriptor descriptor;
        String eventType;
        String state;
        if (decision.retain()) {
            descriptor = new VisualPayloadRetentionDescriptor("", decision.policyId(), decision.policyVersion(),
                    decision.classification(), decision.requiredClearance(), decision.requiredGroups(),
                    VisualPayloadRetentionDescriptor.RETAINED, "payload:" + record.runId(),
                    snapshot.payloadFingerprint(), observedAt, observedAt.plus(decision.retention()));
            jdbc.update("INSERT INTO visual_run_payload_blobs (run_id, payload_json) VALUES (?, ?)",
                    record.runId(), json(snapshot));
            eventType = VisualPayloadLifecycleEvent.CAPTURED;
            state = VisualRunPayloadStatus.AVAILABLE;
        } else {
            descriptor = VisualPayloadRetentionDescriptor.notRetained(decision.policyId(),
                    decision.policyVersion(), decision.classification(), decision.requiredClearance(),
                    decision.requiredGroups(), observedAt);
            eventType = VisualPayloadLifecycleEvent.NOT_RETAINED;
            state = VisualRunPayloadStatus.NOT_RETAINED;
        }
        VisualPayloadLifecycleEvent event = signedEvent(record.runId(), "capture:" + record.runId(), 1,
                eventType, observedAt,
                "resource-gateway", "policy-decision", "", snapshot.payloadFingerprint(), "");
        VisualRunPayloadStatus status = new VisualRunPayloadStatus("", record.runId(), record.tenantId(),
                record.namespace(), record.environment(), state, 1, "", observedAt, descriptor, event);
        jdbc.update("""
                        INSERT INTO visual_run_payload_states
                            (run_id, state, revision, expires_at, state_json) VALUES (?, ?, ?, ?, ?)
                        """, record.runId(), state, 1, descriptor.expiresAt().toString(), json(status));
        insertEvent(event);
        publish(status, event);
        return new Capture(descriptor, status);
    }

    @Override
    @Transactional
    public Access access(String runId, Instant observedAt) {
        VisualRunPayloadStatus status = lockStatus(runId);
        Instant now = observedAt == null ? clock.instant() : observedAt;
        if (status.expiredAt(now)) {
            status = purgeLocked(status, expiryRequest(status), "resource-gateway-retention",
                    "retention-expired", now);
        }
        verify(status.latestEvent());
        VisualRunPayloadSnapshot payload = readPayload(runId).orElse(null);
        if (payload != null && !payload.payloadFingerprint().equals(status.descriptor().payloadFingerprint())) {
            throw failure(VisualPayloadGovernanceException.Reason.CORRUPT,
                    "Run payload fingerprint no longer matches immutable evidence");
        }
        return new Access(status, payload);
    }

    @Override
    public Optional<VisualRunPayloadStatus> status(String runId) {
        return jdbc.query("SELECT state_json FROM visual_run_payload_states WHERE run_id = ?",
                        (rs, rowNum) -> read(rs.getString("state_json"), VisualRunPayloadStatus.class), runId)
                .stream().findFirst();
    }

    @Override
    public List<VisualPayloadLifecycleEvent> events(String runId) {
        return jdbc.query("""
                        SELECT event_json FROM visual_run_payload_events
                        WHERE run_id = ? ORDER BY revision ASC
                        """, (rs, rowNum) -> read(rs.getString("event_json"),
                        VisualPayloadLifecycleEvent.class), runId);
    }

    @Override
    @Transactional
    public VisualRunPayloadStatus placeHold(String runId,
                                            String requestId,
                                            String holdId,
                                            String actorId,
                                            String reason,
                                            Instant occurredAt) {
        requireText(holdId, "holdId");
        VisualRunPayloadStatus current = lockStatus(runId);
        VisualRunPayloadStatus replay = idempotent(current, requestId, VisualPayloadLifecycleEvent.HOLD_PLACED,
                holdId, actorId, reason);
        if (replay != null) return replay;
        if (VisualRunPayloadStatus.LEGAL_HOLD.equals(current.state())) {
            if (current.activeHoldId().equals(holdId)) {
                return current;
            }
            throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                    "A different legal hold is already active");
        }
        if (!VisualRunPayloadStatus.AVAILABLE.equals(current.state())) {
            throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                    "Legal hold requires an available payload");
        }
        return transition(current, VisualRunPayloadStatus.LEGAL_HOLD, VisualPayloadLifecycleEvent.HOLD_PLACED,
                requestId, holdId, holdId, actorId, reason, occurredAt);
    }

    @Override
    @Transactional
    public VisualRunPayloadStatus releaseHold(String runId,
                                              String requestId,
                                              String holdId,
                                              String actorId,
                                              String reason,
                                              Instant occurredAt) {
        VisualRunPayloadStatus current = lockStatus(runId);
        VisualRunPayloadStatus replay = idempotent(current, requestId,
                VisualPayloadLifecycleEvent.HOLD_RELEASED, holdId, actorId, reason);
        if (replay != null) return replay;
        if (!VisualRunPayloadStatus.LEGAL_HOLD.equals(current.state())
                || !current.activeHoldId().equals(holdId)) {
            throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                    "The requested legal hold is not active");
        }
        VisualRunPayloadStatus released = transition(current, VisualRunPayloadStatus.AVAILABLE,
                VisualPayloadLifecycleEvent.HOLD_RELEASED, requestId, "", holdId, actorId, reason, occurredAt);
        Instant now = occurredAt == null ? clock.instant() : occurredAt;
        return released.expiredAt(now)
                ? purgeLocked(released, requestId + ":expiry-purge", actorId,
                "retention-expired-after-hold-release", now) : released;
    }

    @Override
    @Transactional
    public VisualRunPayloadStatus purge(String runId,
                                        String requestId,
                                        String actorId,
                                        String reason,
                                        Instant occurredAt) {
        VisualRunPayloadStatus current = lockStatus(runId);
        VisualRunPayloadStatus replay = idempotent(current, requestId, VisualPayloadLifecycleEvent.PURGED,
                "", actorId, reason);
        return replay == null ? purgeLocked(current, requestId, actorId, reason, occurredAt) : replay;
    }

    @Override
    @Transactional
    public int purgeExpired(Instant observedAt, int limit) {
        Instant now = observedAt == null ? clock.instant() : observedAt;
        int boundedLimit = Math.max(1, Math.min(limit, 1000));
        List<String> runIds = jdbc.queryForList("""
                SELECT run_id FROM visual_run_payload_states
                WHERE state = ? AND expires_at <= ? ORDER BY run_id LIMIT ?
                """, String.class, VisualRunPayloadStatus.AVAILABLE, now.toString(), boundedLimit);
        int purged = 0;
        for (String runId : runIds) {
            VisualRunPayloadStatus locked = lockStatus(runId);
            if (locked.expiredAt(now)) {
                purgeLocked(locked, expiryRequest(locked), "resource-gateway-retention",
                        "retention-expired", now);
                purged++;
            }
        }
        return purged;
    }

    @Override
    public VisualPayloadGovernancePolicy.Descriptor policyDescriptor() {
        return policy.descriptor();
    }

    private VisualRunPayloadStatus purgeLocked(VisualRunPayloadStatus current,
                                               String requestId,
                                               String actorId,
                                               String reason,
                                               Instant occurredAt) {
        if (VisualRunPayloadStatus.LEGAL_HOLD.equals(current.state())) {
            throw failure(VisualPayloadGovernanceException.Reason.LEGAL_HOLD_ACTIVE,
                    "Payload cannot be purged while legal hold is active");
        }
        if (VisualRunPayloadStatus.PURGED.equals(current.state())
                || VisualRunPayloadStatus.NOT_RETAINED.equals(current.state())) {
            return current;
        }
        jdbc.update("DELETE FROM visual_run_payload_blobs WHERE run_id = ?", current.runId());
        return transition(current, VisualRunPayloadStatus.PURGED, VisualPayloadLifecycleEvent.PURGED,
                requestId, "", "", actorId, reason, occurredAt);
    }

    private VisualRunPayloadStatus transition(VisualRunPayloadStatus current,
                                              String newState,
                                              String eventType,
                                              String requestId,
                                              String activeHoldId,
                                              String eventHoldId,
                                              String actorId,
                                              String reason,
                                              Instant occurredAt) {
        Instant at = occurredAt == null ? clock.instant() : occurredAt;
        long revision = current.revision() + 1;
        VisualPayloadLifecycleEvent event = signedEvent(current.runId(), requestId, revision, eventType, at,
                actorId, reason, eventHoldId, current.descriptor().payloadFingerprint(),
                current.latestEvent().eventFingerprint());
        VisualRunPayloadStatus updated = new VisualRunPayloadStatus("", current.runId(), current.tenantId(),
                current.namespace(), current.environment(), newState, revision, activeHoldId, at,
                current.descriptor(), event);
        int changed = jdbc.update("""
                        UPDATE visual_run_payload_states
                        SET state = ?, revision = ?, state_json = ?
                        WHERE run_id = ? AND revision = ?
                        """, newState, revision, json(updated), current.runId(), current.revision());
        if (changed != 1) {
            throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                    "Payload lifecycle changed concurrently");
        }
        insertEvent(event);
        publish(updated, event);
        return updated;
    }

    private VisualRunPayloadStatus lockStatus(String runId) {
        return jdbc.query("""
                        SELECT state_json FROM visual_run_payload_states WHERE run_id = ? FOR UPDATE
                        """, (rs, rowNum) -> read(rs.getString("state_json"),
                        VisualRunPayloadStatus.class), runId).stream().findFirst()
                .orElseThrow(() -> failure(VisualPayloadGovernanceException.Reason.NOT_FOUND,
                        "Run payload was not found: " + runId));
    }

    private Optional<VisualRunPayloadSnapshot> readPayload(String runId) {
        return jdbc.query("SELECT payload_json FROM visual_run_payload_blobs WHERE run_id = ?",
                        (rs, rowNum) -> read(rs.getString("payload_json"), VisualRunPayloadSnapshot.class), runId)
                .stream().findFirst();
    }

    private void insertEvent(VisualPayloadLifecycleEvent event) {
        jdbc.update("""
                        INSERT INTO visual_run_payload_events (run_id, revision, request_id, event_json)
                        VALUES (?, ?, ?, ?)
                        """, event.runId(), event.revision(), event.requestId(), json(event));
    }

    private void publish(VisualRunPayloadStatus status, VisualPayloadLifecycleEvent event) {
        changePublisher.publish(new VisualChangeFact(
                "PAYLOAD_" + event.type(), status.tenantId(), status.namespace(), status.environment(),
                new VisualChangeFact.Aggregate("PAYLOAD_RETENTION", status.runId(), status.revision(),
                        event.eventFingerprint()),
                "/api/integration/runs/" + status.runId() + "/payload-retention", status.state()));
    }

    private VisualPayloadLifecycleEvent signedEvent(String runId,
                                                    String requestId,
                                                    long revision,
                                                    String type,
                                                    Instant occurredAt,
                                                    String actorId,
                                                    String reason,
                                                    String holdId,
                                                    String payloadFingerprint,
                                                    String previousEventFingerprint) {
        VisualPayloadLifecycleEvent unsigned = new VisualPayloadLifecycleEvent("", UUID.randomUUID().toString(),
                requestId, runId, revision, type, occurredAt, actorId, reason, holdId, payloadFingerprint,
                previousEventFingerprint, VisualRunEvidenceSeal.unsigned());
        VisualRunEvidenceSeal seal = signer.seal(unsigned.eventFingerprint());
        if (!seal.signed()) {
            throw failure(VisualPayloadGovernanceException.Reason.SIGNING_UNAVAILABLE,
                    "Payload lifecycle signing authority is unavailable");
        }
        return unsigned.withEvidenceSeal(seal);
    }

    private void verify(VisualPayloadLifecycleEvent event) {
        if (event == null || !signer.verify(event.evidenceSeal(), event.eventFingerprint()).valid()) {
            throw failure(VisualPayloadGovernanceException.Reason.CORRUPT,
                    "Payload lifecycle signature verification failed");
        }
    }

    private VisualRunPayloadStatus idempotent(VisualRunPayloadStatus current,
                                              String requestId,
                                              String expectedType,
                                              String expectedHoldId,
                                              String expectedActorId,
                                              String expectedReason) {
        requireText(requestId, "requestId");
        VisualPayloadLifecycleEvent existing = jdbc.query("""
                        SELECT event_json FROM visual_run_payload_events
                        WHERE run_id = ? AND request_id = ?
                        """, (rs, rowNum) -> read(rs.getString("event_json"),
                        VisualPayloadLifecycleEvent.class), current.runId(), requestId)
                .stream().findFirst().orElse(null);
        if (existing == null) return null;
        verify(existing);
        if (expectedType.equals(existing.type())
                && expectedHoldId.equals(existing.holdId())
                && normalized(expectedActorId).equals(existing.actorId())
                && normalized(expectedReason).equals(existing.reason())) return current;
        throw failure(VisualPayloadGovernanceException.Reason.HOLD_CONFLICT,
                "Payload lifecycle request id identifies different immutable content");
    }

    private static String expiryRequest(VisualRunPayloadStatus status) {
        return "expiry:" + status.runId() + ":" + status.descriptor().expiresAt();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize governed run payload", failure);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException failure) {
            throw new VisualPayloadGovernanceException(VisualPayloadGovernanceException.Reason.CORRUPT,
                    "Failed to deserialize governed run payload: " + failure.getOriginalMessage());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static VisualPayloadGovernanceException failure(VisualPayloadGovernanceException.Reason reason,
                                                            String message) {
        return new VisualPayloadGovernanceException(reason, message);
    }
}
