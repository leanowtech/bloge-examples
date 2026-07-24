package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Signed, scope-isolated retention and multi-hold control plane for Scenario aggregate evidence.
 *
 * <p>Signed events are authoritative and the state row is a rebuildable projection. Purge removes
 * only aggregate evidence and aggregate case progress. The request tombstone, lifecycle audit,
 * retention event chain, deletion proof, and child Mirror evidence remain.</p>
 */
public class DatabaseScenarioRehearsalRetentionRepository
        implements ScenarioRehearsalRetentionRepository {
    private static final String CREATE_STATE = """
            CREATE TABLE IF NOT EXISTS scenario_rehearsal_retention_states (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                run_id VARCHAR(512) NOT NULL,
                request_id VARCHAR(256) NOT NULL,
                evidence_bundle_fingerprint VARCHAR(71) NOT NULL,
                status VARCHAR(32) NOT NULL,
                revision BIGINT NOT NULL,
                retain_until VARCHAR(64) NOT NULL,
                state_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, run_id
                )
            )
            """;
    private static final String CREATE_EVENTS = """
            CREATE TABLE IF NOT EXISTS scenario_rehearsal_retention_events (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                run_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                command_id VARCHAR(256) NOT NULL,
                type VARCHAR(32) NOT NULL,
                hold_id VARCHAR(256) NOT NULL,
                event_fingerprint VARCHAR(71) NOT NULL,
                event_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, run_id, revision
                ),
                UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, run_id, command_id
                )
            )
            """;
    private static final String SELECT_STATE = """
            SELECT request_id, evidence_bundle_fingerprint, status,
                   revision, retain_until, state_json
            FROM scenario_rehearsal_retention_states
            WHERE tenant_id = ? AND organization_id = ?
              AND project_id = ? AND environment_id = ?
              AND region = ? AND run_id = ?
            """;
    private static final String SELECT_STATE_FOR_UPDATE =
            SELECT_STATE + " FOR UPDATE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Supplier<Instant> databaseClock;

    /**
     * Creates the production repository using the application database clock.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param signer governed deletion-proof signing authority
     */
    public DatabaseScenarioRehearsalRetentionRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            VisualEvidenceSigner signer) {
        this(jdbc, mapper, signer, null);
    }

    /** Package-private deterministic database-clock seam for focused tests. */
    DatabaseScenarioRehearsalRetentionRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            Supplier<Instant> databaseClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = signer == null
                ? VisualEvidenceSigner.unavailable() : signer;
        this.databaseClock = databaseClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(
                databaseClock, "databaseClock");
    }

    /** Creates payload-free state and signed event-chain tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_STATE);
        jdbc.execute(CREATE_EVENTS);
    }

    @Override
    @Transactional
    public ScenarioRehearsalRetentionState register(
            ScenarioRehearsalEvidenceBundle bundle,
            Instant retainUntil) {
        ScenarioRehearsalEvidenceBundle exact =
                Objects.requireNonNull(bundle, "bundle");
        Instant retention = Objects.requireNonNull(
                retainUntil, "retainUntil");
        ScenarioRehearsalResult result = exact.result();
        CapabilitySnapshot.Scope scope = result.scope();
        String runId = exact.attestation().runId();
        Optional<ScenarioRehearsalRetentionState> existing =
                locked(scope, runId);
        if (existing.isPresent()) {
            return sameRegistration(
                    existing.orElseThrow(), exact, retention);
        }
        Instant at = databaseNow();
        if (!retention.isAfter(at)) {
            throw new IllegalArgumentException(
                    "Scenario retention boundary must be in the future");
        }
        ScenarioRehearsalRetentionEvent event = signedEvent(
                "register:" + runId,
                scope,
                result.requestId(),
                runId,
                1,
                ScenarioRehearsalRetentionEvent.Type
                        .RETENTION_REGISTERED,
                retention,
                at,
                "resource-gateway-retention",
                "RG.MIRROR.REHEARSAL.RETENTION_REGISTERED",
                "",
                exact.bundleFingerprint(),
                "",
                0,
                ScenarioRehearsalRetentionEvent
                        .ChildEvidenceDisposition.NOT_APPLICABLE);
        ScenarioRehearsalRetentionState state =
                new ScenarioRehearsalRetentionState(
                        "", scope, runId, result.requestId(),
                        exact.bundleFingerprint(),
                        ScenarioRehearsalRetentionState.Status.RETAINED,
                        1, retention, List.of(), at, event);
        try {
            insertState(state);
            insertEvent(event);
            return state;
        } catch (DuplicateKeyException concurrent) {
            ScenarioRehearsalRetentionState persisted =
                    locked(scope, runId)
                            .orElseThrow(() -> concurrent);
            return sameRegistration(
                    persisted, exact, retention);
        }
    }

    @Override
    @Transactional
    public ScenarioRehearsalRetentionState placeHold(
            CapabilitySnapshot.Scope scope,
            String runId,
            String commandId,
            String holdId,
            String actorId,
            String reasonCode) {
        ScenarioRehearsalRetentionState current =
                requireRetained(scope, runId);
        ScenarioRehearsalRetentionState replay =
                replay(
                        current, commandId,
                        ScenarioRehearsalRetentionEvent.Type
                                .HOLD_PLACED,
                        holdId, actorId, reasonCode);
        if (replay != null) {
            return replay;
        }
        String hold = required(holdId, "holdId", 256);
        if (current.activeHoldIds().contains(hold)) {
            return current;
        }
        if (holdWasUsed(scope, runId, hold)) {
            throw new IllegalStateException(
                    "A released Scenario legal hold id cannot be reused");
        }
        TreeSet<String> holds =
                new TreeSet<>(current.activeHoldIds());
        holds.add(hold);
        return transition(
                current, commandId,
                ScenarioRehearsalRetentionEvent.Type.HOLD_PLACED,
                hold, actorId, reasonCode,
                List.copyOf(holds), 0,
                ScenarioRehearsalRetentionEvent
                        .ChildEvidenceDisposition.NOT_APPLICABLE);
    }

    @Override
    @Transactional
    public ScenarioRehearsalRetentionState releaseHold(
            CapabilitySnapshot.Scope scope,
            String runId,
            String commandId,
            String holdId,
            String actorId,
            String reasonCode) {
        ScenarioRehearsalRetentionState current =
                requireRetained(scope, runId);
        ScenarioRehearsalRetentionState replay =
                replay(
                        current, commandId,
                        ScenarioRehearsalRetentionEvent.Type
                                .HOLD_RELEASED,
                        holdId, actorId, reasonCode);
        if (replay != null) {
            return replay;
        }
        String hold = required(holdId, "holdId", 256);
        if (!current.activeHoldIds().contains(hold)) {
            throw new IllegalStateException(
                    "The requested Scenario legal hold is not active");
        }
        TreeSet<String> holds =
                new TreeSet<>(current.activeHoldIds());
        holds.remove(hold);
        return transition(
                current, commandId,
                ScenarioRehearsalRetentionEvent.Type.HOLD_RELEASED,
                hold, actorId, reasonCode,
                List.copyOf(holds), 0,
                ScenarioRehearsalRetentionEvent
                        .ChildEvidenceDisposition.NOT_APPLICABLE);
    }

    @Override
    @Transactional
    public ScenarioRehearsalRetentionState purge(
            CapabilitySnapshot.Scope scope,
            String runId,
            String commandId,
            String actorId,
            String reasonCode) {
        ScenarioRehearsalRetentionState current =
                locked(
                        Objects.requireNonNull(scope, "scope"),
                        required(runId, "runId", 512))
                        .orElseThrow(() -> new IllegalStateException(
                                "Scenario retention state was not found"));
        ScenarioRehearsalRetentionState replay =
                replay(
                        current, commandId,
                        ScenarioRehearsalRetentionEvent.Type.PURGED,
                        "", actorId, reasonCode);
        if (replay != null) {
            return replay;
        }
        if (current.status()
                == ScenarioRehearsalRetentionState.Status.PURGED) {
            return current;
        }
        if (current.held()) {
            throw new IllegalStateException(
                    "Scenario evidence cannot be purged while any legal hold is active");
        }
        Instant at = databaseNow();
        if (at.isBefore(current.retainUntil())) {
            throw new IllegalStateException(
                    "Scenario evidence minimum retention has not elapsed");
        }
        requireExactEvidence(current);
        int deletedProgress = progressCount(current);
        ScenarioRehearsalRetentionEvent event = signedEvent(
                commandId,
                current.scope(),
                current.requestId(),
                current.runId(),
                current.revision() + 1,
                ScenarioRehearsalRetentionEvent.Type.PURGED,
                current.retainUntil(),
                at,
                actorId,
                reasonCode,
                "",
                current.evidenceBundleFingerprint(),
                current.latestEvent().eventFingerprint(),
                deletedProgress,
                ScenarioRehearsalRetentionEvent
                        .ChildEvidenceDisposition.RETAINED);
        deleteAggregate(current);
        ScenarioRehearsalRetentionState purged =
                new ScenarioRehearsalRetentionState(
                        "", current.scope(), current.runId(),
                        current.requestId(),
                        current.evidenceBundleFingerprint(),
                        ScenarioRehearsalRetentionState.Status.PURGED,
                        current.revision() + 1,
                        current.retainUntil(), List.of(), at, event);
        updateState(current, purged);
        insertEvent(event);
        return purged;
    }

    @Override
    public Optional<ScenarioRehearsalRetentionState> find(
            CapabilitySnapshot.Scope scope,
            String runId) {
        Optional<ScenarioRehearsalRetentionState> stored =
                query(
                        SELECT_STATE,
                        Objects.requireNonNull(scope, "scope"),
                        required(runId, "runId", 512));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ScenarioRehearsalRetentionState expected =
                rebuild(events(scope, runId));
        if (!expected.equals(stored.orElseThrow())) {
            throw new IllegalStateException(
                    "Scenario retention state differs from its signed event chain");
        }
        return Optional.of(expected);
    }

    @Override
    public List<ScenarioRehearsalRetentionEvent> events(
            CapabilitySnapshot.Scope scope,
            String runId) {
        CapabilitySnapshot.Scope requiredScope =
                Objects.requireNonNull(scope, "scope");
        String id = required(runId, "runId", 512);
        List<ScenarioRehearsalRetentionEvent> values =
                jdbc.query("""
                                SELECT revision, command_id, type, hold_id,
                                       event_fingerprint, event_json
                                FROM scenario_rehearsal_retention_events
                                WHERE tenant_id = ? AND organization_id = ?
                                  AND project_id = ? AND environment_id = ?
                                  AND region = ? AND run_id = ?
                                ORDER BY revision
                                """,
                        (result, row) -> {
                            ScenarioRehearsalRetentionEvent event =
                                    deserialize(
                                            result.getString(
                                                    "event_json"),
                                            ScenarioRehearsalRetentionEvent
                                                    .class);
                            if (result.getLong("revision")
                                    != event.revision()
                                    || !result.getString(
                                    "command_id").equals(
                                    event.commandId())
                                    || !result.getString("type")
                                    .equals(event.type().name())
                                    || !result.getString("hold_id")
                                    .equals(event.holdId())
                                    || !result.getString(
                                    "event_fingerprint").equals(
                                    event.eventFingerprint())) {
                                throw new IllegalStateException(
                                        "Scenario retention event index differs from signed JSON");
                            }
                            return event;
                        },
                        requiredScope.tenantId(),
                        requiredScope.organizationId(),
                        requiredScope.projectId(),
                        requiredScope.environmentId(),
                        requiredScope.region(),
                        id);
        String previous = "";
        for (int index = 0; index < values.size(); index++) {
            ScenarioRehearsalRetentionEvent event =
                    values.get(index);
            verify(event);
            if (!requiredScope.equals(event.scope())
                    || !id.equals(event.runId())
                    || event.revision() != index + 1L
                    || !previous.equals(
                    event.previousEventFingerprint())) {
                throw new IllegalStateException(
                        "Scenario retention event chain is inconsistent");
            }
            previous = event.eventFingerprint();
        }
        return List.copyOf(values);
    }

    private ScenarioRehearsalRetentionState transition(
            ScenarioRehearsalRetentionState current,
            String commandId,
            ScenarioRehearsalRetentionEvent.Type type,
            String holdId,
            String actorId,
            String reasonCode,
            List<String> activeHolds,
            int deletedProgress,
            ScenarioRehearsalRetentionEvent
                    .ChildEvidenceDisposition childDisposition) {
        Instant at = databaseNow();
        ScenarioRehearsalRetentionEvent event = signedEvent(
                commandId,
                current.scope(),
                current.requestId(),
                current.runId(),
                current.revision() + 1,
                type,
                current.retainUntil(),
                at,
                actorId,
                reasonCode,
                holdId,
                current.evidenceBundleFingerprint(),
                current.latestEvent().eventFingerprint(),
                deletedProgress,
                childDisposition);
        ScenarioRehearsalRetentionState updated =
                new ScenarioRehearsalRetentionState(
                        "", current.scope(), current.runId(),
                        current.requestId(),
                        current.evidenceBundleFingerprint(),
                        current.status(),
                        current.revision() + 1,
                        current.retainUntil(), activeHolds,
                        at, event);
        updateState(current, updated);
        insertEvent(event);
        return updated;
    }

    private ScenarioRehearsalRetentionState replay(
            ScenarioRehearsalRetentionState current,
            String commandId,
            ScenarioRehearsalRetentionEvent.Type expectedType,
            String holdId,
            String actorId,
            String reasonCode) {
        String command = required(
                commandId, "commandId", 256);
        Optional<ScenarioRehearsalRetentionEvent> existing =
                eventByCommand(
                        current.scope(),
                        current.runId(),
                        command);
        if (existing.isEmpty()) {
            return null;
        }
        ScenarioRehearsalRetentionEvent event =
                existing.orElseThrow();
        if (event.type() != expectedType
                || !normalized(holdId).equals(event.holdId())
                || !required(
                actorId, "actorId", 255).equals(
                event.actorId())
                || !required(
                reasonCode, "reasonCode", 256).equals(
                event.reasonCode())) {
            throw new IllegalStateException(
                    "Scenario retention command id identifies different semantics");
        }
        return current;
    }

    private Optional<ScenarioRehearsalRetentionEvent>
    eventByCommand(
            CapabilitySnapshot.Scope scope,
            String runId,
            String commandId) {
        List<ScenarioRehearsalRetentionEvent> values =
                jdbc.query("""
                                SELECT event_json
                                FROM scenario_rehearsal_retention_events
                                WHERE tenant_id = ? AND organization_id = ?
                                  AND project_id = ? AND environment_id = ?
                                  AND region = ? AND run_id = ?
                                  AND command_id = ?
                                """,
                        (result, row) -> deserialize(
                                result.getString("event_json"),
                                ScenarioRehearsalRetentionEvent.class),
                        scope.tenantId(),
                        scope.organizationId(),
                        scope.projectId(),
                        scope.environmentId(),
                        scope.region(),
                        runId,
                        commandId);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        ScenarioRehearsalRetentionEvent event =
                values.getFirst();
        verify(event);
        return Optional.of(event);
    }

    private boolean holdWasUsed(
            CapabilitySnapshot.Scope scope,
            String runId,
            String holdId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM scenario_rehearsal_retention_events
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND run_id = ?
                          AND hold_id = ?
                        """,
                Integer.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                runId,
                holdId);
        return count != null && count > 0;
    }

    private ScenarioRehearsalRetentionState requireRetained(
            CapabilitySnapshot.Scope scope,
            String runId) {
        ScenarioRehearsalRetentionState current =
                locked(
                        Objects.requireNonNull(scope, "scope"),
                        required(runId, "runId", 512))
                        .orElseThrow(() -> new IllegalStateException(
                                "Scenario retention state was not found"));
        if (current.status()
                != ScenarioRehearsalRetentionState.Status.RETAINED) {
            throw new IllegalStateException(
                    "Purged Scenario evidence cannot change legal holds");
        }
        return current;
    }

    private ScenarioRehearsalRetentionState sameRegistration(
            ScenarioRehearsalRetentionState state,
            ScenarioRehearsalEvidenceBundle bundle,
            Instant retainUntil) {
        ScenarioRehearsalResult result = bundle.result();
        if (!state.scope().equals(result.scope())
                || !state.runId().equals(
                bundle.attestation().runId())
                || !state.requestId().equals(result.requestId())
                || !state.evidenceBundleFingerprint().equals(
                bundle.bundleFingerprint())
                || !state.retainUntil().equals(retainUntil)) {
            throw new IllegalStateException(
                    "Scenario retention registration conflicts with immutable evidence");
        }
        return state;
    }

    private ScenarioRehearsalRetentionState rebuild(
            List<ScenarioRehearsalRetentionEvent> events) {
        if (events.isEmpty()
                || events.getFirst().type()
                != ScenarioRehearsalRetentionEvent.Type
                .RETENTION_REGISTERED) {
            throw new IllegalStateException(
                    "Scenario retention chain lacks registration");
        }
        ScenarioRehearsalRetentionEvent first =
                events.getFirst();
        TreeSet<String> holds = new TreeSet<>();
        ScenarioRehearsalRetentionState.Status status =
                ScenarioRehearsalRetentionState.Status.RETAINED;
        for (ScenarioRehearsalRetentionEvent event : events) {
            if (!first.scope().equals(event.scope())
                    || !first.runId().equals(event.runId())
                    || !first.requestId().equals(event.requestId())
                    || !first.retainUntil().equals(
                    event.retainUntil())
                    || !first.evidenceBundleFingerprint().equals(
                    event.evidenceBundleFingerprint())) {
                throw new IllegalStateException(
                        "Scenario retention event identity drifted");
            }
            switch (event.type()) {
                case RETENTION_REGISTERED -> {
                    if (event.revision() != 1) {
                        throw new IllegalStateException(
                                "Scenario retention registered more than once");
                    }
                }
                case HOLD_PLACED -> {
                    if (status
                            != ScenarioRehearsalRetentionState
                            .Status.RETAINED
                            || !holds.add(event.holdId())) {
                        throw new IllegalStateException(
                                "Scenario hold placement is inconsistent");
                    }
                }
                case HOLD_RELEASED -> {
                    if (!holds.remove(event.holdId())) {
                        throw new IllegalStateException(
                                "Scenario hold release is inconsistent");
                    }
                }
                case PURGED -> {
                    if (!holds.isEmpty()
                            || status
                            == ScenarioRehearsalRetentionState
                            .Status.PURGED) {
                        throw new IllegalStateException(
                                "Scenario deletion proof is inconsistent");
                    }
                    status =
                            ScenarioRehearsalRetentionState
                                    .Status.PURGED;
                }
            }
        }
        ScenarioRehearsalRetentionEvent latest =
                events.getLast();
        return new ScenarioRehearsalRetentionState(
                "", first.scope(), first.runId(),
                first.requestId(),
                first.evidenceBundleFingerprint(),
                status, latest.revision(),
                first.retainUntil(),
                List.copyOf(holds),
                latest.occurredAt(), latest);
    }

    private ScenarioRehearsalRetentionEvent signedEvent(
            String commandId,
            CapabilitySnapshot.Scope scope,
            String requestId,
            String runId,
            long revision,
            ScenarioRehearsalRetentionEvent.Type type,
            Instant retainUntil,
            Instant occurredAt,
            String actorId,
            String reasonCode,
            String holdId,
            String evidenceBundleFingerprint,
            String previousEventFingerprint,
            int deletedProgress,
            ScenarioRehearsalRetentionEvent
                    .ChildEvidenceDisposition childDisposition) {
        if (!signer.available()) {
            throw new IllegalStateException(
                    "Scenario retention signing authority is unavailable");
        }
        ScenarioRehearsalRetentionEvent unsigned =
                new ScenarioRehearsalRetentionEvent(
                        "", UUID.randomUUID().toString(),
                        commandId, scope, requestId, runId,
                        revision, type, retainUntil, occurredAt,
                        actorId, reasonCode, holdId,
                        evidenceBundleFingerprint,
                        previousEventFingerprint,
                        deletedProgress, childDisposition,
                        VisualRunEvidenceSeal.unsigned());
        String fingerprint = unsigned.eventFingerprint();
        VisualRunEvidenceSeal seal = signer.seal(fingerprint);
        ScenarioRehearsalRetentionEvent signed =
                unsigned.withEvidenceSeal(seal);
        verify(signed);
        return signed;
    }

    private void verify(
            ScenarioRehearsalRetentionEvent event) {
        String fingerprint = event.eventFingerprint();
        VisualRunEvidenceSeal seal = event.evidenceSeal();
        if (!seal.signed()
                || !fingerprint.equals(
                seal.materialFingerprint())
                || !signer.verify(seal, fingerprint).valid()) {
            throw new IllegalStateException(
                    "Scenario retention event signature is invalid");
        }
    }

    private void insertState(
            ScenarioRehearsalRetentionState state) {
        CapabilitySnapshot.Scope scope = state.scope();
        jdbc.update("""
                        INSERT INTO scenario_rehearsal_retention_states (
                            tenant_id, organization_id, project_id,
                            environment_id, region, run_id, request_id,
                            evidence_bundle_fingerprint, status, revision,
                            retain_until, state_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                state.runId(),
                state.requestId(),
                state.evidenceBundleFingerprint(),
                state.status().name(),
                state.revision(),
                state.retainUntil().toString(),
                serialize(state));
    }

    private void updateState(
            ScenarioRehearsalRetentionState current,
            ScenarioRehearsalRetentionState updated) {
        CapabilitySnapshot.Scope scope = current.scope();
        int changed = jdbc.update("""
                        UPDATE scenario_rehearsal_retention_states
                        SET status = ?, revision = ?, state_json = ?
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND run_id = ? AND revision = ?
                        """,
                updated.status().name(),
                updated.revision(),
                serialize(updated),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                current.runId(),
                current.revision());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Scenario retention state changed concurrently");
        }
    }

    private void insertEvent(
            ScenarioRehearsalRetentionEvent event) {
        CapabilitySnapshot.Scope scope = event.scope();
        jdbc.update("""
                        INSERT INTO scenario_rehearsal_retention_events (
                            tenant_id, organization_id, project_id,
                            environment_id, region, run_id, revision,
                            command_id, type, hold_id,
                            event_fingerprint, event_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                event.runId(),
                event.revision(),
                event.commandId(),
                event.type().name(),
                event.holdId(),
                event.eventFingerprint(),
                serialize(event));
    }

    private Optional<ScenarioRehearsalRetentionState> locked(
            CapabilitySnapshot.Scope scope,
            String runId) {
        Optional<ScenarioRehearsalRetentionState> stored =
                query(SELECT_STATE_FOR_UPDATE, scope, runId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ScenarioRehearsalRetentionState expected =
                rebuild(events(scope, runId));
        if (!expected.equals(stored.orElseThrow())) {
            throw new IllegalStateException(
                    "Scenario retention state differs from its signed event chain");
        }
        return Optional.of(expected);
    }

    private Optional<ScenarioRehearsalRetentionState> query(
            String sql,
            CapabilitySnapshot.Scope scope,
            String runId) {
        List<ScenarioRehearsalRetentionState> values =
                jdbc.query(
                        sql,
                        (result, row) -> {
                            ScenarioRehearsalRetentionState state =
                                    deserialize(
                                            result.getString(
                                                    "state_json"),
                                            ScenarioRehearsalRetentionState
                                                    .class);
                            if (!scope.equals(state.scope())
                                    || !runId.equals(state.runId())
                                    || !result.getString(
                                    "request_id").equals(
                                    state.requestId())
                                    || !result.getString(
                                    "evidence_bundle_fingerprint")
                                    .equals(
                                            state.evidenceBundleFingerprint())
                                    || !result.getString("status")
                                    .equals(state.status().name())
                                    || result.getLong("revision")
                                    != state.revision()
                                    || !result.getString(
                                    "retain_until").equals(
                                    state.retainUntil().toString())) {
                                throw new IllegalStateException(
                                        "Scenario retention index differs from state JSON");
                            }
                            verify(state.latestEvent());
                            return state;
                        },
                        scope.tenantId(),
                        scope.organizationId(),
                        scope.projectId(),
                        scope.environmentId(),
                        scope.region(),
                        runId);
        return values.stream().findFirst();
    }

    private void requireExactEvidence(
            ScenarioRehearsalRetentionState state) {
        CapabilitySnapshot.Scope scope = state.scope();
        List<String> fingerprints = jdbc.queryForList("""
                        SELECT bundle_fingerprint
                        FROM scenario_rehearsal_evidence
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND run_id = ?
                        """,
                String.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                state.runId());
        if (fingerprints.size() != 1
                || !state.evidenceBundleFingerprint().equals(
                fingerprints.getFirst())) {
            throw new IllegalStateException(
                    "Scenario evidence differs from retention authority");
        }
    }

    private int progressCount(
            ScenarioRehearsalRetentionState state) {
        CapabilitySnapshot.Scope scope = state.scope();
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM scenario_rehearsal_case_progress
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND request_id = ?
                        """,
                Integer.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                state.requestId());
        return count == null ? 0 : count;
    }

    private void deleteAggregate(
            ScenarioRehearsalRetentionState state) {
        CapabilitySnapshot.Scope scope = state.scope();
        jdbc.update("""
                        DELETE FROM scenario_rehearsal_case_progress
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND request_id = ?
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                state.requestId());
        int evidenceDeleted = jdbc.update("""
                        DELETE FROM scenario_rehearsal_evidence
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND run_id = ?
                          AND bundle_fingerprint = ?
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                state.runId(),
                state.evidenceBundleFingerprint());
        if (evidenceDeleted != 1) {
            throw new IllegalStateException(
                    "Scenario evidence changed during governed deletion");
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Scenario retention value cannot be serialized",
                    failure);
        }
    }

    private <T> T deserialize(
            String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Stored Scenario retention value is invalid",
                    failure);
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(
                databaseClock.get(),
                "Scenario retention database clock returned null");
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        DataSource source = Objects.requireNonNull(
                jdbc.getDataSource(),
                "Scenario retention datasource is unavailable");
        while (source
                instanceof DelegatingDataSource delegating
                && delegating.getTargetDataSource() != null
                && delegating.getTargetDataSource() != source) {
            source = delegating.getTargetDataSource();
        }
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT CURRENT_TIMESTAMP")) {
            if (!result.next()) {
                throw new IllegalStateException(
                        "Scenario retention database clock returned no row");
            }
            Timestamp value = result.getTimestamp(1);
            if (value == null) {
                throw new IllegalStateException(
                        "Scenario retention database clock returned null");
            }
            return value.toInstant();
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "Scenario retention database clock is unavailable",
                    unavailable);
        }
    }

    private static String required(
            String value, String field, int maximum) {
        String normalized = normalized(value);
        if (normalized.isBlank()
                || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " is blank or exceeds its bound");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
