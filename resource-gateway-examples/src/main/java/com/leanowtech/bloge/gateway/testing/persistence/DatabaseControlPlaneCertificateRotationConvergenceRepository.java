package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationConvergenceRepository;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationFleetPolicy;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Database-clock authority for exact certificate-rotation replica convergence.
 *
 * <p>Each process start owns a distinct target acknowledgement row. A stable lock serializes that
 * row's sequence, while a deployment-scope lock admits at most one live fleet policy. Aggregate
 * thresholds count unique expected serving slots, never raw rows, so duplicate process starts
 * cannot manufacture a quorum. Whole-record fingerprints, an external inventory revision floor,
 * bounded leases and bounded cleanup make rollback, fork, collision and direct database drift
 * fail closed.</p>
 */
public final class DatabaseControlPlaneCertificateRotationConvergenceRepository
        implements ControlPlaneCertificateRotationConvergenceRepository {

    private static final int MAXIMUM_LIVE_ROWS =
            ControlPlaneCertificateRotationFleetPolicy.maximumReplicas() * 2;
    private static final int PURGE_BATCH = 256;
    private static final String ACTIVE_FLEET_SCHEMA =
            "bloge.controlPlaneCertificateRotationActiveFleet.v1";
    private static final String INVENTORY_FLOOR_SCHEMA =
            "bloge.controlPlaneCertificateRotationInventoryFloor.v1";
    private static final String ACKNOWLEDGEMENT_RECORD_SCHEMA =
            "bloge.controlPlaneCertificateRotationAcknowledgementRecord.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ControlPlaneCertificateRotationFleetPolicy policy;
    private final String policyFingerprint;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates one isolated convergence authority.
     *
     * @param jdbc testing-control-plane JDBC facade
     * @param objectMapper canonical record fingerprint mapper
     * @param policy exact deployment-owned fleet policy
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseControlPlaneCertificateRotationConvergenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ControlPlaneCertificateRotationFleetPolicy policy,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.policyFingerprint = policy.sharedPolicyFingerprint(objectMapper);
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        mutations = new TransactionTemplate(manager);
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    /** Creates the additive, bounded fleet, inventory-floor and acknowledgement schema. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_rotation_fleet_scope_locks (
                    deployment_scope_id VARCHAR(255) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_rotation_active_fleets (
                    deployment_scope_id VARCHAR(255) PRIMARY KEY,
                    fleet_id VARCHAR(255) NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_rotation_inventory_floors (
                    deployment_scope_id VARCHAR(255) PRIMARY KEY,
                    revision BIGINT NOT NULL,
                    material_fingerprint VARCHAR(71) NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_rotation_ack_locks (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    fleet_id VARCHAR(255) NOT NULL,
                    target_id VARCHAR(255) NOT NULL,
                    instance_id VARCHAR(255) NOT NULL,
                    startup_id VARCHAR(36) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, fleet_id, target_id,
                                 instance_id, startup_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_rotation_acknowledgements (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    fleet_id VARCHAR(255) NOT NULL,
                    target_id VARCHAR(255) NOT NULL,
                    instance_id VARCHAR(255) NOT NULL,
                    startup_id VARCHAR(36) NOT NULL,
                    schema_version VARCHAR(96) NOT NULL,
                    artifact_fingerprint VARCHAR(71) NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    protocol_version VARCHAR(255) NOT NULL,
                    sequence BIGINT NOT NULL,
                    generation BIGINT NOT NULL,
                    event_id VARCHAR(255) NOT NULL,
                    event_fingerprint VARCHAR(71) NOT NULL,
                    settings_fingerprint VARCHAR(71) NOT NULL,
                    activate_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    replica_state VARCHAR(16) NOT NULL,
                    failure_code VARCHAR(64) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    purge_after TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, fleet_id, target_id,
                                 instance_id, startup_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_cp_cert_rotation_ack_expiry_idx
                ON rg_cp_cert_rotation_acknowledgements (
                    purge_after, deployment_scope_id, fleet_id, target_id,
                    instance_id, startup_id)
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot acknowledge(Acknowledgement acknowledgement) {
        requireLocalAcknowledgement(acknowledgement);
        Snapshot result = mutations.execute(status -> {
            lockAcknowledgement(acknowledgement.expectedRotation().targetId());
            Instant now = databaseNow();
            Instant leaseExpiresAt = now.plus(policy.leaseDuration());
            Instant purgeAfter = leaseExpiresAt.plus(policy.recordRetention());
            boolean ownsActiveFleet = claimOrRenewActiveFleet(now, leaseExpiresAt);
            if (ownsActiveFleet) {
                enforceInventoryFloor(now);
            }
            AcknowledgementRow current = currentRow(
                    acknowledgement.expectedRotation().targetId(),
                    policy.instanceId(), policy.startupId());
            validateSuccessor(current, acknowledgement, now);
            persist(acknowledgement, now, leaseExpiresAt, purgeAfter);
            purgeExpired(now);
            return snapshotAt(acknowledgement.expectedRotation(), now);
        });
        return Objects.requireNonNull(result, "certificate rotation acknowledgement result");
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot(ExpectedRotation expectedRotation) {
        ExpectedRotation expected = Objects.requireNonNull(
                expectedRotation, "expectedRotation");
        Snapshot result = reads.execute(status -> snapshotAt(expected, databaseNow()));
        return Objects.requireNonNull(result, "certificate rotation convergence snapshot");
    }

    /** {@inheritDoc} */
    @Override
    public void withdraw(String instanceId, String startupId) {
        if (!policy.instanceId().equals(normalized(instanceId))
                || !policy.startupId().equals(normalized(startupId))) {
            throw invalid("Certificate rotation withdrawal must match the local process");
        }
        mutations.executeWithoutResult(status -> {
            List<String> targets = jdbc.queryForList("""
                    SELECT target_id FROM rg_cp_cert_rotation_ack_locks
                    WHERE deployment_scope_id = ? AND fleet_id = ?
                      AND instance_id = ? AND startup_id = ?
                    ORDER BY target_id
                    """, String.class, policy.deploymentScopeId(), policy.fleetId(),
                    policy.instanceId(), policy.startupId());
            targets.forEach(this::lockAcknowledgement);
            jdbc.update("""
                    DELETE FROM rg_cp_cert_rotation_acknowledgements
                    WHERE deployment_scope_id = ? AND fleet_id = ?
                      AND instance_id = ? AND startup_id = ?
                    """, policy.deploymentScopeId(), policy.fleetId(),
                    policy.instanceId(), policy.startupId());
            jdbc.update("""
                    DELETE FROM rg_cp_cert_rotation_ack_locks
                    WHERE deployment_scope_id = ? AND fleet_id = ?
                      AND instance_id = ? AND startup_id = ?
                    """, policy.deploymentScopeId(), policy.fleetId(),
                    policy.instanceId(), policy.startupId());
        });
    }

    private Snapshot snapshotAt(ExpectedRotation expected, Instant now) {
        LinkedHashSet<String> activationBlockers = new LinkedHashSet<>();
        validateActiveFleet(now, activationBlockers);
        validateInventoryFloor(now, activationBlockers);

        List<AcknowledgementRow> selected = jdbc.query("""
                SELECT deployment_scope_id, fleet_id, target_id, instance_id, startup_id,
                       schema_version, artifact_fingerprint, policy_fingerprint,
                       protocol_version, sequence, generation, event_id,
                       event_fingerprint, settings_fingerprint, activate_at,
                       replica_state, failure_code, observed_at, lease_expires_at,
                       purge_after, record_fingerprint
                FROM rg_cp_cert_rotation_acknowledgements
                WHERE deployment_scope_id = ? AND fleet_id = ? AND target_id = ?
                ORDER BY instance_id, startup_id
                LIMIT ?
                """, this::row, policy.deploymentScopeId(), policy.fleetId(),
                expected.targetId(), MAXIMUM_LIVE_ROWS + 1);
        if (selected.size() > MAXIMUM_LIVE_ROWS) {
            activationBlockers.add("INVENTORY_OVERFLOW");
            selected = selected.subList(0, MAXIMUM_LIVE_ROWS);
        }

        List<AcknowledgementRow> live = new ArrayList<>();
        int corrupt = 0;
        for (AcknowledgementRow candidate : selected) {
            if (!candidate.timeShapeValid()
                    || !candidate.leaseExpiresAt().isAfter(now)) {
                if (!candidate.timeShapeValid()) {
                    corrupt++;
                }
                continue;
            }
            if (!candidate.valid(objectMapper, policy.leaseDuration(),
                    policy.recordRetention())) {
                corrupt++;
                continue;
            }
            live.add(candidate);
        }
        if (corrupt > 0) {
            activationBlockers.add("INVENTORY_CORRUPT");
        }

        Map<String, List<AcknowledgementRow>> byInstance = new HashMap<>();
        live.forEach(candidate -> byInstance.computeIfAbsent(
                candidate.acknowledgement().instanceId(), ignored -> new ArrayList<>())
                .add(candidate));
        Set<String> expectedInstances = policy.expectedInstanceIds();
        int missing = (int) expectedInstances.stream()
                .filter(id -> !byInstance.containsKey(id)).count();
        int unexpected = (int) live.stream().filter(candidate ->
                !expectedInstances.contains(candidate.acknowledgement().instanceId())).count();
        int duplicates = (int) expectedInstances.stream().filter(
                id -> byInstance.getOrDefault(id, List.of()).size() > 1).count();
        boolean localPresent = live.stream().anyMatch(candidate ->
                policy.instanceId().equals(candidate.acknowledgement().instanceId())
                        && policy.startupId().equals(candidate.acknowledgement().startupId()));
        int divergentArtifact = (int) live.stream().filter(candidate ->
                !policy.artifactFingerprint().equals(
                        candidate.acknowledgement().artifactFingerprint())).count();
        int divergentPolicy = (int) live.stream().filter(candidate ->
                !policyFingerprint.equals(
                        candidate.acknowledgement().policyFingerprint())).count();
        int divergentProtocol = (int) live.stream().filter(candidate ->
                !policy.protocolVersion().equals(
                        candidate.acknowledgement().protocolVersion())).count();
        int divergentRotation = (int) live.stream().filter(candidate ->
                !expected.equals(candidate.acknowledgement().expectedRotation())).count();

        List<Acknowledgement> exactUnique = expectedInstances.stream()
                .map(id -> byInstance.getOrDefault(id, List.of()))
                .filter(rows -> rows.size() == 1)
                .map(List::getFirst)
                .map(AcknowledgementRow::acknowledgement)
                .filter(acknowledgement -> policy.artifactFingerprint().equals(
                        acknowledgement.artifactFingerprint()))
                .filter(acknowledgement -> policyFingerprint.equals(
                        acknowledgement.policyFingerprint()))
                .filter(acknowledgement -> policy.protocolVersion().equals(
                        acknowledgement.protocolVersion()))
                .filter(acknowledgement -> expected.equals(
                        acknowledgement.expectedRotation()))
                .toList();
        int staged = count(exactUnique, ReplicaState.STAGED);
        int active = count(exactUnique, ReplicaState.ACTIVE);
        int failed = count(exactUnique, ReplicaState.FAILED);

        if (unexpected > 0) {
            activationBlockers.add("UNEXPECTED_REPLICA");
        }
        if (duplicates > 0) {
            activationBlockers.add("DUPLICATE_REPLICA");
        }
        if (!localPresent) {
            activationBlockers.add("LOCAL_PROCESS_NOT_REGISTERED");
        }
        if (divergentArtifact > 0) {
            activationBlockers.add("ARTIFACT_DIVERGED");
        }
        if (divergentPolicy > 0) {
            activationBlockers.add("POLICY_DIVERGED");
        }
        if (divergentProtocol > 0) {
            activationBlockers.add("PROTOCOL_DIVERGED");
        }
        if (divergentRotation > 0) {
            activationBlockers.add("ROTATION_DIVERGED");
        }
        if (failed > 0) {
            activationBlockers.add("REPLICA_FAILED");
        }
        if (policy.activationMode()
                == ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS
                && missing > 0) {
            activationBlockers.add("REPLICA_MISSING");
        }
        if (staged + active < policy.requiredStagedReplicas()) {
            activationBlockers.add("STAGING_THRESHOLD_UNMET");
        }

        LinkedHashSet<String> convergenceBlockers =
                new LinkedHashSet<>(activationBlockers);
        if (missing > 0) {
            convergenceBlockers.add("REPLICA_MISSING");
        }
        if (staged > 0) {
            convergenceBlockers.add("REPLICA_STILL_STAGED");
        }
        if (active != expectedInstances.size()) {
            convergenceBlockers.add("REPLICA_NOT_ACTIVE");
        }
        boolean activationPermitted = activationBlockers.isEmpty();
        boolean converged = convergenceBlockers.isEmpty();
        String status = converged ? "CONVERGED"
                : activationPermitted ? "ACTIVATION_PERMITTED"
                : activationBlockers.getFirst();
        Instant nextExpiry = live.stream().map(AcknowledgementRow::leaseExpiresAt)
                .min(Instant::compareTo).orElse(null);
        return new Snapshot(Snapshot.SCHEMA_VERSION, activationPermitted, converged,
                status, expectedInstances.size(), policy.requiredStagedReplicas(),
                live.size(), staged, active, failed, missing, unexpected, duplicates,
                divergentArtifact, divergentPolicy, divergentProtocol, divergentRotation,
                corrupt, now, nextExpiry, List.copyOf(activationBlockers),
                List.copyOf(convergenceBlockers));
    }

    private void requireLocalAcknowledgement(Acknowledgement acknowledgement) {
        if (acknowledgement == null
                || !policy.deploymentScopeId().equals(
                        acknowledgement.deploymentScopeId())
                || !policy.fleetId().equals(acknowledgement.fleetId())
                || !policy.instanceId().equals(acknowledgement.instanceId())
                || !policy.startupId().equals(acknowledgement.startupId())
                || !policy.artifactFingerprint().equals(
                        acknowledgement.artifactFingerprint())
                || !policyFingerprint.equals(acknowledgement.policyFingerprint())
                || !policy.protocolVersion().equals(acknowledgement.protocolVersion())) {
            throw invalid("Certificate rotation acknowledgement does not match local policy");
        }
    }

    private void validateSuccessor(
            AcknowledgementRow current,
            Acknowledgement acknowledgement,
            Instant databaseNow) {
        if (acknowledgement.state() == ReplicaState.ACTIVE
                && databaseNow.isBefore(
                acknowledgement.expectedRotation().activateAt())) {
            throw invalid("Certificate rotation cannot become active before database time");
        }
        if (current == null) {
            if (acknowledgement.sequence() != 1) {
                throw invalid("Certificate rotation acknowledgement sequence has a gap");
            }
            return;
        }
        if (!current.valid(objectMapper, policy.leaseDuration(), policy.recordRetention())) {
            throw new IllegalStateException(
                    "Certificate rotation acknowledgement record is corrupt");
        }
        Acknowledgement previous = current.acknowledgement();
        if (acknowledgement.sequence() == previous.sequence()) {
            if (!acknowledgement.equals(previous)) {
                throw invalid("Certificate rotation acknowledgement sequence was reused");
            }
            return;
        }
        if (acknowledgement.sequence() != previous.sequence() + 1) {
            throw invalid("Certificate rotation acknowledgement sequence is not consecutive");
        }
        ExpectedRotation before = previous.expectedRotation();
        ExpectedRotation after = acknowledgement.expectedRotation();
        if (after.generation() < before.generation()) {
            throw invalid("Certificate rotation acknowledgement rolled back");
        }
        if (after.generation() == before.generation() && !after.equals(before)) {
            throw invalid("Certificate rotation acknowledgement forked");
        }
        if (after.generation() > before.generation() + 1) {
            throw invalid("Certificate rotation acknowledgement generation has a gap");
        }
        if (after.generation() > before.generation()
                && previous.state() != ReplicaState.ACTIVE) {
            throw invalid("Certificate rotation successor requires an active predecessor");
        }
        if (after.generation() > before.generation()
                && acknowledgement.state() == ReplicaState.ACTIVE) {
            throw invalid("Certificate rotation successor must be staged before activation");
        }
        if (after.generation() == before.generation()
                && !transitionAllowed(previous.state(), acknowledgement.state())) {
            throw invalid("Certificate rotation acknowledgement state regressed");
        }
    }

    private static boolean transitionAllowed(ReplicaState before, ReplicaState after) {
        return switch (before) {
            case STAGED -> after == ReplicaState.STAGED
                    || after == ReplicaState.ACTIVE || after == ReplicaState.FAILED;
            case FAILED -> after == ReplicaState.FAILED || after == ReplicaState.STAGED;
            case ACTIVE -> after == ReplicaState.ACTIVE;
        };
    }

    private boolean claimOrRenewActiveFleet(Instant now, Instant leaseExpiresAt) {
        jdbc.update("""
                MERGE INTO rg_cp_cert_rotation_fleet_scope_locks (deployment_scope_id)
                KEY (deployment_scope_id) VALUES (?)
                """, policy.deploymentScopeId());
        jdbc.queryForObject("""
                SELECT deployment_scope_id
                FROM rg_cp_cert_rotation_fleet_scope_locks
                WHERE deployment_scope_id = ? FOR UPDATE
                """, String.class, policy.deploymentScopeId());
        ActiveFleet current = activeFleet();
        if (current != null && !current.valid(objectMapper)) {
            throw new IllegalStateException(
                    "Certificate rotation active fleet record is corrupt");
        }
        if (current != null && current.leaseExpiresAt().isAfter(now)
                && (!policy.fleetId().equals(current.fleetId())
                || !policyFingerprint.equals(current.policyFingerprint()))) {
            return false;
        }
        ActiveFleet next = ActiveFleet.create(policy.deploymentScopeId(), policy.fleetId(),
                policyFingerprint, now, leaseExpiresAt, objectMapper);
        jdbc.update("""
                MERGE INTO rg_cp_cert_rotation_active_fleets (
                    deployment_scope_id, fleet_id, policy_fingerprint,
                    observed_at, lease_expires_at, record_fingerprint
                ) KEY (deployment_scope_id) VALUES (?, ?, ?, ?, ?, ?)
                """, next.deploymentScopeId(), next.fleetId(), next.policyFingerprint(),
                Timestamp.from(next.observedAt()), Timestamp.from(next.leaseExpiresAt()),
                next.recordFingerprint());
        return true;
    }

    private void validateActiveFleet(Instant now, LinkedHashSet<String> blockers) {
        ActiveFleet active = activeFleet();
        if (active == null) {
            blockers.add("FLEET_NOT_ACTIVE");
        } else if (!active.valid(objectMapper)) {
            blockers.add("FLEET_AUTHORITY_CORRUPT");
        } else if (!active.leaseExpiresAt().isAfter(now)) {
            blockers.add("FLEET_NOT_ACTIVE");
        } else if (!policy.fleetId().equals(active.fleetId())
                || !policyFingerprint.equals(active.policyFingerprint())) {
            blockers.add("FLEET_NOT_ACTIVE");
        }
    }

    private void enforceInventoryFloor(Instant observedAt) {
        ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation inventory =
                policy.inventoryAttestation();
        if (!inventory.externallyAttested()) {
            if (inventoryFloor() != null) {
                throw new IllegalStateException(
                        "Certificate rotation inventory authority cannot be downgraded");
            }
            return;
        }
        if (!inventory.expiresAt().isAfter(observedAt)) {
            throw new IllegalStateException(
                    "Certificate rotation inventory attestation is expired");
        }
        InventoryFloor current = inventoryFloor();
        if (current != null && !current.valid(objectMapper)) {
            throw new IllegalStateException(
                    "Certificate rotation inventory floor is corrupt");
        }
        if (current != null && (current.revision() > inventory.revision()
                || current.revision() == inventory.revision()
                && (!current.materialFingerprint().equals(inventory.materialFingerprint())
                || !current.policyFingerprint().equals(inventory.policyFingerprint())))) {
            throw new IllegalStateException(
                    "Certificate rotation inventory revision is rolled back or forked");
        }
        if (current != null && current.revision() == inventory.revision()) {
            return;
        }
        InventoryFloor next = InventoryFloor.create(policy.deploymentScopeId(), inventory,
                observedAt, objectMapper);
        jdbc.update("""
                MERGE INTO rg_cp_cert_rotation_inventory_floors (
                    deployment_scope_id, revision, material_fingerprint,
                    policy_fingerprint, observed_at, record_fingerprint
                ) KEY (deployment_scope_id) VALUES (?, ?, ?, ?, ?, ?)
                """, next.deploymentScopeId(), next.revision(), next.materialFingerprint(),
                next.policyFingerprint(), Timestamp.from(next.observedAt()),
                next.recordFingerprint());
    }

    private void validateInventoryFloor(Instant now, LinkedHashSet<String> blockers) {
        ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation inventory =
                policy.inventoryAttestation();
        if (!inventory.externallyAttested()) {
            if (inventoryFloor() != null) {
                blockers.add("INVENTORY_AUTHORITY_DOWNGRADE");
            }
            return;
        }
        if (!now.isBefore(inventory.expiresAt())) {
            blockers.add("INVENTORY_ATTESTATION_EXPIRED");
        }
        InventoryFloor floor = inventoryFloor();
        if (floor == null) {
            blockers.add("INVENTORY_FLOOR_MISSING");
        } else if (!floor.valid(objectMapper)) {
            blockers.add("INVENTORY_FLOOR_CORRUPT");
        } else if (floor.revision() > inventory.revision()) {
            blockers.add("INVENTORY_ROLLBACK");
        } else if (floor.revision() < inventory.revision()) {
            blockers.add("INVENTORY_FLOOR_STALE");
        } else if (!floor.materialFingerprint().equals(inventory.materialFingerprint())
                || !floor.policyFingerprint().equals(inventory.policyFingerprint())) {
            blockers.add("INVENTORY_FORKED");
        }
    }

    private void lockAcknowledgement(String targetId) {
        jdbc.update("""
                MERGE INTO rg_cp_cert_rotation_ack_locks (
                    deployment_scope_id, fleet_id, target_id, instance_id, startup_id
                ) KEY (deployment_scope_id, fleet_id, target_id, instance_id, startup_id)
                VALUES (?, ?, ?, ?, ?)
                """, policy.deploymentScopeId(), policy.fleetId(), targetId,
                policy.instanceId(), policy.startupId());
        jdbc.queryForObject("""
                SELECT target_id FROM rg_cp_cert_rotation_ack_locks
                WHERE deployment_scope_id = ? AND fleet_id = ? AND target_id = ?
                  AND instance_id = ? AND startup_id = ? FOR UPDATE
                """, String.class, policy.deploymentScopeId(), policy.fleetId(), targetId,
                policy.instanceId(), policy.startupId());
    }

    private void persist(
            Acknowledgement acknowledgement,
            Instant observedAt,
            Instant leaseExpiresAt,
            Instant purgeAfter) {
        String recordFingerprint = recordFingerprint(
                acknowledgement, observedAt, leaseExpiresAt, purgeAfter);
        ExpectedRotation expected = acknowledgement.expectedRotation();
        jdbc.update("""
                MERGE INTO rg_cp_cert_rotation_acknowledgements (
                    deployment_scope_id, fleet_id, target_id, instance_id, startup_id,
                    schema_version, artifact_fingerprint, policy_fingerprint,
                    protocol_version, sequence, generation, event_id,
                    event_fingerprint, settings_fingerprint, activate_at,
                    replica_state, failure_code, observed_at, lease_expires_at,
                    purge_after, record_fingerprint
                ) KEY (deployment_scope_id, fleet_id, target_id, instance_id, startup_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, acknowledgement.deploymentScopeId(), acknowledgement.fleetId(),
                expected.targetId(), acknowledgement.instanceId(), acknowledgement.startupId(),
                acknowledgement.schemaVersion(), acknowledgement.artifactFingerprint(),
                acknowledgement.policyFingerprint(), acknowledgement.protocolVersion(),
                acknowledgement.sequence(), expected.generation(), expected.eventId(),
                expected.eventFingerprint(), expected.settingsFingerprint(),
                Timestamp.from(expected.activateAt()), acknowledgement.state().name(),
                acknowledgement.failureCode(), Timestamp.from(observedAt),
                Timestamp.from(leaseExpiresAt), Timestamp.from(purgeAfter), recordFingerprint);
    }

    private AcknowledgementRow currentRow(
            String targetId, String instanceId, String startupId) {
        List<AcknowledgementRow> rows = jdbc.query("""
                SELECT deployment_scope_id, fleet_id, target_id, instance_id, startup_id,
                       schema_version, artifact_fingerprint, policy_fingerprint,
                       protocol_version, sequence, generation, event_id,
                       event_fingerprint, settings_fingerprint, activate_at,
                       replica_state, failure_code, observed_at, lease_expires_at,
                       purge_after, record_fingerprint
                FROM rg_cp_cert_rotation_acknowledgements
                WHERE deployment_scope_id = ? AND fleet_id = ? AND target_id = ?
                  AND instance_id = ? AND startup_id = ?
                """, this::row, policy.deploymentScopeId(), policy.fleetId(), targetId,
                instanceId, startupId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate certificate rotation acknowledgement record");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ActiveFleet activeFleet() {
        List<ActiveFleet> rows = jdbc.query("""
                SELECT deployment_scope_id, fleet_id, policy_fingerprint,
                       observed_at, lease_expires_at, record_fingerprint
                FROM rg_cp_cert_rotation_active_fleets
                WHERE deployment_scope_id = ?
                """, (result, rowNumber) -> new ActiveFleet(
                result.getString("deployment_scope_id"), result.getString("fleet_id"),
                result.getString("policy_fingerprint"), instant(result, "observed_at"),
                instant(result, "lease_expires_at"),
                result.getString("record_fingerprint")), policy.deploymentScopeId());
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate certificate rotation active fleet");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private InventoryFloor inventoryFloor() {
        List<InventoryFloor> rows = jdbc.query("""
                SELECT deployment_scope_id, revision, material_fingerprint,
                       policy_fingerprint, observed_at, record_fingerprint
                FROM rg_cp_cert_rotation_inventory_floors
                WHERE deployment_scope_id = ?
                """, (result, rowNumber) -> new InventoryFloor(
                result.getString("deployment_scope_id"), result.getLong("revision"),
                result.getString("material_fingerprint"),
                result.getString("policy_fingerprint"), instant(result, "observed_at"),
                result.getString("record_fingerprint")), policy.deploymentScopeId());
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate certificate rotation inventory floor");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void purgeExpired(Instant now) {
        List<PurgeKey> keys = jdbc.query("""
                SELECT deployment_scope_id, fleet_id, target_id, instance_id, startup_id
                FROM rg_cp_cert_rotation_acknowledgements
                WHERE purge_after < ?
                ORDER BY purge_after, deployment_scope_id, fleet_id, target_id,
                         instance_id, startup_id
                LIMIT ?
                """, (result, rowNumber) -> new PurgeKey(
                result.getString("deployment_scope_id"), result.getString("fleet_id"),
                result.getString("target_id"), result.getString("instance_id"),
                result.getString("startup_id")), Timestamp.from(now), PURGE_BATCH);
        keys.forEach(key -> {
            int removed = jdbc.update("""
                    DELETE FROM rg_cp_cert_rotation_acknowledgements
                    WHERE deployment_scope_id = ? AND fleet_id = ? AND target_id = ?
                      AND instance_id = ? AND startup_id = ? AND purge_after < ?
                    """, key.deploymentScopeId(), key.fleetId(), key.targetId(),
                    key.instanceId(), key.startupId(), Timestamp.from(now));
            if (removed > 0) {
                jdbc.update("""
                        DELETE FROM rg_cp_cert_rotation_ack_locks
                        WHERE deployment_scope_id = ? AND fleet_id = ? AND target_id = ?
                          AND instance_id = ? AND startup_id = ?
                        """, key.deploymentScopeId(), key.fleetId(), key.targetId(),
                        key.instanceId(), key.startupId());
            }
        });
    }

    private String recordFingerprint(
            Acknowledgement acknowledgement,
            Instant observedAt,
            Instant leaseExpiresAt,
            Instant purgeAfter) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", ACKNOWLEDGEMENT_RECORD_SCHEMA,
                "acknowledgement", acknowledgement,
                "observedAt", observedAt.toString(),
                "leaseExpiresAt", leaseExpiresAt.toString(),
                "purgeAfter", purgeAfter.toString()));
    }

    private AcknowledgementRow row(ResultSet result, int rowNumber) throws SQLException {
        Instant observedAt = instant(result, "observed_at");
        Instant leaseExpiresAt = instant(result, "lease_expires_at");
        Instant purgeAfter = instant(result, "purge_after");
        String recordFingerprint = result.getString("record_fingerprint");
        try {
            ExpectedRotation expected = new ExpectedRotation(
                    result.getString("target_id"), result.getLong("generation"),
                    result.getString("event_id"), result.getString("event_fingerprint"),
                    result.getString("settings_fingerprint"), instant(result, "activate_at"));
            Acknowledgement acknowledgement = new Acknowledgement(
                    result.getString("schema_version"),
                    result.getString("deployment_scope_id"), result.getString("fleet_id"),
                    result.getString("instance_id"), result.getString("startup_id"),
                    result.getString("artifact_fingerprint"),
                    result.getString("policy_fingerprint"),
                    result.getString("protocol_version"), result.getLong("sequence"),
                    expected, ReplicaState.valueOf(result.getString("replica_state")),
                    result.getString("failure_code"));
            return new AcknowledgementRow(acknowledgement, observedAt,
                    leaseExpiresAt, purgeAfter, recordFingerprint);
        } catch (RuntimeException corrupt) {
            return new AcknowledgementRow(null, observedAt,
                    leaseExpiresAt, purgeAfter, recordFingerprint);
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", (result, rowNumber) -> instant(result, 1)),
                "database time");
    }

    private static int count(List<Acknowledgement> values, ReplicaState state) {
        return (int) values.stream().filter(value -> value.state() == state).count();
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet result, int column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record AcknowledgementRow(
            Acknowledgement acknowledgement,
            Instant observedAt,
            Instant leaseExpiresAt,
            Instant purgeAfter,
            String recordFingerprint) {

        private boolean timeShapeValid() {
            return observedAt != null && leaseExpiresAt != null && purgeAfter != null
                    && leaseExpiresAt.isAfter(observedAt)
                    && !purgeAfter.isBefore(leaseExpiresAt);
        }

        private boolean valid(
                ObjectMapper objectMapper, java.time.Duration lease,
                java.time.Duration retention) {
            return acknowledgement != null && timeShapeValid()
                    && leaseExpiresAt.equals(observedAt.plus(lease))
                    && purgeAfter.equals(leaseExpiresAt.plus(retention))
                    && recordFingerprint.equals(ProtocolFingerprint.of(objectMapper, Map.of(
                    "schemaVersion", ACKNOWLEDGEMENT_RECORD_SCHEMA,
                    "acknowledgement", acknowledgement,
                    "observedAt", observedAt.toString(),
                    "leaseExpiresAt", leaseExpiresAt.toString(),
                    "purgeAfter", purgeAfter.toString())));
        }
    }

    private record ActiveFleet(
            String deploymentScopeId,
            String fleetId,
            String policyFingerprint,
            Instant observedAt,
            Instant leaseExpiresAt,
            String recordFingerprint) {

        private static ActiveFleet create(
                String deploymentScopeId, String fleetId, String policyFingerprint,
                Instant observedAt, Instant leaseExpiresAt, ObjectMapper objectMapper) {
            String fingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                    "schemaVersion", ACTIVE_FLEET_SCHEMA,
                    "deploymentScopeId", deploymentScopeId,
                    "fleetId", fleetId,
                    "policyFingerprint", policyFingerprint,
                    "observedAt", observedAt.toString(),
                    "leaseExpiresAt", leaseExpiresAt.toString()));
            return new ActiveFleet(deploymentScopeId, fleetId, policyFingerprint,
                    observedAt, leaseExpiresAt, fingerprint);
        }

        private boolean valid(ObjectMapper objectMapper) {
            return observedAt != null && leaseExpiresAt != null
                    && leaseExpiresAt.isAfter(observedAt)
                    && equals(create(deploymentScopeId, fleetId, policyFingerprint,
                    observedAt, leaseExpiresAt, objectMapper));
        }
    }

    private record InventoryFloor(
            String deploymentScopeId,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            Instant observedAt,
            String recordFingerprint) {

        private static InventoryFloor create(
                String deploymentScopeId,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation inventory,
                Instant observedAt,
                ObjectMapper objectMapper) {
            String fingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                    "schemaVersion", INVENTORY_FLOOR_SCHEMA,
                    "deploymentScopeId", deploymentScopeId,
                    "revision", inventory.revision(),
                    "materialFingerprint", inventory.materialFingerprint(),
                    "policyFingerprint", inventory.policyFingerprint(),
                    "observedAt", observedAt.toString()));
            return new InventoryFloor(deploymentScopeId, inventory.revision(),
                    inventory.materialFingerprint(), inventory.policyFingerprint(),
                    observedAt, fingerprint);
        }

        private boolean valid(ObjectMapper objectMapper) {
            if (observedAt == null) {
                return false;
            }
            try {
                var inventory =
                        new ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation(
                                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                                        .SCHEMA_VERSION,
                                true, "PERSISTED_EXTERNAL", revision, materialFingerprint,
                                policyFingerprint, Instant.MAX);
                return equals(create(deploymentScopeId, inventory, observedAt, objectMapper));
            } catch (RuntimeException corrupt) {
                return false;
            }
        }
    }

    private record PurgeKey(
            String deploymentScopeId,
            String fleetId,
            String targetId,
            String instanceId,
            String startupId) {
    }
}
