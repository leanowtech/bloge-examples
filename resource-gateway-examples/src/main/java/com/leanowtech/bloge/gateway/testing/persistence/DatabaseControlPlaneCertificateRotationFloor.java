package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEvent;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationFloor;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Database-clock, whole-record-fingerprinted certificate generation floor.
 *
 * <p>A stable deployment/target lock serializes bootstrap, event acceptance, replay, and due
 * activation across replicas. The floor and its event journal are committed in one transaction.
 * Event identity and target-generation uniqueness prevent cross-target reuse and same-generation
 * forks; exact replay is idempotent. Every mutable row is canonically fingerprinted so direct
 * database drift fails closed rather than becoming a new trusted head.</p>
 */
public final class DatabaseControlPlaneCertificateRotationFloor
        implements ControlPlaneCertificateRotationFloor {

    private static final String FLOOR_RECORD_SCHEMA =
            "bloge.controlPlaneCertificateRotationFloorRecord.v1";
    private static final String EVENT_RECORD_SCHEMA =
            "bloge.controlPlaneCertificateRotationEventRecord.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String deploymentScopeId;
    private final Map<String, InitialTarget> initialTargets;
    private final TransactionTemplate mutations;

    /**
     * Creates one durable floor for an exact deployment target inventory.
     *
     * @param jdbc isolated testing-control-plane JDBC facade
     * @param objectMapper canonical record fingerprint mapper
     * @param deploymentScopeId stable deployment scope
     * @param initialTargets non-empty out-of-band active target inventory
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseControlPlaneCertificateRotationFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String deploymentScopeId,
            Map<String, InitialTarget> initialTargets,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.deploymentScopeId = normalized(deploymentScopeId);
        if (!IDENTIFIER.matcher(this.deploymentScopeId).matches()
                || initialTargets == null || initialTargets.isEmpty()
                || initialTargets.size() > 64) {
            throw invalid("Control-plane certificate rotation floor configuration is invalid");
        }
        TreeMap<String, InitialTarget> sorted = new TreeMap<>();
        initialTargets.forEach((targetId, target) -> {
            String normalized = normalized(targetId);
            if (!IDENTIFIER.matcher(normalized).matches() || target == null
                    || sorted.putIfAbsent(normalized, target) != null) {
                throw invalid("Control-plane certificate rotation target inventory is invalid");
            }
        });
        this.initialTargets = Map.copyOf(sorted);
        this.mutations = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        this.mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates durable tables and establishes or verifies every initial target floor. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_control_plane_certificate_rotation_locks (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    target_id VARCHAR(255) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, target_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_control_plane_certificate_rotation_floors (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    target_id VARCHAR(255) NOT NULL,
                    active_generation BIGINT NOT NULL,
                    active_material_id VARCHAR(255) NOT NULL,
                    active_settings_fingerprint VARCHAR(71) NOT NULL,
                    active_event_id VARCHAR(255) NOT NULL,
                    active_event_fingerprint VARCHAR(71) NOT NULL,
                    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    pending_generation BIGINT NOT NULL,
                    pending_material_id VARCHAR(255) NOT NULL,
                    pending_settings_fingerprint VARCHAR(71) NOT NULL,
                    pending_event_id VARCHAR(255) NOT NULL,
                    pending_event_fingerprint VARCHAR(71) NOT NULL,
                    pending_activate_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, target_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_control_plane_certificate_rotation_events (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    target_id VARCHAR(255) NOT NULL,
                    event_id VARCHAR(255) NOT NULL,
                    event_fingerprint VARCHAR(71) NOT NULL,
                    generation BIGINT NOT NULL,
                    previous_settings_fingerprint VARCHAR(71) NOT NULL,
                    material_id VARCHAR(255) NOT NULL,
                    settings_fingerprint VARCHAR(71) NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    activate_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    activation_state VARCHAR(16) NOT NULL,
                    activated_at TIMESTAMP WITH TIME ZONE,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, event_id),
                    UNIQUE (deployment_scope_id, target_id, generation)
                )
                """);
        initialTargets.forEach((targetId, target) ->
                mutations.executeWithoutResult(status -> bootstrap(targetId, target)));
    }

    /** {@inheritDoc} */
    @Override
    public Acceptance accept(ControlPlaneCertificateRotationEvent event) {
        ControlPlaneCertificateRotationEvent required = Objects.requireNonNull(event, "event");
        ControlPlaneCertificateRotationEvent.Material material = required.material();
        if (!deploymentScopeId.equals(material.deploymentScopeId())
                || !initialTargets.containsKey(material.targetId())) {
            throw invalid("Control-plane certificate rotation event floor binding does not match");
        }
        try {
            return mutations.execute(status -> acceptLocked(required));
        } catch (DataIntegrityViolationException conflict) {
            throw invalid("Control-plane certificate rotation event conflicts with durable state");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot(String targetId) {
        String target = normalized(targetId);
        if (!initialTargets.containsKey(target)) {
            throw invalid("Control-plane certificate rotation target is unknown");
        }
        return mutations.execute(status -> {
            lockTarget(target);
            FloorRecord current = requiredCurrent(target);
            return snapshot(advanceIfDue(current, databaseNow()));
        });
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Snapshot> snapshots() {
        LinkedHashMap<String, Snapshot> result = new LinkedHashMap<>();
        initialTargets.keySet().stream().sorted()
                .forEach(target -> result.put(target, snapshot(target)));
        return Map.copyOf(result);
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private void bootstrap(String targetId, InitialTarget initial) {
        lockTarget(targetId);
        Instant now = databaseNow();
        FloorRecord current = current(targetId);
        if (current == null) {
            persistFloor(FloorRecord.initial(deploymentScopeId, targetId, initial, now));
            return;
        }
        current = advanceIfDue(validateFloor(current, targetId), now);
        if (initial.generation() < current.activeGeneration()) {
            throw invalid("Control-plane certificate rotation bootstrap rejected rollback");
        }
        if (initial.generation() > current.activeGeneration()) {
            throw invalid("Control-plane certificate rotation bootstrap rejected generation gap");
        }
        if (!initial.materialId().equals(current.activeMaterialId())
                || !initial.settingsFingerprint().equals(
                current.activeSettingsFingerprint())) {
            throw invalid("Control-plane certificate rotation bootstrap rejected fork");
        }
    }

    private Acceptance acceptLocked(ControlPlaneCertificateRotationEvent event) {
        ControlPlaneCertificateRotationEvent.Material material = event.material();
        lockTarget(material.targetId());
        Instant now = databaseNow();
        FloorRecord current = advanceIfDue(requiredCurrent(material.targetId()), now);
        EventRecord existing = event(material.eventId());
        if (existing != null) {
            validateEvent(existing);
            if (!existing.exact(event)) {
                throw invalid("Control-plane certificate rotation event id was reused");
            }
            return new Acceptance(AcceptanceStatus.REPLAYED, snapshot(current));
        }
        if (now.isBefore(material.notBefore()) || !now.isBefore(material.expiresAt())) {
            throw invalid("Control-plane certificate rotation event time window is invalid");
        }
        if (current.pendingGeneration() > 0) {
            throw invalid("Control-plane certificate rotation floor already has a successor");
        }
        if (material.generation() != current.activeGeneration() + 1) {
            throw invalid("Control-plane certificate rotation floor rejected generation");
        }
        if (!material.previousMaterialFingerprint().equals(
                current.activeSettingsFingerprint())) {
            throw invalid("Control-plane certificate rotation floor rejected predecessor");
        }

        boolean due = !material.activateAt().isAfter(now);
        EventRecord accepted = EventRecord.accepted(event, now, due);
        insertEvent(accepted);
        FloorRecord next = due
                ? current.activate(material, event.materialFingerprint(), now)
                : current.stage(material, event.materialFingerprint(), now);
        persistFloor(next);
        return new Acceptance(due ? AcceptanceStatus.ACTIVATED
                : AcceptanceStatus.STAGED, snapshot(next));
    }

    private FloorRecord advanceIfDue(FloorRecord current, Instant now) {
        if (current.pendingGeneration() == 0
                || now.isBefore(current.pendingActivateAt())) {
            return current;
        }
        EventRecord pending = event(current.pendingEventId());
        if (pending == null || !pending.valid(objectMapper, deploymentScopeId)
                || !pending.eventFingerprint().equals(current.pendingEventFingerprint())
                || pending.generation() != current.pendingGeneration()
                || pending.state() != EventState.STAGED) {
            throw new IllegalStateException(
                    "Control-plane certificate rotation pending event is corrupt");
        }
        EventRecord activated = pending.activate(now);
        updateEvent(activated);
        FloorRecord next = current.activatePending(now);
        persistFloor(next);
        return next;
    }

    private FloorRecord requiredCurrent(String targetId) {
        FloorRecord current = current(targetId);
        if (current == null) {
            throw new IllegalStateException(
                    "Control-plane certificate rotation floor is missing");
        }
        return validateFloor(current, targetId);
    }

    private FloorRecord validateFloor(FloorRecord current, String targetId) {
        if (!current.valid(objectMapper, deploymentScopeId, targetId)) {
            throw new IllegalStateException(
                    "Control-plane certificate rotation floor is corrupt");
        }
        return current;
    }

    private void validateEvent(EventRecord event) {
        if (!event.valid(objectMapper, deploymentScopeId)) {
            throw new IllegalStateException(
                    "Control-plane certificate rotation event journal is corrupt");
        }
    }

    private void lockTarget(String targetId) {
        jdbc.update("""
                MERGE INTO rg_control_plane_certificate_rotation_locks
                    (deployment_scope_id, target_id)
                    KEY (deployment_scope_id, target_id) VALUES (?, ?)
                """, deploymentScopeId, targetId);
        jdbc.queryForObject("""
                SELECT target_id
                FROM rg_control_plane_certificate_rotation_locks
                WHERE deployment_scope_id = ? AND target_id = ? FOR UPDATE
                """, String.class, deploymentScopeId, targetId);
    }

    private FloorRecord current(String targetId) {
        List<FloorRecord> rows = jdbc.query("""
                SELECT deployment_scope_id, target_id, active_generation,
                       active_material_id, active_settings_fingerprint,
                       active_event_id, active_event_fingerprint, activated_at,
                       pending_generation, pending_material_id,
                       pending_settings_fingerprint, pending_event_id,
                       pending_event_fingerprint, pending_activate_at,
                       updated_at, record_fingerprint
                FROM rg_control_plane_certificate_rotation_floors
                WHERE deployment_scope_id = ? AND target_id = ?
                """, this::floorRow, deploymentScopeId, targetId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate control-plane certificate rotation floor");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private EventRecord event(String eventId) {
        List<EventRecord> rows = jdbc.query("""
                SELECT deployment_scope_id, target_id, event_id, event_fingerprint,
                       generation, previous_settings_fingerprint, material_id,
                       settings_fingerprint, policy_fingerprint, activate_at,
                       expires_at, accepted_at, activation_state, activated_at,
                       record_fingerprint
                FROM rg_control_plane_certificate_rotation_events
                WHERE deployment_scope_id = ? AND event_id = ?
                """, this::eventRow, deploymentScopeId, eventId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate control-plane certificate rotation event");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void persistFloor(FloorRecord record) {
        FloorRecord signed = record.withFingerprint(
                floorFingerprint(record.withFingerprint("")));
        jdbc.update("""
                MERGE INTO rg_control_plane_certificate_rotation_floors (
                    deployment_scope_id, target_id, active_generation,
                    active_material_id, active_settings_fingerprint,
                    active_event_id, active_event_fingerprint, activated_at,
                    pending_generation, pending_material_id,
                    pending_settings_fingerprint, pending_event_id,
                    pending_event_fingerprint, pending_activate_at,
                    updated_at, record_fingerprint
                ) KEY (deployment_scope_id, target_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, signed.deploymentScopeId(), signed.targetId(), signed.activeGeneration(),
                signed.activeMaterialId(), signed.activeSettingsFingerprint(),
                signed.activeEventId(), signed.activeEventFingerprint(),
                Timestamp.from(signed.activatedAt()), signed.pendingGeneration(),
                signed.pendingMaterialId(), signed.pendingSettingsFingerprint(),
                signed.pendingEventId(), signed.pendingEventFingerprint(),
                timestamp(signed.pendingActivateAt()), Timestamp.from(signed.updatedAt()),
                signed.recordFingerprint());
    }

    private void insertEvent(EventRecord record) {
        EventRecord signed = record.withFingerprint(
                eventFingerprint(record.withFingerprint("")));
        jdbc.update("""
                INSERT INTO rg_control_plane_certificate_rotation_events (
                    deployment_scope_id, target_id, event_id, event_fingerprint,
                    generation, previous_settings_fingerprint, material_id,
                    settings_fingerprint, policy_fingerprint, activate_at,
                    expires_at, accepted_at, activation_state, activated_at,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, signed.deploymentScopeId(), signed.targetId(), signed.eventId(),
                signed.eventFingerprint(), signed.generation(),
                signed.previousSettingsFingerprint(), signed.materialId(),
                signed.settingsFingerprint(), signed.policyFingerprint(),
                Timestamp.from(signed.activateAt()), Timestamp.from(signed.expiresAt()),
                Timestamp.from(signed.acceptedAt()), signed.state().name(),
                timestamp(signed.activatedAt()), signed.recordFingerprint());
    }

    private void updateEvent(EventRecord record) {
        EventRecord signed = record.withFingerprint(
                eventFingerprint(record.withFingerprint("")));
        int changed = jdbc.update("""
                UPDATE rg_control_plane_certificate_rotation_events
                SET activation_state = ?, activated_at = ?, record_fingerprint = ?
                WHERE deployment_scope_id = ? AND event_id = ?
                  AND event_fingerprint = ? AND activation_state = 'STAGED'
                """, signed.state().name(), timestamp(signed.activatedAt()),
                signed.recordFingerprint(), deploymentScopeId, signed.eventId(),
                signed.eventFingerprint());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Control-plane certificate rotation event activation lost its fence");
        }
    }

    private FloorRecord floorRow(ResultSet result, int rowNumber) throws SQLException {
        return new FloorRecord(result.getString("deployment_scope_id"),
                result.getString("target_id"), result.getLong("active_generation"),
                result.getString("active_material_id"),
                result.getString("active_settings_fingerprint"),
                result.getString("active_event_id"),
                result.getString("active_event_fingerprint"),
                instant(result, "activated_at"), result.getLong("pending_generation"),
                result.getString("pending_material_id"),
                result.getString("pending_settings_fingerprint"),
                result.getString("pending_event_id"),
                result.getString("pending_event_fingerprint"),
                instant(result, "pending_activate_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private EventRecord eventRow(ResultSet result, int rowNumber) throws SQLException {
        return new EventRecord(result.getString("deployment_scope_id"),
                result.getString("target_id"), result.getString("event_id"),
                result.getString("event_fingerprint"), result.getLong("generation"),
                result.getString("previous_settings_fingerprint"),
                result.getString("material_id"), result.getString("settings_fingerprint"),
                result.getString("policy_fingerprint"), instant(result, "activate_at"),
                instant(result, "expires_at"), instant(result, "accepted_at"),
                EventState.valueOf(result.getString("activation_state")),
                instant(result, "activated_at"), result.getString("record_fingerprint"));
    }

    private String floorFingerprint(FloorRecord record) {
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", FLOOR_RECORD_SCHEMA);
        material.put("deploymentScopeId", record.deploymentScopeId());
        material.put("targetId", record.targetId());
        material.put("activeGeneration", record.activeGeneration());
        material.put("activeMaterialId", record.activeMaterialId());
        material.put("activeSettingsFingerprint", record.activeSettingsFingerprint());
        material.put("activeEventId", record.activeEventId());
        material.put("activeEventFingerprint", record.activeEventFingerprint());
        material.put("activatedAt", record.activatedAt().toString());
        material.put("pendingGeneration", record.pendingGeneration());
        material.put("pendingMaterialId", record.pendingMaterialId());
        material.put("pendingSettingsFingerprint", record.pendingSettingsFingerprint());
        material.put("pendingEventId", record.pendingEventId());
        material.put("pendingEventFingerprint", record.pendingEventFingerprint());
        material.put("pendingActivateAt", text(record.pendingActivateAt()));
        material.put("updatedAt", record.updatedAt().toString());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private String eventFingerprint(EventRecord record) {
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", EVENT_RECORD_SCHEMA);
        material.put("deploymentScopeId", record.deploymentScopeId());
        material.put("targetId", record.targetId());
        material.put("eventId", record.eventId());
        material.put("eventFingerprint", record.eventFingerprint());
        material.put("generation", record.generation());
        material.put("previousSettingsFingerprint", record.previousSettingsFingerprint());
        material.put("materialId", record.materialId());
        material.put("settingsFingerprint", record.settingsFingerprint());
        material.put("policyFingerprint", record.policyFingerprint());
        material.put("activateAt", record.activateAt().toString());
        material.put("expiresAt", record.expiresAt().toString());
        material.put("acceptedAt", record.acceptedAt().toString());
        material.put("state", record.state().name());
        material.put("activatedAt", text(record.activatedAt()));
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(value, "database time").toInstant();
    }

    private static Snapshot snapshot(FloorRecord record) {
        return new Snapshot(Snapshot.SCHEMA_VERSION, record.deploymentScopeId(),
                record.targetId(), record.activeGeneration(), record.activeMaterialId(),
                record.activeSettingsFingerprint(), record.activeEventId(),
                record.activeEventFingerprint(), record.activatedAt(),
                record.pendingGeneration(), record.pendingMaterialId(),
                record.pendingSettingsFingerprint(), record.pendingEventId(),
                record.pendingEventFingerprint(), record.pendingActivateAt(),
                record.updatedAt());
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String text(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private enum EventState {
        STAGED,
        ACTIVE
    }

    private record FloorRecord(
            String deploymentScopeId,
            String targetId,
            long activeGeneration,
            String activeMaterialId,
            String activeSettingsFingerprint,
            String activeEventId,
            String activeEventFingerprint,
            Instant activatedAt,
            long pendingGeneration,
            String pendingMaterialId,
            String pendingSettingsFingerprint,
            String pendingEventId,
            String pendingEventFingerprint,
            Instant pendingActivateAt,
            Instant updatedAt,
            String recordFingerprint) {

        private static FloorRecord initial(
                String scope, String target, InitialTarget initial, Instant now) {
            return new FloorRecord(scope, target, initial.generation(), initial.materialId(),
                    initial.settingsFingerprint(), "", "", now, 0, "", "", "", "",
                    null, now, "");
        }

        private FloorRecord stage(
                ControlPlaneCertificateRotationEvent.Material material,
                String eventFingerprint,
                Instant now) {
            return new FloorRecord(deploymentScopeId, targetId, activeGeneration,
                    activeMaterialId, activeSettingsFingerprint, activeEventId,
                    activeEventFingerprint, activatedAt, material.generation(),
                    material.materialId(), material.settingsFingerprint(), material.eventId(),
                    eventFingerprint, material.activateAt(), now, "");
        }

        private FloorRecord activate(
                ControlPlaneCertificateRotationEvent.Material material,
                String eventFingerprint,
                Instant now) {
            return new FloorRecord(deploymentScopeId, targetId, material.generation(),
                    material.materialId(), material.settingsFingerprint(), material.eventId(),
                    eventFingerprint, material.activateAt(), 0, "", "", "", "", null,
                    now, "");
        }

        private FloorRecord activatePending(Instant now) {
            return new FloorRecord(deploymentScopeId, targetId, pendingGeneration,
                    pendingMaterialId, pendingSettingsFingerprint, pendingEventId,
                    pendingEventFingerprint, pendingActivateAt, 0, "", "", "", "", null,
                    now, "");
        }

        private FloorRecord withFingerprint(String value) {
            return new FloorRecord(deploymentScopeId, targetId, activeGeneration,
                    activeMaterialId, activeSettingsFingerprint, activeEventId,
                    activeEventFingerprint, activatedAt, pendingGeneration, pendingMaterialId,
                    pendingSettingsFingerprint, pendingEventId, pendingEventFingerprint,
                    pendingActivateAt, updatedAt, value);
        }

        private boolean valid(
                ObjectMapper mapper, String expectedScope, String expectedTarget) {
            try {
                new Snapshot(Snapshot.SCHEMA_VERSION, deploymentScopeId, targetId,
                        activeGeneration, activeMaterialId, activeSettingsFingerprint,
                        activeEventId, activeEventFingerprint, activatedAt, pendingGeneration,
                        pendingMaterialId, pendingSettingsFingerprint, pendingEventId,
                        pendingEventFingerprint, pendingActivateAt, updatedAt);
                return deploymentScopeId.equals(expectedScope) && targetId.equals(expectedTarget)
                        && recordFingerprint.equals(fingerprint(mapper));
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        private String fingerprint(ObjectMapper mapper) {
            LinkedHashMap<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", FLOOR_RECORD_SCHEMA);
            material.put("deploymentScopeId", deploymentScopeId);
            material.put("targetId", targetId);
            material.put("activeGeneration", activeGeneration);
            material.put("activeMaterialId", activeMaterialId);
            material.put("activeSettingsFingerprint", activeSettingsFingerprint);
            material.put("activeEventId", activeEventId);
            material.put("activeEventFingerprint", activeEventFingerprint);
            material.put("activatedAt", activatedAt.toString());
            material.put("pendingGeneration", pendingGeneration);
            material.put("pendingMaterialId", pendingMaterialId);
            material.put("pendingSettingsFingerprint", pendingSettingsFingerprint);
            material.put("pendingEventId", pendingEventId);
            material.put("pendingEventFingerprint", pendingEventFingerprint);
            material.put("pendingActivateAt", text(pendingActivateAt));
            material.put("updatedAt", updatedAt.toString());
            return ProtocolFingerprint.of(mapper, material);
        }
    }

    private record EventRecord(
            String deploymentScopeId,
            String targetId,
            String eventId,
            String eventFingerprint,
            long generation,
            String previousSettingsFingerprint,
            String materialId,
            String settingsFingerprint,
            String policyFingerprint,
            Instant activateAt,
            Instant expiresAt,
            Instant acceptedAt,
            EventState state,
            Instant activatedAt,
            String recordFingerprint) {

        private static EventRecord accepted(
                ControlPlaneCertificateRotationEvent event,
                Instant now,
                boolean active) {
            var material = event.material();
            return new EventRecord(material.deploymentScopeId(), material.targetId(),
                    material.eventId(), event.materialFingerprint(), material.generation(),
                    material.previousMaterialFingerprint(), material.materialId(),
                    material.settingsFingerprint(), material.policyFingerprint(),
                    material.activateAt(), material.expiresAt(), now,
                    active ? EventState.ACTIVE : EventState.STAGED, active ? now : null, "");
        }

        private EventRecord activate(Instant now) {
            return new EventRecord(deploymentScopeId, targetId, eventId, eventFingerprint,
                    generation, previousSettingsFingerprint, materialId, settingsFingerprint,
                    policyFingerprint, activateAt, expiresAt, acceptedAt, EventState.ACTIVE,
                    now, "");
        }

        private EventRecord withFingerprint(String value) {
            return new EventRecord(deploymentScopeId, targetId, eventId, eventFingerprint,
                    generation, previousSettingsFingerprint, materialId, settingsFingerprint,
                    policyFingerprint, activateAt, expiresAt, acceptedAt, state, activatedAt,
                    value);
        }

        private boolean exact(ControlPlaneCertificateRotationEvent event) {
            var material = event.material();
            return eventFingerprint.equals(event.materialFingerprint())
                    && targetId.equals(material.targetId())
                    && generation == material.generation()
                    && previousSettingsFingerprint.equals(
                    material.previousMaterialFingerprint())
                    && materialId.equals(material.materialId())
                    && settingsFingerprint.equals(material.settingsFingerprint())
                    && policyFingerprint.equals(material.policyFingerprint())
                    && activateAt.equals(material.activateAt())
                    && expiresAt.equals(material.expiresAt());
        }

        private boolean valid(ObjectMapper mapper, String expectedScope) {
            try {
                if (!deploymentScopeId.equals(expectedScope) || !IDENTIFIER.matcher(targetId)
                        .matches() || !IDENTIFIER.matcher(eventId).matches()
                        || generation < 2 || acceptedAt == null || state == null
                        || state == EventState.STAGED && activatedAt != null
                        || state == EventState.ACTIVE && activatedAt == null) {
                    return false;
                }
                return recordFingerprint.equals(fingerprint(mapper));
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        private String fingerprint(ObjectMapper mapper) {
            LinkedHashMap<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", EVENT_RECORD_SCHEMA);
            material.put("deploymentScopeId", deploymentScopeId);
            material.put("targetId", targetId);
            material.put("eventId", eventId);
            material.put("eventFingerprint", eventFingerprint);
            material.put("generation", generation);
            material.put("previousSettingsFingerprint", previousSettingsFingerprint);
            material.put("materialId", materialId);
            material.put("settingsFingerprint", settingsFingerprint);
            material.put("policyFingerprint", policyFingerprint);
            material.put("activateAt", activateAt.toString());
            material.put("expiresAt", expiresAt.toString());
            material.put("acceptedAt", acceptedAt.toString());
            material.put("state", state.name());
            material.put("activatedAt", text(activatedAt));
            return ProtocolFingerprint.of(mapper, material);
        }
    }
}
