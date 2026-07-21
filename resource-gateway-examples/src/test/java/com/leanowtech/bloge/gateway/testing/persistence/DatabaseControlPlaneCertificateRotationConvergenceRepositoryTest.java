package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationConvergenceRepository;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationFleetPolicy;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationTargets;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseControlPlaneCertificateRotationConvergenceRepositoryTest {

    private static final String SCOPE = "resource-gateway-prod";
    private static final String FLEET = "deployment-2026-07-21";
    private static final String TARGET = ControlPlaneCertificateRotationTargets
            .RECOVERY_FLEET_NOTARY;
    private static final String PROTOCOL = "5.0";
    private static final String ARTIFACT = fingerprint('a');

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:certificate-rotation-convergence-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void allReplicaPolicySeparatesActivationFromFinalConvergence() {
        Set<String> expected = Set.of("replica-a", "replica-b");
        var policyA = policy("replica-a", UUID.randomUUID().toString(), expected);
        var policyB = policy("replica-b", UUID.randomUUID().toString(), expected);
        var repositoryA = repository(policyA);
        var repositoryB = repository(policyB);
        var rotation = rotation(2, "rotation-002", 'b');

        assertThat(repositoryA.acknowledge(acknowledgement(
                policyA, 1, rotation, state("STAGED"))))
                .satisfies(snapshot -> {
                    assertThat(snapshot.activationPermitted()).isFalse();
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("REPLICA_MISSING");
                    assertThat(snapshot.missingReplicaCount()).isOne();
                });
        assertThat(repositoryB.acknowledge(acknowledgement(
                policyB, 1, rotation, state("STAGED"))))
                .satisfies(snapshot -> {
                    assertThat(snapshot.activationPermitted()).isTrue();
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("ACTIVATION_PERMITTED");
                    assertThat(snapshot.stagedReplicaCount()).isEqualTo(2);
                    assertThat(snapshot.convergenceBlockers())
                            .containsExactly("REPLICA_STILL_STAGED", "REPLICA_NOT_ACTIVE");
                });

        repositoryA.acknowledge(acknowledgement(policyA, 2, rotation, state("ACTIVE")));
        assertThat(repositoryB.acknowledge(acknowledgement(
                policyB, 2, rotation, state("ACTIVE"))))
                .satisfies(snapshot -> {
                    assertThat(snapshot.activationPermitted()).isTrue();
                    assertThat(snapshot.converged()).isTrue();
                    assertThat(snapshot.status()).isEqualTo("CONVERGED");
                    assertThat(snapshot.activeReplicaCount()).isEqualTo(2);
                    assertThat(snapshot.activationBlockers()).isEmpty();
                    assertThat(snapshot.convergenceBlockers()).isEmpty();
                });
    }

    @Test
    void duplicateProcessStartsNeverManufactureAUniqueSlotQuorum() {
        Set<String> expected = Set.of("replica-a", "replica-b");
        var firstA = policy("replica-a", UUID.randomUUID().toString(), expected);
        var secondA = policy("replica-a", UUID.randomUUID().toString(), expected);
        var rotation = rotation(2, "rotation-002", 'b');

        repository(firstA).acknowledge(acknowledgement(
                firstA, 1, rotation, state("STAGED")));
        assertThat(repository(secondA).acknowledge(acknowledgement(
                secondA, 1, rotation, state("STAGED"))))
                .satisfies(snapshot -> {
                    assertThat(snapshot.liveReplicaCount()).isEqualTo(2);
                    assertThat(snapshot.stagedReplicaCount()).isZero();
                    assertThat(snapshot.duplicateReplicaCount()).isOne();
                    assertThat(snapshot.missingReplicaCount()).isOne();
                    assertThat(snapshot.activationBlockers()).contains(
                            "DUPLICATE_REPLICA", "REPLICA_MISSING",
                            "STAGING_THRESHOLD_UNMET");
                    assertThat(snapshot.activationPermitted()).isFalse();
                });
    }

    @Test
    void fencedQuorumCanPermitActivationButCannotClaimConvergence() {
        Set<String> expected = Set.of("replica-a", "replica-b", "replica-c");
        var policyA = policy(FLEET, "replica-a", UUID.randomUUID().toString(), expected,
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.FENCED_QUORUM,
                2, ARTIFACT, PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured());
        var policyB = policy(FLEET, "replica-b", UUID.randomUUID().toString(), expected,
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.FENCED_QUORUM,
                2, ARTIFACT, PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured());
        var rotation = rotation(2, "rotation-002", 'b');

        repository(policyA).acknowledge(acknowledgement(
                policyA, 1, rotation, state("STAGED")));
        assertThat(repository(policyB).acknowledge(acknowledgement(
                policyB, 1, rotation, state("STAGED"))))
                .satisfies(snapshot -> {
                    assertThat(snapshot.activationPermitted()).isTrue();
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("ACTIVATION_PERMITTED");
                    assertThat(snapshot.missingReplicaCount()).isOne();
                    assertThat(snapshot.activationBlockers()).isEmpty();
                    assertThat(snapshot.convergenceBlockers())
                            .contains("REPLICA_MISSING", "REPLICA_STILL_STAGED",
                                    "REPLICA_NOT_ACTIVE");
                });
    }

    @Test
    void perProcessSequenceRejectsReuseGapRollbackForkAndStateRegression() {
        var policy = policy("replica-a", UUID.randomUUID().toString(),
                Set.of("replica-a"));
        var repository = repository(policy);
        var generationTwo = rotation(2, "rotation-002", 'b');
        var staged = acknowledgement(policy, 1, generationTwo, state("STAGED"));

        assertThat(repository.acknowledge(staged).activationPermitted()).isTrue();
        assertThat(repository.acknowledge(staged).activationPermitted()).isTrue();
        assertThatThrownBy(() -> repository.acknowledge(acknowledgement(
                policy, 1, generationTwo, state("FAILED"))))
                .hasMessageContaining("sequence was reused");
        assertThatThrownBy(() -> repository.acknowledge(acknowledgement(
                policy, 3, generationTwo, state("ACTIVE"))))
                .hasMessageContaining("not consecutive");
        assertThatThrownBy(() -> repository.acknowledge(acknowledgement(
                policy, 2, rotation(2, "rotation-fork", 'c'), state("ACTIVE"))))
                .hasMessageContaining("forked");

        assertThat(repository.acknowledge(acknowledgement(
                policy, 2, generationTwo, state("ACTIVE"))).converged()).isTrue();
        assertThatThrownBy(() -> repository.acknowledge(acknowledgement(
                policy, 3, generationTwo, state("STAGED"))))
                .hasMessageContaining("state regressed");
        assertThatThrownBy(() -> repository.acknowledge(acknowledgement(
                policy, 3, rotation(1, "rotation-001", 'a'), state("STAGED"))))
                .hasMessageContaining("rolled back");
        assertThatThrownBy(() -> repository.acknowledge(acknowledgement(
                policy, 3, rotation(4, "rotation-004", 'd'), state("STAGED"))))
                .hasMessageContaining("generation has a gap");
        var early = new ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation(
                TARGET, 3, "rotation-003", fingerprint('c'), fingerprint('c'),
                Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> repository.acknowledge(acknowledgement(
                policy, 3, early, state("ACTIVE"))))
                .hasMessageContaining("database time");
        assertThat(repository.acknowledge(acknowledgement(
                policy, 3, rotation(3, "rotation-003", 'c'), state("STAGED")))
                .activationPermitted()).isTrue();
    }

    @Test
    void divergentRotationArtifactPolicyProtocolAndUnexpectedSlotsFailClosed() {
        Set<String> expected = Set.of("replica-a", "replica-b");
        var policyA = policy("replica-a", UUID.randomUUID().toString(), expected);
        var divergentB = policy(FLEET, "replica-b", UUID.randomUUID().toString(), expected,
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                2, fingerprint('9'), "5.1",
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured());
        var unexpectedC = policy(FLEET, "replica-c", UUID.randomUUID().toString(),
                Set.of("replica-a", "replica-b", "replica-c"),
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                3, ARTIFACT, PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured());
        var rotation = rotation(2, "rotation-002", 'b');
        var repositoryA = repository(policyA);

        repositoryA.acknowledge(acknowledgement(policyA, 1, rotation, state("STAGED")));
        repository(divergentB).acknowledge(acknowledgement(
                divergentB, 1, rotation(2, "rotation-other", 'c'), state("STAGED")));
        repository(unexpectedC).acknowledge(acknowledgement(
                unexpectedC, 1, rotation, state("STAGED")));

        assertThat(repositoryA.snapshot(rotation)).satisfies(snapshot -> {
            assertThat(snapshot.activationPermitted()).isFalse();
            assertThat(snapshot.unexpectedReplicaCount()).isOne();
            assertThat(snapshot.divergentArtifactCount()).isOne();
            assertThat(snapshot.divergentPolicyCount()).isEqualTo(2);
            assertThat(snapshot.divergentProtocolCount()).isOne();
            assertThat(snapshot.divergentRotationCount()).isOne();
            assertThat(snapshot.activationBlockers()).contains(
                    "UNEXPECTED_REPLICA", "ARTIFACT_DIVERGED", "POLICY_DIVERGED",
                    "PROTOCOL_DIVERGED", "ROTATION_DIVERGED",
                    "STAGING_THRESHOLD_UNMET");
        });
    }

    @Test
    void databaseExpiryAndWholeRecordCorruptionAreExcludedAndReported() {
        Set<String> expected = Set.of("replica-a", "replica-b");
        var policyA = policy("replica-a", UUID.randomUUID().toString(), expected);
        var policyB = policy("replica-b", UUID.randomUUID().toString(), expected);
        var repositoryA = repository(policyA);
        var repositoryB = repository(policyB);
        var rotation = rotation(2, "rotation-002", 'b');
        var acknowledgementA = acknowledgement(policyA, 1, rotation, state("STAGED"));
        var acknowledgementB = acknowledgement(policyB, 1, rotation, state("STAGED"));
        repositoryA.acknowledge(acknowledgementA);
        repositoryB.acknowledge(acknowledgementB);

        expireAcknowledgement(acknowledgementB);
        assertThat(repositoryA.snapshot(rotation)).satisfies(snapshot -> {
            assertThat(snapshot.liveReplicaCount()).isOne();
            assertThat(snapshot.missingReplicaCount()).isOne();
            assertThat(snapshot.corruptReplicaCount()).isZero();
        });

        repositoryB.acknowledge(acknowledgementB);
        database.jdbc().update("""
                UPDATE rg_cp_cert_rotation_acknowledgements
                SET replica_state = ?
                WHERE fleet_id = ? AND instance_id = ?
                """, "UNKNOWN", FLEET, "replica-b");
        assertThat(repositoryA.snapshot(rotation)).satisfies(snapshot -> {
            assertThat(snapshot.liveReplicaCount()).isOne();
            assertThat(snapshot.corruptReplicaCount()).isOne();
            assertThat(snapshot.activationBlockers())
                    .contains("INVENTORY_CORRUPT", "REPLICA_MISSING");
        });
        database.jdbc().update("""
                UPDATE rg_cp_cert_rotation_acknowledgements
                SET replica_state = ?
                WHERE fleet_id = ? AND instance_id = ?
                """, "STAGED", FLEET, "replica-b");
        database.jdbc().update("""
                UPDATE rg_cp_cert_rotation_acknowledgements
                SET record_fingerprint = ?
                WHERE fleet_id = ? AND instance_id = ?
                """, fingerprint('8'), FLEET, "replica-b");
        assertThat(repositoryA.snapshot(rotation)).satisfies(snapshot -> {
            assertThat(snapshot.liveReplicaCount()).isOne();
            assertThat(snapshot.corruptReplicaCount()).isOne();
            assertThat(snapshot.activationBlockers())
                    .contains("INVENTORY_CORRUPT", "REPLICA_MISSING");
        });
    }

    @Test
    void oneScopeAdmitsOnlyOneLiveFleetAndConcurrentFirstClaimsLinearize() throws Exception {
        var oldPolicy = policy("fleet-old", "replica-a", UUID.randomUUID().toString(),
                Set.of("replica-a"),
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                1, ARTIFACT, PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured());
        var newPolicy = policy("fleet-new", "replica-b", UUID.randomUUID().toString(),
                Set.of("replica-b"),
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                1, ARTIFACT, PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured());
        var oldRepository = repository(oldPolicy);
        var newRepository = repository(newPolicy);
        var rotation = rotation(2, "rotation-002", 'b');
        CountDownLatch start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<String> oldResult = workers.submit(() -> acknowledgeAfter(
                    start, oldRepository,
                    acknowledgement(oldPolicy, 1, rotation, state("ACTIVE"))));
            Future<String> newResult = workers.submit(() -> acknowledgeAfter(
                    start, newRepository,
                    acknowledgement(newPolicy, 1, rotation, state("ACTIVE"))));
            start.countDown();
            assertThat(List.of(oldResult.get(), newResult.get()))
                    .containsExactlyInAnyOrder("CONVERGED", "FLEET_NOT_ACTIVE");
        }

        String activeFleet = database.jdbc().queryForObject("""
                SELECT fleet_id FROM rg_cp_cert_rotation_active_fleets
                WHERE deployment_scope_id = ?
                """, String.class, SCOPE);
        assertThat(activeFleet).isIn("fleet-old", "fleet-new");
    }

    @Test
    void signedInventoryFloorAdvancesButRejectsRollbackForkExpiryAndCorruption() {
        var revisionOne = attestedPolicy("fleet-one", 1, 'd',
                Instant.now().plusSeconds(300));
        var revisionTwo = attestedPolicy("fleet-two", 2, 'e',
                Instant.now().plusSeconds(300));
        var rotation = rotation(2, "rotation-002", 'b');
        var repositoryOne = repository(revisionOne);
        var repositoryTwo = repository(revisionTwo);

        assertThat(repositoryOne.acknowledge(acknowledgement(
                revisionOne, 1, rotation, state("ACTIVE"))).converged()).isTrue();
        expireActiveFleet(revisionOne);
        assertThat(repositoryTwo.acknowledge(acknowledgement(
                revisionTwo, 1, rotation, state("ACTIVE"))).converged()).isTrue();
        expireActiveFleet(revisionTwo);

        assertThatThrownBy(() -> repositoryOne.acknowledge(acknowledgement(
                revisionOne, 1, rotation, state("ACTIVE"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rolled back");

        var forkedRevisionTwo = attestedPolicy("fleet-fork", 2, 'f',
                Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> repository(forkedRevisionTwo).acknowledge(
                acknowledgement(forkedRevisionTwo, 1, rotation, state("ACTIVE"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forked");

        var localDowngrade = policy("fleet-local", "replica-a",
                UUID.randomUUID().toString(), Set.of("replica-a"),
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                1, ARTIFACT, PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured());
        assertThatThrownBy(() -> repository(localDowngrade).acknowledge(
                acknowledgement(localDowngrade, 1, rotation, state("ACTIVE"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be downgraded");

        database.jdbc().update("""
                UPDATE rg_cp_cert_rotation_inventory_floors
                SET record_fingerprint = ? WHERE deployment_scope_id = ?
                """, fingerprint('9'), SCOPE);
        assertThatThrownBy(() -> repositoryTwo.acknowledge(acknowledgement(
                revisionTwo, 1, rotation, state("ACTIVE"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");

        var expired = attestedPolicy("expired-fleet", 3, '7',
                Instant.now().minusSeconds(1));
        expireActiveFleet(revisionTwo);
        assertThatThrownBy(() -> repository(expired).acknowledge(acknowledgement(
                expired, 1, rotation, state("ACTIVE"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void withdrawalIsExactAndPolicyValidationRejectsUnsafeShapes() {
        var policy = policy("replica-a", UUID.randomUUID().toString(),
                Set.of("replica-a"));
        var repository = repository(policy);
        var rotation = rotation(2, "rotation-002", 'b');
        repository.acknowledge(acknowledgement(policy, 1, rotation, state("ACTIVE")));

        assertThatThrownBy(() -> repository.withdraw("replica-a", UUID.randomUUID().toString()))
                .hasMessageContaining("local process");
        repository.withdraw(policy.instanceId(), policy.startupId());
        assertThat(repository.snapshot(rotation).activationBlockers())
                .contains("LOCAL_PROCESS_NOT_REGISTERED", "REPLICA_MISSING");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_cp_cert_rotation_ack_locks
                WHERE deployment_scope_id = ? AND fleet_id = ?
                """, Integer.class, SCOPE, FLEET)).isZero();

        assertThatThrownBy(() -> policy(FLEET, "replica-a",
                UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"),
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.FENCED_QUORUM,
                1, ARTIFACT, PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlPlaneCertificateRotationFleetPolicy(
                SCOPE, FLEET, "replica-a", "not-a-uuid", ARTIFACT,
                Set.of("replica-a"), PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                1, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofHours(1),
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DatabaseControlPlaneCertificateRotationConvergenceRepository repository(
            ControlPlaneCertificateRotationFleetPolicy policy) {
        var repository = new DatabaseControlPlaneCertificateRotationConvergenceRepository(
                database.jdbc(), objectMapper, policy, database.transactionManager());
        repository.init();
        return repository;
    }

    private ControlPlaneCertificateRotationFleetPolicy policy(
            String instanceId, String startupId, Set<String> expected) {
        return policy(FLEET, instanceId, startupId, expected,
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                expected.size(), ARTIFACT, PROTOCOL,
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation
                        .localConfigured());
    }

    private ControlPlaneCertificateRotationFleetPolicy policy(
            String fleetId,
            String instanceId,
            String startupId,
            Set<String> expected,
            ControlPlaneCertificateRotationFleetPolicy.ActivationMode mode,
            int required,
            String artifact,
            String protocol,
            ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation inventory) {
        return new ControlPlaneCertificateRotationFleetPolicy(
                SCOPE, fleetId, instanceId, startupId, artifact, expected, protocol,
                mode, required, Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofHours(1), inventory);
    }

    private ControlPlaneCertificateRotationFleetPolicy attestedPolicy(
            String fleetId, long revision, char fingerprintCharacter, Instant expiresAt) {
        var inventory = new ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation(
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation.SCHEMA_VERSION,
                true, "STATIC_SIGNED_ED25519_M_OF_N", revision,
                fingerprint(fingerprintCharacter), fingerprint('6'), expiresAt);
        return policy(fleetId, "replica-a", UUID.randomUUID().toString(),
                Set.of("replica-a"),
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                1, ARTIFACT, PROTOCOL, inventory);
    }

    private ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement acknowledgement(
            ControlPlaneCertificateRotationFleetPolicy policy,
            long sequence,
            ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation rotation,
            ControlPlaneCertificateRotationConvergenceRepository.ReplicaState state) {
        return new ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement(
                ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement
                        .SCHEMA_VERSION,
                policy.deploymentScopeId(), policy.fleetId(), policy.instanceId(),
                policy.startupId(), policy.artifactFingerprint(),
                policy.sharedPolicyFingerprint(objectMapper), policy.protocolVersion(),
                sequence, rotation, state, state
                == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.FAILED
                ? "TLS_LOAD_FAILED" : "");
    }

    private static ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation rotation(
            long generation, String eventId, char fingerprintCharacter) {
        return new ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation(
                TARGET, generation, eventId, fingerprint(fingerprintCharacter),
                fingerprint(fingerprintCharacter),
                Instant.now().minusSeconds(60));
    }

    private static ControlPlaneCertificateRotationConvergenceRepository.ReplicaState state(
            String value) {
        return ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.valueOf(value);
    }

    private static String acknowledgeAfter(
            CountDownLatch start,
            DatabaseControlPlaneCertificateRotationConvergenceRepository repository,
            ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement acknowledgement)
            throws Exception {
        start.await();
        return repository.acknowledge(acknowledgement).status();
    }

    private void expireActiveFleet(ControlPlaneCertificateRotationFleetPolicy policy) {
        Instant observedAt = Instant.parse("2026-07-20T00:00:00Z");
        Instant leaseExpiresAt = observedAt.plus(policy.leaseDuration());
        String recordFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.controlPlaneCertificateRotationActiveFleet.v1",
                "deploymentScopeId", policy.deploymentScopeId(),
                "fleetId", policy.fleetId(),
                "policyFingerprint", policy.sharedPolicyFingerprint(objectMapper),
                "observedAt", observedAt.toString(),
                "leaseExpiresAt", leaseExpiresAt.toString()));
        database.jdbc().update("""
                UPDATE rg_cp_cert_rotation_active_fleets
                SET observed_at = ?, lease_expires_at = ?, record_fingerprint = ?
                WHERE deployment_scope_id = ?
                """, Timestamp.from(observedAt), Timestamp.from(leaseExpiresAt),
                recordFingerprint, policy.deploymentScopeId());
    }

    private void expireAcknowledgement(
            ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement acknowledgement) {
        Instant observedAt = Instant.parse("2026-07-20T00:00:00Z");
        Instant leaseExpiresAt = observedAt.plusSeconds(3);
        Instant purgeAfter = leaseExpiresAt.plus(Duration.ofHours(1));
        String recordFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.controlPlaneCertificateRotationAcknowledgementRecord.v1",
                "acknowledgement", acknowledgement,
                "observedAt", observedAt.toString(),
                "leaseExpiresAt", leaseExpiresAt.toString(),
                "purgeAfter", purgeAfter.toString()));
        database.jdbc().update("""
                UPDATE rg_cp_cert_rotation_acknowledgements
                SET observed_at = ?, lease_expires_at = ?, purge_after = ?,
                    record_fingerprint = ?
                WHERE fleet_id = ? AND instance_id = ? AND startup_id = ?
                """, Timestamp.from(observedAt), Timestamp.from(leaseExpiresAt),
                Timestamp.from(purgeAfter), recordFingerprint,
                acknowledgement.fleetId(), acknowledgement.instanceId(),
                acknowledgement.startupId());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(Character.toLowerCase(value)).repeat(64);
    }
}
