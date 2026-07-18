package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityCohortPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityCohortRepository;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityAuthorityCohortRepositoryTest {

    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String GENERATION_A = "sha256:" + "b".repeat(64);
    private static final String GENERATION_B = "sha256:" + "c".repeat(64);
    private static final Instant REFRESHED_AT = Instant.parse("2026-07-19T00:00:00Z");

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:authority-cohort-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void exactTwoReplicaCohortConvergesOnlyAfterBothEquivalentHeartbeats() {
        TestSuiteStabilityAuthorityCohortPolicy policyA = policy(
                "replica-a", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        TestSuiteStabilityAuthorityCohortPolicy policyB = policy(
                "replica-b", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        var repositoryA = repository(policyA);
        var repositoryB = repository(policyB);

        assertThat(repositoryA.heartbeat(member(policyA, GENERATION_A, true)))
                .satisfies(snapshot -> {
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("MEMBER_MISSING");
                    assertThat(snapshot.missingReplicaCount()).isOne();
                });

        assertThat(repositoryB.heartbeat(member(policyB, GENERATION_A, true)))
                .satisfies(snapshot -> {
                    assertThat(snapshot.converged()).isTrue();
                    assertThat(snapshot.status()).isEqualTo("CONVERGED");
                    assertThat(snapshot.expectedReplicaCount()).isEqualTo(2);
                    assertThat(snapshot.liveReplicaCount()).isEqualTo(2);
                    assertThat(snapshot.healthyReplicaCount()).isEqualTo(2);
                    assertThat(snapshot.distinctSnapshotCount()).isOne();
                    assertThat(snapshot.nextLeaseExpiryAt())
                            .isAfter(snapshot.observedAt());
                });
        assertThat(repositoryA.snapshot().converged()).isTrue();
    }

    @Test
    void duplicateProcessAndGenerationDriftFailClosedThenRecover() {
        TestSuiteStabilityAuthorityCohortPolicy policyA = policy(
                "replica-a", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        TestSuiteStabilityAuthorityCohortPolicy policyB = policy(
                "replica-b", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        TestSuiteStabilityAuthorityCohortPolicy replacementB = policy(
                "replica-b", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        var repositoryA = repository(policyA);
        var repositoryB = repository(policyB);
        var replacementRepositoryB = repository(replacementB);
        repositoryA.heartbeat(member(policyA, GENERATION_A, true));
        repositoryB.heartbeat(member(policyB, GENERATION_A, true));

        assertThat(replacementRepositoryB.heartbeat(
                member(replacementB, GENERATION_A, true)).blockers())
                .contains("DUPLICATE_INSTANCE");
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_cohort_members
                SET observed_at = CURRENT_TIMESTAMP - INTERVAL '4' SECOND,
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND
                WHERE cohort_id = ? AND instance_id = ? AND startup_id = ?
                """, policyB.cohortId(), policyB.instanceId(), policyB.startupId());
        assertThat(replacementRepositoryB.snapshot().converged()).isTrue();
        assertThat(repositoryB.snapshot().blockers())
                .contains("LOCAL_PROCESS_NOT_REGISTERED");
        replacementRepositoryB.withdraw(replacementB.instanceId(), replacementB.startupId());
        assertThat(repositoryB.heartbeat(member(policyB, GENERATION_A, true)).converged())
                .isTrue();

        assertThat(repositoryB.heartbeat(member(policyB, GENERATION_B, true)).blockers())
                .contains("SNAPSHOT_DIVERGED");
        assertThat(repositoryB.heartbeat(member(policyB, GENERATION_A, true)).converged())
                .isTrue();
    }

    @Test
    void policyAndUnexpectedInventoryDriftCannotSelfAdmit() {
        TestSuiteStabilityAuthorityCohortPolicy policyA = policy(
                "replica-a", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        TestSuiteStabilityAuthorityCohortPolicy policyB = policy(
                "replica-b", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        TestSuiteStabilityAuthorityCohortPolicy policyC = policy(
                "replica-c", UUID.randomUUID().toString(),
                Set.of("replica-a", "replica-b", "replica-c"));
        var repositoryA = repository(policyA);
        repositoryA.heartbeat(member(policyA, GENERATION_A, true));
        repository(policyB).heartbeat(member(policyB, GENERATION_A, true));
        repository(policyC).heartbeat(member(policyC, GENERATION_A, true));

        assertThat(repositoryA.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.converged()).isFalse();
            assertThat(snapshot.unexpectedReplicaCount()).isOne();
            assertThat(snapshot.divergentPolicyCount()).isOne();
            assertThat(snapshot.blockers())
                    .contains("UNEXPECTED_MEMBER", "POLICY_DIVERGED");
        });
    }

    @Test
    void databaseExpiryAndRecordCorruptionAreFailClosed() {
        TestSuiteStabilityAuthorityCohortPolicy policyA = policy(
                "replica-a", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        TestSuiteStabilityAuthorityCohortPolicy policyB = policy(
                "replica-b", UUID.randomUUID().toString(), Set.of("replica-a", "replica-b"));
        var repositoryA = repository(policyA);
        var repositoryB = repository(policyB);
        repositoryA.heartbeat(member(policyA, GENERATION_A, true));
        repositoryB.heartbeat(member(policyB, GENERATION_A, true));

        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_cohort_members
                SET observed_at = CURRENT_TIMESTAMP - INTERVAL '4' SECOND,
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND
                WHERE cohort_id = ? AND instance_id = ?
                """, policyB.cohortId(), policyB.instanceId());
        assertThat(repositoryA.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo("MEMBER_MISSING");
            assertThat(snapshot.liveReplicaCount()).isOne();
        });

        repositoryB.heartbeat(member(policyB, GENERATION_A, true));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_cohort_members
                SET snapshot_fingerprint = ?
                WHERE cohort_id = ? AND instance_id = ?
                """, GENERATION_B, policyB.cohortId(), policyB.instanceId());
        assertThat(repositoryA.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.converged()).isFalse();
            assertThat(snapshot.status()).isEqualTo("INVENTORY_CORRUPT");
            assertThat(snapshot.blockers()).contains("INVENTORY_CORRUPT", "MEMBER_MISSING");
        });

        repositoryB.heartbeat(member(policyB, GENERATION_A, true));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_cohort_members
                SET purge_after = purge_after + INTERVAL '1' SECOND
                WHERE cohort_id = ? AND instance_id = ?
                """, policyB.cohortId(), policyB.instanceId());
        assertThat(repositoryA.snapshot().blockers())
                .contains("INVENTORY_CORRUPT", "MEMBER_MISSING");
    }

    @Test
    void policyRejectsIncompleteIdentityUnsafeTimingAndLocalInventoryOmission() {
        assertThatThrownBy(() -> new TestSuiteStabilityAuthorityCohortPolicy(
                "scope-a", "cohort-a", "replica-a", "not-a-uuid", ARTIFACT,
                Set.of("replica-a"), "iam.example",
                ToolStudioResourceGatewayProtocol.VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityAuthorityCohortPolicy(
                "scope-a", "cohort-a", "replica-a", UUID.randomUUID().toString(), ARTIFACT,
                Set.of("replica-b"), "iam.example",
                ToolStudioResourceGatewayProtocol.VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityAuthorityCohortPolicy(
                "scope-a", "cohort-a", "replica-a", UUID.randomUUID().toString(), ARTIFACT,
                Set.of("replica-a"), "iam.example",
                ToolStudioResourceGatewayProtocol.VERSION,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three heartbeats");
    }

    @Test
    void heartbeatPurgesOnlyBoundedExpiredRowsAcrossPriorCohorts() {
        TestSuiteStabilityAuthorityCohortPolicy oldPolicy = policy(
                "deployment-old", "replica-a", UUID.randomUUID().toString(),
                Set.of("replica-a"));
        repository(oldPolicy).heartbeat(member(oldPolicy, GENERATION_A, true));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_cohort_members
                SET observed_at = CURRENT_TIMESTAMP - INTERVAL '2' HOUR,
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '119' MINUTE,
                    purge_after = CURRENT_TIMESTAMP - INTERVAL '1' SECOND
                WHERE cohort_id = ?
                """, oldPolicy.cohortId());
        TestSuiteStabilityAuthorityCohortPolicy currentPolicy = policy(
                "deployment-current", "replica-a", UUID.randomUUID().toString(),
                Set.of("replica-a"));

        repository(currentPolicy).heartbeat(member(currentPolicy, GENERATION_A, true));

        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_test_suite_stability_authority_cohort_members
                WHERE cohort_id = ?
                """, Integer.class, oldPolicy.cohortId())).isZero();
    }

    @Test
    void stableScopeAllowsOnlyOneLiveDeploymentCohort() {
        TestSuiteStabilityAuthorityCohortPolicy oldPolicy = policy(
                "deployment-old", "replica-old", UUID.randomUUID().toString(),
                Set.of("replica-old"));
        TestSuiteStabilityAuthorityCohortPolicy newPolicy = policy(
                "deployment-new", "replica-new", UUID.randomUUID().toString(),
                Set.of("replica-new"));
        var oldRepository = repository(oldPolicy);
        var newRepository = repository(newPolicy);
        assertThat(oldRepository.heartbeat(member(oldPolicy, GENERATION_A, true)).converged())
                .isTrue();

        assertThat(newRepository.heartbeat(member(newPolicy, GENERATION_A, true)))
                .satisfies(snapshot -> {
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("COHORT_NOT_ACTIVE");
                });
        assertThat(oldRepository.snapshot().converged()).isTrue();

        Instant observedAt = Instant.parse("2026-07-18T00:00:00Z");
        Instant leaseExpiresAt = observedAt.plusSeconds(3);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_active_cohorts
                SET observed_at = ?, lease_expires_at = ?, record_fingerprint = ?
                WHERE scope_id = ?
                """, Timestamp.from(observedAt), Timestamp.from(leaseExpiresAt),
                activeCohortFingerprint(oldPolicy, observedAt, leaseExpiresAt),
                oldPolicy.scopeId());
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_cohort_members
                SET observed_at = CURRENT_TIMESTAMP - INTERVAL '4' SECOND,
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND
                WHERE cohort_id = ?
                """, oldPolicy.cohortId());

        assertThat(newRepository.heartbeat(member(newPolicy, GENERATION_A, true)).converged())
                .isTrue();
        assertThat(oldRepository.snapshot().blockers())
                .contains("COHORT_NOT_ACTIVE", "LOCAL_PROCESS_NOT_REGISTERED");
    }

    @Test
    void equalCohortNamesInDifferentFleetScopesRemainIsolated() {
        TestSuiteStabilityAuthorityCohortPolicy scopeA = policy(
                "scope-a", "shared-deployment", "replica-a",
                UUID.randomUUID().toString(), Set.of("replica-a"));
        TestSuiteStabilityAuthorityCohortPolicy scopeB = policy(
                "scope-b", "shared-deployment", "replica-a",
                UUID.randomUUID().toString(), Set.of("replica-a"));

        assertThat(repository(scopeA).heartbeat(member(scopeA, GENERATION_A, true)).converged())
                .isTrue();
        assertThat(repository(scopeB).heartbeat(member(scopeB, GENERATION_A, true)).converged())
                .isTrue();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM rg_test_suite_stability_authority_cohort_members
                WHERE cohort_id = ?
                """, Integer.class, "shared-deployment")).isEqualTo(2);
    }

    @Test
    void concurrentFirstHeartbeatsElectExactlyOneActiveCohort() throws Exception {
        TestSuiteStabilityAuthorityCohortPolicy cohortA = policy(
                "deployment-race-a", "replica-a", UUID.randomUUID().toString(),
                Set.of("replica-a"));
        TestSuiteStabilityAuthorityCohortPolicy cohortB = policy(
                "deployment-race-b", "replica-b", UUID.randomUUID().toString(),
                Set.of("replica-b"));
        var repositoryA = repository(cohortA);
        var repositoryB = repository(cohortB);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var resultA = executor.submit(() -> {
                ready.countDown();
                start.await();
                return repositoryA.heartbeat(member(cohortA, GENERATION_A, true));
            });
            var resultB = executor.submit(() -> {
                ready.countDown();
                start.await();
                return repositoryB.heartbeat(member(cohortB, GENERATION_A, true));
            });
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(Set.of(resultA.get(3, TimeUnit.SECONDS).status(),
                    resultB.get(3, TimeUnit.SECONDS).status()))
                    .containsExactlyInAnyOrder("CONVERGED", "COHORT_NOT_ACTIVE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void corruptActiveCohortAuthorityCannotBeSilentlyOverwritten() {
        TestSuiteStabilityAuthorityCohortPolicy policy = policy(
                "deployment-corrupt", "replica-a", UUID.randomUUID().toString(),
                Set.of("replica-a"));
        var repository = repository(policy);
        repository.heartbeat(member(policy, GENERATION_A, true));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_active_cohorts
                SET record_fingerprint = ? WHERE scope_id = ?
                """, GENERATION_B, policy.scopeId());

        assertThat(repository.snapshot().status()).isEqualTo("COHORT_AUTHORITY_CORRUPT");
        assertThatThrownBy(() -> repository.heartbeat(member(policy, GENERATION_A, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    private DatabaseTestSuiteStabilityAuthorityCohortRepository repository(
            TestSuiteStabilityAuthorityCohortPolicy policy) {
        var repository = new DatabaseTestSuiteStabilityAuthorityCohortRepository(
                database.jdbc(), objectMapper, policy, database.transactionManager());
        repository.init();
        return repository;
    }

    private TestSuiteStabilityAuthorityCohortPolicy policy(
            String instanceId, String startupId, Set<String> expected) {
        return policy("deployment-2026-07-19", instanceId, startupId, expected);
    }

    private TestSuiteStabilityAuthorityCohortPolicy policy(
            String cohortId, String instanceId, String startupId, Set<String> expected) {
        return policy("stability-authority-scope", cohortId,
                instanceId, startupId, expected);
    }

    private TestSuiteStabilityAuthorityCohortPolicy policy(
            String scopeId,
            String cohortId,
            String instanceId,
            String startupId,
            Set<String> expected) {
        return new TestSuiteStabilityAuthorityCohortPolicy(
                scopeId, cohortId, instanceId, startupId, ARTIFACT,
                expected, "iam.example", ToolStudioResourceGatewayProtocol.VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1));
    }

    private TestSuiteStabilityAuthorityCohortRepository.Member member(
            TestSuiteStabilityAuthorityCohortPolicy policy,
            String generation,
            boolean available) {
        return new TestSuiteStabilityAuthorityCohortRepository.Member(
                "bloge.testSuiteStabilityAuthorityCohortMember.v1",
                policy.scopeId(), policy.cohortId(), policy.instanceId(), policy.startupId(),
                policy.artifactFingerprint(), policy.cohortFingerprint(objectMapper),
                policy.protocolVersion(), policy.authorityId(), "DYNAMIC_JWKS_ED25519",
                available, available ? "HEALTHY" : "UNAVAILABLE",
                generation, available ? 1 : 0, available ? REFRESHED_AT : null);
    }

    private String activeCohortFingerprint(
            TestSuiteStabilityAuthorityCohortPolicy policy,
            Instant observedAt,
            Instant leaseExpiresAt) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion",
                        "bloge.testSuiteStabilityAuthorityActiveCohort.v1"),
                Map.entry("scopeId", policy.scopeId()),
                Map.entry("cohortId", policy.cohortId()),
                Map.entry("policyFingerprint", policy.cohortFingerprint(objectMapper)),
                Map.entry("observedAt", observedAt.toString()),
                Map.entry("leaseExpiresAt", leaseExpiresAt.toString())));
    }
}
