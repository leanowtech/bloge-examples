package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Signed, scope-isolated retention and logical-deletion control plane for Scenario batches.
 *
 * <p>The signed event chain is authoritative and the state row is a rebuildable projection.
 * Purge removes only the terminal batch job, its item rows, and its signed batch bundle. Child
 * Scenario evidence, child coordination records, lifecycle audit, operation audit, retention
 * state, and signed deletion events remain.</p>
 */
public class DatabaseScenarioRehearsalBatchRetentionRepository
        implements ScenarioRehearsalBatchRetentionRepository {
    private static final String CREATE_STATE = """
            CREATE TABLE IF NOT EXISTS scenario_rehearsal_batch_retention_states (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                job_id VARCHAR(512) NOT NULL,
                request_id VARCHAR(256) NOT NULL,
                manifest_fingerprint VARCHAR(71) NOT NULL,
                evidence_bundle_fingerprint VARCHAR(71) NOT NULL,
                status VARCHAR(32) NOT NULL,
                revision BIGINT NOT NULL,
                retain_until VARCHAR(64) NOT NULL,
                state_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, job_id
                )
            )
            """;
    private static final String CREATE_EVENTS = """
            CREATE TABLE IF NOT EXISTS scenario_rehearsal_batch_retention_events (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                job_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                command_id VARCHAR(256) NOT NULL,
                type VARCHAR(32) NOT NULL,
                hold_id VARCHAR(256) NOT NULL,
                event_fingerprint VARCHAR(71) NOT NULL,
                event_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, job_id, revision
                ),
                UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, job_id, command_id
                )
            )
            """;
    private static final String SELECT_STATE = """
            SELECT request_id, manifest_fingerprint,
                   evidence_bundle_fingerprint, status,
                   revision, retain_until, state_json
            FROM scenario_rehearsal_batch_retention_states
            WHERE tenant_id = ? AND organization_id = ?
              AND project_id = ? AND environment_id = ?
              AND region = ? AND job_id = ?
            """;
    private static final String SELECT_STATE_FOR_UPDATE =
            SELECT_STATE + " FOR UPDATE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final ScenarioRehearsalBatchEvidenceRepository evidence;
    private final Supplier<Instant> databaseClock;

    /**
     * Creates the production repository using the application database clock.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param signer governed retention-event signing authority
     * @param evidence independently verifying batch-evidence repository
     */
    public DatabaseScenarioRehearsalBatchRetentionRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            ScenarioRehearsalBatchEvidenceRepository evidence) {
        this(jdbc, mapper, signer, evidence, null);
    }

    /** Package-private deterministic database-clock seam for focused tests. */
    DatabaseScenarioRehearsalBatchRetentionRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            ScenarioRehearsalBatchEvidenceRepository evidence,
            Supplier<Instant> databaseClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = signer == null
                ? VisualEvidenceSigner.unavailable() : signer;
        this.evidence = Objects.requireNonNull(
                evidence, "evidence");
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
    public ScenarioRehearsalBatchRetentionState register(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            Instant retainUntil) {
        ScenarioRehearsalBatchEvidenceBundle exact =
                Objects.requireNonNull(bundle, "bundle");
        Instant retention = Objects.requireNonNull(
                retainUntil, "retainUntil");
        Instant at = databaseNow();
        return register(
                exact,
                prepareRegistration(
                        exact,
                        retention,
                        at,
                        "scenario-batch-retention:"
                                + exact.index().job().jobId()
                                .substring("scenario-batch-".length())));
    }

    @Override
    public ScenarioRehearsalBatchRetentionRepository
    .PreparedRegistration prepareRegistration(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            Instant retainUntil,
            Instant occurredAt,
            String signingRequestId) {
        ScenarioRehearsalBatchEvidenceBundle exact =
                Objects.requireNonNull(bundle, "bundle");
        Instant retention = Objects.requireNonNull(
                retainUntil, "retainUntil");
        Instant at = Objects.requireNonNull(
                occurredAt, "occurredAt");
        ScenarioRehearsalBatchJob job = exact.index().job();
        if (!job.status().terminal()) {
            throw new IllegalArgumentException(
                    "Scenario batch retention requires a terminal job");
        }
        if (!retention.isAfter(at)) {
            throw new IllegalArgumentException(
                    "Scenario batch retention boundary must be in the future");
        }
        String suffix = job.jobId().substring(
                "scenario-batch-".length());
        ScenarioRehearsalBatchRetentionEvent event =
                signedEvent(
                        "batch-retention-" + suffix,
                        signingRequestId,
                        "register:" + job.jobId(),
                        job.scope(),
                        job.requestId(),
                        job.jobId(),
                        job.manifestFingerprint(),
                        1,
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .RETENTION_REGISTERED,
                        retention,
                        at,
                        "resource-gateway-retention",
                        "RG.MIRROR.REHEARSAL_BATCH.RETENTION_REGISTERED",
                        "",
                        exact.bundleFingerprint(),
                        "",
                        0, 0, 0,
                        ScenarioRehearsalBatchRetentionEvent
                                .PreservedDisposition.NOT_APPLICABLE,
                        ScenarioRehearsalBatchRetentionEvent
                                .PreservedDisposition.NOT_APPLICABLE);
        return new ScenarioRehearsalBatchRetentionRepository
                .PreparedRegistration(
                exact.bundleFingerprint(),
                retention,
                event);
    }

    @Override
    @Transactional
    public ScenarioRehearsalBatchRetentionState register(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            ScenarioRehearsalBatchRetentionRepository
                    .PreparedRegistration prepared) {
        ScenarioRehearsalBatchEvidenceBundle exact =
                Objects.requireNonNull(bundle, "bundle");
        ScenarioRehearsalBatchRetentionRepository
                .PreparedRegistration registration =
                Objects.requireNonNull(prepared, "prepared");
        ScenarioRehearsalBatchJob job = exact.index().job();
        ScenarioRehearsalBatchRetentionEvent event =
                registration.event();
        if (!registration.bundleFingerprint().equals(
                exact.bundleFingerprint())
                || !event.scope().equals(job.scope())
                || !event.requestId().equals(job.requestId())
                || !event.jobId().equals(job.jobId())
                || !event.manifestFingerprint().equals(
                job.manifestFingerprint())) {
            throw new IllegalArgumentException(
                    "Prepared Scenario batch retention differs from evidence");
        }
        verify(event);
        requireExactEvidence(exact);
        Optional<ScenarioRehearsalBatchRetentionState> existing =
                locked(job.scope(), job.jobId());
        if (existing.isPresent()) {
            return sameRegistration(
                    existing.orElseThrow(),
                    exact,
                    registration.retainUntil());
        }
        ScenarioRehearsalBatchRetentionState state =
                new ScenarioRehearsalBatchRetentionState(
                        "", job.scope(), job.requestId(),
                        job.jobId(), job.manifestFingerprint(),
                        exact.bundleFingerprint(),
                        ScenarioRehearsalBatchRetentionState.Status
                                .RETAINED,
                        1, registration.retainUntil(),
                        List.of(), event.occurredAt(), event);
        try {
            insertState(state);
            insertEvent(event);
            return state;
        } catch (DuplicateKeyException concurrent) {
            ScenarioRehearsalBatchRetentionState persisted =
                    locked(job.scope(), job.jobId())
                            .orElseThrow(() -> concurrent);
            return sameRegistration(
                    persisted,
                    exact,
                    registration.retainUntil());
        }
    }

    @Override
    @Transactional
    public ScenarioRehearsalBatchRetentionState placeHold(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String commandId,
            String holdId,
            String actorId,
            String reasonCode) {
        ScenarioRehearsalBatchRetentionState current =
                requireRetained(scope, jobId);
        ScenarioRehearsalBatchRetentionState replay =
                replay(
                        current, commandId,
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .HOLD_PLACED,
                        holdId, actorId, reasonCode);
        if (replay != null) {
            return replay;
        }
        String hold = required(holdId, "holdId", 256);
        if (current.activeHoldIds().contains(hold)) {
            return current;
        }
        if (holdWasUsed(scope, jobId, hold)) {
            throw new IllegalStateException(
                    "A released Scenario batch legal hold id cannot be reused");
        }
        TreeSet<String> holds =
                new TreeSet<>(current.activeHoldIds());
        holds.add(hold);
        return transition(
                current, commandId,
                ScenarioRehearsalBatchRetentionEvent.Type
                        .HOLD_PLACED,
                hold, actorId, reasonCode,
                List.copyOf(holds));
    }

    @Override
    @Transactional
    public ScenarioRehearsalBatchRetentionState releaseHold(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String commandId,
            String holdId,
            String actorId,
            String reasonCode) {
        ScenarioRehearsalBatchRetentionState current =
                requireRetained(scope, jobId);
        ScenarioRehearsalBatchRetentionState replay =
                replay(
                        current, commandId,
                        ScenarioRehearsalBatchRetentionEvent.Type
                                .HOLD_RELEASED,
                        holdId, actorId, reasonCode);
        if (replay != null) {
            return replay;
        }
        String hold = required(holdId, "holdId", 256);
        if (!current.activeHoldIds().contains(hold)) {
            throw new IllegalStateException(
                    "The requested Scenario batch legal hold is not active");
        }
        TreeSet<String> holds =
                new TreeSet<>(current.activeHoldIds());
        holds.remove(hold);
        return transition(
                current, commandId,
                ScenarioRehearsalBatchRetentionEvent.Type
                        .HOLD_RELEASED,
                hold, actorId, reasonCode,
                List.copyOf(holds));
    }

    @Override
    @Transactional
    public ScenarioRehearsalBatchRetentionState purge(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String commandId,
            String actorId,
            String reasonCode) {
        ScenarioRehearsalBatchRetentionState current =
                locked(
                        Objects.requireNonNull(scope, "scope"),
                        required(jobId, "jobId", 512))
                        .orElseThrow(() -> new IllegalStateException(
                                "Scenario batch retention state was not found"));
        ScenarioRehearsalBatchRetentionState replay =
                replay(
                        current, commandId,
                        ScenarioRehearsalBatchRetentionEvent.Type.PURGED,
                        "", actorId, reasonCode);
        if (replay != null) {
            return replay;
        }
        if (current.status()
                == ScenarioRehearsalBatchRetentionState.Status.PURGED) {
            return current;
        }
        if (current.held()) {
            throw new IllegalStateException(
                    "Scenario batch evidence cannot be purged while any legal hold is active");
        }
        Instant at = databaseNow();
        if (at.isBefore(current.retainUntil())) {
            throw new IllegalStateException(
                    "Scenario batch evidence minimum retention has not elapsed");
        }
        PurgeMaterial material = requireExactAggregate(current);
        ScenarioRehearsalBatchRetentionEvent event =
                signedEvent(
                        commandId,
                        current.scope(),
                        current.requestId(),
                        current.jobId(),
                        current.manifestFingerprint(),
                        current.revision() + 1,
                        ScenarioRehearsalBatchRetentionEvent.Type.PURGED,
                        current.retainUntil(),
                        at,
                        actorId,
                        reasonCode,
                        "",
                        current.evidenceBundleFingerprint(),
                        current.latestEvent().eventFingerprint(),
                        1,
                        material.itemCount(),
                        1,
                        ScenarioRehearsalBatchRetentionEvent
                                .PreservedDisposition.RETAINED,
                        ScenarioRehearsalBatchRetentionEvent
                                .PreservedDisposition.RETAINED);
        deleteAggregate(current, material.itemCount());
        ScenarioRehearsalBatchRetentionState purged =
                new ScenarioRehearsalBatchRetentionState(
                        "", current.scope(), current.requestId(),
                        current.jobId(),
                        current.manifestFingerprint(),
                        current.evidenceBundleFingerprint(),
                        ScenarioRehearsalBatchRetentionState.Status.PURGED,
                        current.revision() + 1,
                        current.retainUntil(), List.of(), at, event);
        updateState(current, purged);
        insertEvent(event);
        return purged;
    }

    @Override
    public Optional<ScenarioRehearsalBatchRetentionState> find(
            CapabilitySnapshot.Scope scope,
            String jobId) {
        Optional<ScenarioRehearsalBatchRetentionState> stored =
                query(
                        SELECT_STATE,
                        Objects.requireNonNull(scope, "scope"),
                        required(jobId, "jobId", 512));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ScenarioRehearsalBatchRetentionState expected =
                rebuild(events(scope, jobId));
        if (!expected.equals(stored.orElseThrow())) {
            throw new IllegalStateException(
                    "Scenario batch retention state differs from its signed event chain");
        }
        return Optional.of(expected);
    }

    @Override
    public List<ScenarioRehearsalBatchRetentionEvent> events(
            CapabilitySnapshot.Scope scope,
            String jobId) {
        CapabilitySnapshot.Scope requiredScope =
                Objects.requireNonNull(scope, "scope");
        String id = required(jobId, "jobId", 512);
        List<ScenarioRehearsalBatchRetentionEvent> values =
                jdbc.query("""
                                SELECT revision, command_id, type, hold_id,
                                       event_fingerprint, event_json
                                FROM scenario_rehearsal_batch_retention_events
                                WHERE tenant_id = ? AND organization_id = ?
                                  AND project_id = ? AND environment_id = ?
                                  AND region = ? AND job_id = ?
                                ORDER BY revision
                                """,
                        (result, row) -> {
                            ScenarioRehearsalBatchRetentionEvent event =
                                    deserialize(
                                            result.getString(
                                                    "event_json"),
                                            ScenarioRehearsalBatchRetentionEvent
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
                                        "Scenario batch retention event index differs from signed JSON");
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
            ScenarioRehearsalBatchRetentionEvent event =
                    values.get(index);
            verify(event);
            if (!requiredScope.equals(event.scope())
                    || !id.equals(event.jobId())
                    || event.revision() != index + 1L
                    || !previous.equals(
                    event.previousEventFingerprint())) {
                throw new IllegalStateException(
                        "Scenario batch retention event chain is inconsistent");
            }
            previous = event.eventFingerprint();
        }
        return List.copyOf(values);
    }

    private ScenarioRehearsalBatchRetentionState transition(
            ScenarioRehearsalBatchRetentionState current,
            String commandId,
            ScenarioRehearsalBatchRetentionEvent.Type type,
            String holdId,
            String actorId,
            String reasonCode,
            List<String> activeHolds) {
        Instant at = databaseNow();
        ScenarioRehearsalBatchRetentionEvent event =
                signedEvent(
                        commandId,
                        current.scope(),
                        current.requestId(),
                        current.jobId(),
                        current.manifestFingerprint(),
                        current.revision() + 1,
                        type,
                        current.retainUntil(),
                        at,
                        actorId,
                        reasonCode,
                        holdId,
                        current.evidenceBundleFingerprint(),
                        current.latestEvent().eventFingerprint(),
                        0, 0, 0,
                        ScenarioRehearsalBatchRetentionEvent
                                .PreservedDisposition.NOT_APPLICABLE,
                        ScenarioRehearsalBatchRetentionEvent
                                .PreservedDisposition.NOT_APPLICABLE);
        ScenarioRehearsalBatchRetentionState updated =
                new ScenarioRehearsalBatchRetentionState(
                        "", current.scope(), current.requestId(),
                        current.jobId(),
                        current.manifestFingerprint(),
                        current.evidenceBundleFingerprint(),
                        current.status(),
                        current.revision() + 1,
                        current.retainUntil(), activeHolds,
                        at, event);
        updateState(current, updated);
        insertEvent(event);
        return updated;
    }

    private ScenarioRehearsalBatchRetentionState replay(
            ScenarioRehearsalBatchRetentionState current,
            String commandId,
            ScenarioRehearsalBatchRetentionEvent.Type expectedType,
            String holdId,
            String actorId,
            String reasonCode) {
        String command = required(
                commandId, "commandId", 256);
        Optional<ScenarioRehearsalBatchRetentionEvent> existing =
                eventByCommand(
                        current.scope(),
                        current.jobId(),
                        command);
        if (existing.isEmpty()) {
            return null;
        }
        ScenarioRehearsalBatchRetentionEvent event =
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
                    "Scenario batch retention command id identifies different semantics");
        }
        return current;
    }

    private Optional<ScenarioRehearsalBatchRetentionEvent>
    eventByCommand(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String commandId) {
        List<ScenarioRehearsalBatchRetentionEvent> values =
                jdbc.query("""
                                SELECT event_json
                                FROM scenario_rehearsal_batch_retention_events
                                WHERE tenant_id = ? AND organization_id = ?
                                  AND project_id = ? AND environment_id = ?
                                  AND region = ? AND job_id = ?
                                  AND command_id = ?
                                """,
                        (result, row) -> deserialize(
                                result.getString("event_json"),
                                ScenarioRehearsalBatchRetentionEvent
                                        .class),
                        scope.tenantId(),
                        scope.organizationId(),
                        scope.projectId(),
                        scope.environmentId(),
                        scope.region(),
                        jobId,
                        commandId);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        ScenarioRehearsalBatchRetentionEvent event =
                values.getFirst();
        verify(event);
        return Optional.of(event);
    }

    private boolean holdWasUsed(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String holdId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM scenario_rehearsal_batch_retention_events
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND job_id = ?
                          AND hold_id = ?
                        """,
                Integer.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                jobId,
                holdId);
        return count != null && count > 0;
    }

    private ScenarioRehearsalBatchRetentionState requireRetained(
            CapabilitySnapshot.Scope scope,
            String jobId) {
        ScenarioRehearsalBatchRetentionState current =
                locked(
                        Objects.requireNonNull(scope, "scope"),
                        required(jobId, "jobId", 512))
                        .orElseThrow(() -> new IllegalStateException(
                                "Scenario batch retention state was not found"));
        if (current.status()
                != ScenarioRehearsalBatchRetentionState.Status.RETAINED) {
            throw new IllegalStateException(
                    "Purged Scenario batch evidence cannot change legal holds");
        }
        return current;
    }

    private ScenarioRehearsalBatchRetentionState sameRegistration(
            ScenarioRehearsalBatchRetentionState state,
            ScenarioRehearsalBatchEvidenceBundle bundle,
            Instant retainUntil) {
        ScenarioRehearsalBatchJob job = bundle.index().job();
        if (!state.scope().equals(job.scope())
                || !state.requestId().equals(job.requestId())
                || !state.jobId().equals(job.jobId())
                || !state.manifestFingerprint().equals(
                job.manifestFingerprint())
                || !state.evidenceBundleFingerprint().equals(
                bundle.bundleFingerprint())
                || !state.retainUntil().equals(retainUntil)) {
            throw new IllegalStateException(
                    "Scenario batch retention registration conflicts with immutable evidence");
        }
        return state;
    }

    private ScenarioRehearsalBatchRetentionState rebuild(
            List<ScenarioRehearsalBatchRetentionEvent> events) {
        if (events.isEmpty()
                || events.getFirst().type()
                != ScenarioRehearsalBatchRetentionEvent.Type
                .RETENTION_REGISTERED) {
            throw new IllegalStateException(
                    "Scenario batch retention chain lacks registration");
        }
        ScenarioRehearsalBatchRetentionEvent first =
                events.getFirst();
        TreeSet<String> holds = new TreeSet<>();
        ScenarioRehearsalBatchRetentionState.Status status =
                ScenarioRehearsalBatchRetentionState.Status.RETAINED;
        for (ScenarioRehearsalBatchRetentionEvent event : events) {
            if (!first.scope().equals(event.scope())
                    || !first.requestId().equals(event.requestId())
                    || !first.jobId().equals(event.jobId())
                    || !first.manifestFingerprint().equals(
                    event.manifestFingerprint())
                    || !first.retainUntil().equals(
                    event.retainUntil())
                    || !first.evidenceBundleFingerprint().equals(
                    event.evidenceBundleFingerprint())) {
                throw new IllegalStateException(
                        "Scenario batch retention event identity drifted");
            }
            switch (event.type()) {
                case RETENTION_REGISTERED -> {
                    if (event.revision() != 1) {
                        throw new IllegalStateException(
                                "Scenario batch retention registered more than once");
                    }
                }
                case HOLD_PLACED -> {
                    if (status
                            != ScenarioRehearsalBatchRetentionState
                            .Status.RETAINED
                            || !holds.add(event.holdId())) {
                        throw new IllegalStateException(
                                "Scenario batch hold placement is inconsistent");
                    }
                }
                case HOLD_RELEASED -> {
                    if (!holds.remove(event.holdId())) {
                        throw new IllegalStateException(
                                "Scenario batch hold release is inconsistent");
                    }
                }
                case PURGED -> {
                    if (!holds.isEmpty()
                            || status
                            == ScenarioRehearsalBatchRetentionState
                            .Status.PURGED) {
                        throw new IllegalStateException(
                                "Scenario batch deletion proof is inconsistent");
                    }
                    status =
                            ScenarioRehearsalBatchRetentionState
                                    .Status.PURGED;
                }
            }
        }
        ScenarioRehearsalBatchRetentionEvent latest =
                events.getLast();
        return new ScenarioRehearsalBatchRetentionState(
                "", first.scope(), first.requestId(),
                first.jobId(), first.manifestFingerprint(),
                first.evidenceBundleFingerprint(),
                status, latest.revision(),
                first.retainUntil(), List.copyOf(holds),
                latest.occurredAt(), latest);
    }

    private ScenarioRehearsalBatchRetentionEvent signedEvent(
            String commandId,
            CapabilitySnapshot.Scope scope,
            String requestId,
            String jobId,
            String manifestFingerprint,
            long revision,
            ScenarioRehearsalBatchRetentionEvent.Type type,
            Instant retainUntil,
            Instant occurredAt,
            String actorId,
            String reasonCode,
            String holdId,
            String evidenceBundleFingerprint,
            String previousEventFingerprint,
            int deletedJobs,
            int deletedItems,
            int deletedEvidence,
            ScenarioRehearsalBatchRetentionEvent
                    .PreservedDisposition childDisposition,
            ScenarioRehearsalBatchRetentionEvent
                    .PreservedDisposition auditDisposition) {
        String eventId = UUID.randomUUID().toString();
        return signedEvent(
                eventId,
                "batch-retention:" + eventId,
                commandId,
                scope,
                requestId,
                jobId,
                manifestFingerprint,
                revision,
                type,
                retainUntil,
                occurredAt,
                actorId,
                reasonCode,
                holdId,
                evidenceBundleFingerprint,
                previousEventFingerprint,
                deletedJobs,
                deletedItems,
                deletedEvidence,
                childDisposition,
                auditDisposition);
    }

    private ScenarioRehearsalBatchRetentionEvent signedEvent(
            String eventId,
            String signingRequestId,
            String commandId,
            CapabilitySnapshot.Scope scope,
            String requestId,
            String jobId,
            String manifestFingerprint,
            long revision,
            ScenarioRehearsalBatchRetentionEvent.Type type,
            Instant retainUntil,
            Instant occurredAt,
            String actorId,
            String reasonCode,
            String holdId,
            String evidenceBundleFingerprint,
            String previousEventFingerprint,
            int deletedJobs,
            int deletedItems,
            int deletedEvidence,
            ScenarioRehearsalBatchRetentionEvent
                    .PreservedDisposition childDisposition,
            ScenarioRehearsalBatchRetentionEvent
                    .PreservedDisposition auditDisposition) {
        if (!signer.available()) {
            throw new ScenarioRehearsalBatchFinalizationException(
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.SIGNER_UNAVAILABLE);
        }
        ScenarioRehearsalBatchRetentionEvent unsigned =
                new ScenarioRehearsalBatchRetentionEvent(
                        "", eventId,
                        commandId, scope, requestId, jobId,
                        manifestFingerprint,
                        revision, type, retainUntil, occurredAt,
                        actorId, reasonCode, holdId,
                        evidenceBundleFingerprint,
                        previousEventFingerprint,
                        deletedJobs, deletedItems, deletedEvidence,
                        childDisposition, auditDisposition,
                        VisualRunEvidenceSeal.unsigned());
        String fingerprint = unsigned.eventFingerprint();
        VisualRunEvidenceSeal seal;
        try {
            seal = signer.seal(
                    fingerprint,
                    required(
                            signingRequestId,
                            "signingRequestId",
                            256));
        } catch (RuntimeException unavailable) {
            throw new ScenarioRehearsalBatchFinalizationException(
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.SIGNER_UNAVAILABLE);
        }
        ScenarioRehearsalBatchRetentionEvent signed =
                unsigned.withEvidenceSeal(seal);
        try {
            verify(signed);
        } catch (RuntimeException invalid) {
            throw new ScenarioRehearsalBatchFinalizationException(
                    ScenarioRehearsalBatchFinalizationException
                            .Reason.SIGNATURE_INVALID);
        }
        return signed;
    }

    private void verify(
            ScenarioRehearsalBatchRetentionEvent event) {
        String fingerprint = event.eventFingerprint();
        VisualRunEvidenceSeal seal = event.evidenceSeal();
        if (!seal.signed()
                || !fingerprint.equals(
                seal.materialFingerprint())
                || !signer.verify(
                seal, fingerprint).valid()) {
            throw new IllegalStateException(
                    "Scenario batch retention event signature is invalid");
        }
    }

    private void insertState(
            ScenarioRehearsalBatchRetentionState state) {
        CapabilitySnapshot.Scope scope = state.scope();
        jdbc.update("""
                        INSERT INTO scenario_rehearsal_batch_retention_states (
                            tenant_id, organization_id, project_id,
                            environment_id, region, job_id, request_id,
                            manifest_fingerprint,
                            evidence_bundle_fingerprint, status, revision,
                            retain_until, state_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                state.jobId(),
                state.requestId(),
                state.manifestFingerprint(),
                state.evidenceBundleFingerprint(),
                state.status().name(),
                state.revision(),
                state.retainUntil().toString(),
                serialize(state));
    }

    private void updateState(
            ScenarioRehearsalBatchRetentionState current,
            ScenarioRehearsalBatchRetentionState updated) {
        CapabilitySnapshot.Scope scope = current.scope();
        int changed = jdbc.update("""
                        UPDATE scenario_rehearsal_batch_retention_states
                        SET status = ?, revision = ?, state_json = ?
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND job_id = ? AND revision = ?
                        """,
                updated.status().name(),
                updated.revision(),
                serialize(updated),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                current.jobId(),
                current.revision());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Scenario batch retention state changed concurrently");
        }
    }

    private void insertEvent(
            ScenarioRehearsalBatchRetentionEvent event) {
        CapabilitySnapshot.Scope scope = event.scope();
        jdbc.update("""
                        INSERT INTO scenario_rehearsal_batch_retention_events (
                            tenant_id, organization_id, project_id,
                            environment_id, region, job_id, revision,
                            command_id, type, hold_id,
                            event_fingerprint, event_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                event.jobId(),
                event.revision(),
                event.commandId(),
                event.type().name(),
                event.holdId(),
                event.eventFingerprint(),
                serialize(event));
    }

    private Optional<ScenarioRehearsalBatchRetentionState> locked(
            CapabilitySnapshot.Scope scope,
            String jobId) {
        Optional<ScenarioRehearsalBatchRetentionState> stored =
                query(SELECT_STATE_FOR_UPDATE, scope, jobId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ScenarioRehearsalBatchRetentionState expected =
                rebuild(events(scope, jobId));
        if (!expected.equals(stored.orElseThrow())) {
            throw new IllegalStateException(
                    "Scenario batch retention state differs from its signed event chain");
        }
        return Optional.of(expected);
    }

    private Optional<ScenarioRehearsalBatchRetentionState> query(
            String sql,
            CapabilitySnapshot.Scope scope,
            String jobId) {
        List<ScenarioRehearsalBatchRetentionState> values =
                jdbc.query(
                        sql,
                        (result, row) -> {
                            ScenarioRehearsalBatchRetentionState state =
                                    deserialize(
                                            result.getString(
                                                    "state_json"),
                                            ScenarioRehearsalBatchRetentionState
                                                    .class);
                            if (!scope.equals(state.scope())
                                    || !jobId.equals(state.jobId())
                                    || !result.getString(
                                    "request_id").equals(
                                    state.requestId())
                                    || !result.getString(
                                    "manifest_fingerprint").equals(
                                    state.manifestFingerprint())
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
                                        "Scenario batch retention index differs from state JSON");
                            }
                            verify(state.latestEvent());
                            return state;
                        },
                        scope.tenantId(),
                        scope.organizationId(),
                        scope.projectId(),
                        scope.environmentId(),
                        scope.region(),
                        jobId);
        return values.stream().findFirst();
    }

    private void requireExactEvidence(
            ScenarioRehearsalBatchEvidenceBundle expected) {
        ScenarioRehearsalBatchJob job =
                expected.index().job();
        ScenarioRehearsalBatchEvidenceBundle stored =
                evidence.find(job.scope(), job.jobId())
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Scenario batch evidence is unavailable for retention"));
        if (!stored.equals(expected)) {
            throw new IllegalStateException(
                    "Scenario batch evidence differs from retention registration");
        }
    }

    private PurgeMaterial requireExactAggregate(
            ScenarioRehearsalBatchRetentionState state) {
        ScenarioRehearsalBatchEvidenceBundle bundle =
                evidence.find(state.scope(), state.jobId())
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Scenario batch evidence differs from retention authority"));
        ScenarioRehearsalBatchJob expectedJob =
                bundle.index().job();
        if (!state.requestId().equals(expectedJob.requestId())
                || !state.manifestFingerprint().equals(
                expectedJob.manifestFingerprint())
                || !state.evidenceBundleFingerprint().equals(
                bundle.bundleFingerprint())) {
            throw new IllegalStateException(
                    "Scenario batch evidence identity differs from retention authority");
        }
        ScenarioRehearsalBatchJob storedJob =
                exactStoredJob(state);
        if (!storedJob.equals(expectedJob)) {
            throw new IllegalStateException(
                    "Scenario batch job differs from signed evidence");
        }
        List<ScenarioRehearsalBatchItemPage.Item> storedItems =
                exactStoredItems(bundle);
        if (!storedItems.equals(bundle.index().items())) {
            throw new IllegalStateException(
                    "Scenario batch items differ from signed evidence");
        }
        return new PurgeMaterial(storedItems.size());
    }

    private ScenarioRehearsalBatchJob exactStoredJob(
            ScenarioRehearsalBatchRetentionState state) {
        List<ScenarioRehearsalBatchJob> jobs = jdbc.query("""
                        SELECT tenant_id, organization_id, project_id,
                               environment_id, region, request_id,
                               request_fingerprint, manifest_fingerprint,
                               status, record_fingerprint, job_json
                        FROM scenario_rehearsal_batch_jobs
                        WHERE job_id = ?
                        """,
                (result, row) -> {
                    ScenarioRehearsalBatchJob job = deserialize(
                            result.getString("job_json"),
                            ScenarioRehearsalBatchJob.class);
                    try {
                        ScenarioRehearsalBatchIntegrity.verify(
                                mapper, job);
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalStateException(
                                "Stored Scenario batch job failed integrity validation",
                                invalid);
                    }
                    CapabilitySnapshot.Scope indexed =
                            new CapabilitySnapshot.Scope(
                                    result.getString("tenant_id"),
                                    result.getString(
                                            "organization_id"),
                                    result.getString("project_id"),
                                    result.getString(
                                            "environment_id"),
                                    result.getString("region"));
                    if (!indexed.equals(job.scope())
                            || !result.getString("request_id")
                            .equals(job.requestId())
                            || !result.getString(
                            "request_fingerprint").equals(
                            job.requestFingerprint())
                            || !result.getString(
                            "manifest_fingerprint").equals(
                            job.manifestFingerprint())
                            || !result.getString("status")
                            .equals(job.status().name())
                            || !result.getString(
                            "record_fingerprint").equals(
                            job.recordFingerprint())) {
                        throw new IllegalStateException(
                                "Stored Scenario batch job index differs from sealed JSON");
                    }
                    return job;
                },
                state.jobId());
        if (jobs.size() != 1
                || !state.scope().equals(
                jobs.getFirst().scope())) {
            throw new IllegalStateException(
                    "Scenario batch job differs from retention scope");
        }
        return jobs.getFirst();
    }

    private List<ScenarioRehearsalBatchItemPage.Item>
    exactStoredItems(
            ScenarioRehearsalBatchEvidenceBundle bundle) {
        ScenarioRehearsalBatchManifest manifest =
                bundle.index().manifest();
        List<ScenarioRehearsalBatchItemPage.Item> values =
                jdbc.query("""
                                SELECT item_index, compiled_plan_id,
                                       compiled_plan_revision,
                                       compiled_plan_fingerprint,
                                       child_request_id, status,
                                       attempt_count, run_id,
                                       evidence_bundle_fingerprint,
                                       workbook_seed_fingerprint,
                                       failure_code, started_at,
                                       completed_at,
                                       execution_timeout_millis,
                                       record_fingerprint
                                FROM scenario_rehearsal_batch_items
                                WHERE job_id = ?
                                ORDER BY item_index
                        """,
                        (result, row) -> exactStoredItem(
                                result, manifest),
                        bundle.index().job().jobId());
        if (values.size() != manifest.entries().size()) {
            throw new IllegalStateException(
                    "Scenario batch item count differs from its manifest");
        }
        return List.copyOf(values);
    }

    private ScenarioRehearsalBatchItemPage.Item exactStoredItem(
            ResultSet result,
            ScenarioRehearsalBatchManifest manifest)
            throws SQLException {
        ScenarioRehearsalBatchItemPage.Item item =
                new ScenarioRehearsalBatchItemPage.Item(
                        result.getInt("item_index"),
                        new MirrorArtifactRef(
                                "COMPILED_REHEARSAL_PLAN",
                                result.getString(
                                        "compiled_plan_id"),
                                result.getLong(
                                        "compiled_plan_revision"),
                                result.getString(
                                        "compiled_plan_fingerprint")),
                        result.getString("child_request_id"),
                        ScenarioRehearsalBatchItemPage.Status
                                .valueOf(
                                        result.getString("status")),
                        result.getInt("attempt_count"),
                        result.getString("run_id"),
                        result.getString(
                                "evidence_bundle_fingerprint"),
                        result.getString(
                                "workbook_seed_fingerprint"),
                        result.getString("failure_code"),
                        instant(result, "started_at"),
                        instant(result, "completed_at"));
        if (item.itemIndex() >= manifest.entries().size()) {
            throw new IllegalStateException(
                    "Scenario batch item is outside its manifest");
        }
        Duration timeout = Duration.ofMillis(
                result.getLong("execution_timeout_millis"));
        ScenarioRehearsalBatchManifest.Entry entry =
                manifest.entries().get(item.itemIndex());
        LinkedHashMap<String, Object> material =
                new LinkedHashMap<>();
        material.put("item", item);
        material.put("executionTimeout", timeout);
        String fingerprint = ProtocolFingerprint.ofBounded(
                mapper, material, 128 * 1024);
        if (!entry.compiledPlanRef().equals(
                item.compiledPlanRef())
                || !entry.aggregateRequestId().equals(
                item.childRequestId())
                || !entry.executionTimeout().equals(timeout)
                || !fingerprint.equals(
                result.getString("record_fingerprint"))) {
            throw new IllegalStateException(
                    "Stored Scenario batch item failed manifest or integrity validation");
        }
        return item;
    }

    private void deleteAggregate(
            ScenarioRehearsalBatchRetentionState state,
            int expectedItems) {
        int deletedItems = jdbc.update("""
                        DELETE FROM scenario_rehearsal_batch_items
                        WHERE job_id = ?
                        """,
                state.jobId());
        if (deletedItems != expectedItems) {
            throw new IllegalStateException(
                    "Scenario batch items changed during governed deletion");
        }
        CapabilitySnapshot.Scope scope = state.scope();
        int deletedEvidence = jdbc.update("""
                        DELETE FROM scenario_rehearsal_batch_evidence
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND job_id = ?
                          AND bundle_fingerprint = ?
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                state.jobId(),
                state.evidenceBundleFingerprint());
        if (deletedEvidence != 1) {
            throw new IllegalStateException(
                    "Scenario batch evidence changed during governed deletion");
        }
        int deletedJob = jdbc.update("""
                        DELETE FROM scenario_rehearsal_batch_jobs
                        WHERE job_id = ? AND tenant_id = ?
                          AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ?
                          AND manifest_fingerprint = ?
                        """,
                state.jobId(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                state.manifestFingerprint());
        if (deletedJob != 1) {
            throw new IllegalStateException(
                    "Scenario batch job changed during governed deletion");
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Scenario batch retention value cannot be serialized",
                    failure);
        }
    }

    private <T> T deserialize(
            String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Stored Scenario batch retention value is invalid",
                    failure);
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(
                databaseClock.get(),
                "Scenario batch retention database clock returned null");
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        DataSource source = Objects.requireNonNull(
                jdbc.getDataSource(),
                "Scenario batch retention datasource is unavailable");
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
                        "Scenario batch retention database clock returned no row");
            }
            Timestamp value = result.getTimestamp(1);
            if (value == null) {
                throw new IllegalStateException(
                        "Scenario batch retention database clock returned null");
            }
            return value.toInstant();
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "Scenario batch retention database clock is unavailable",
                    unavailable);
        }
    }

    private static Instant instant(
            ResultSet result, String column)
            throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
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

    private record PurgeMaterial(int itemCount) {
        private PurgeMaterial {
            if (itemCount < 1
                    || itemCount
                    > ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES) {
                throw new IllegalArgumentException(
                        "Scenario batch purge item count is invalid");
            }
        }
    }
}
