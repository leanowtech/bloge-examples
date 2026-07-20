package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DynamicJwksTestSecretAuthorityTrustStore;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityTrustCohortPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityCohortPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityCohortRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSecretAuthorityTrustCohortRepositoryTest {

    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String GENERATION_A = "sha256:" + "b".repeat(64);
    private static final String GENERATION_B = "sha256:" + "c".repeat(64);
    private static final Instant REFRESHED_AT = Instant.parse("2026-07-20T00:00:00Z");

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:test-secret-trust-cohort-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void exactTwoReplicaCohortRequiresOneEquivalentHealthyGeneration() {
        Set<String> expected = Set.of("replica-a", "replica-b");
        var repositoryA = repository(policy("deployment-a", "replica-a", expected));
        var repositoryB = repository(policy("deployment-a", "replica-b", expected));

        assertThat(repositoryA.heartbeat(observation(GENERATION_A, true)))
                .satisfies(snapshot -> {
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("MEMBER_MISSING");
                    assertThat(snapshot.missingReplicaCount()).isOne();
                });
        assertThat(repositoryB.heartbeat(observation(GENERATION_A, true)))
                .satisfies(snapshot -> {
                    assertThat(snapshot.converged()).isTrue();
                    assertThat(snapshot.liveReplicaCount()).isEqualTo(2);
                    assertThat(snapshot.healthyReplicaCount()).isEqualTo(2);
                    assertThat(snapshot.distinctTrustGenerationCount()).isOne();
                    assertThat(snapshot.blockers()).isEmpty();
                });

        assertThat(repositoryB.heartbeat(observation(GENERATION_B, true)))
                .satisfies(snapshot -> {
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("SNAPSHOT_DIVERGED");
                    assertThat(snapshot.distinctTrustGenerationCount()).isEqualTo(2);
                });
        assertThat(repositoryB.heartbeat(observation(GENERATION_A, true)).converged()).isTrue();
    }

    @Test
    void duplicateProcessAndUnavailableMemberRemainFailClosed() {
        Set<String> expected = Set.of("replica-a");
        TestSecretAuthorityTrustCohortPolicy original =
                policy("deployment-a", "replica-a", expected);
        TestSecretAuthorityTrustCohortPolicy replacement =
                policy("deployment-a", "replica-a", expected);
        var originalRepository = repository(original);
        var replacementRepository = repository(replacement);

        assertThat(originalRepository.heartbeat(observation(GENERATION_A, true)).converged())
                .isTrue();
        assertThat(replacementRepository.heartbeat(observation(GENERATION_A, true)).blockers())
                .contains("DUPLICATE_INSTANCE");
        replacementRepository.withdraw(replacement.instanceId(), replacement.startupId());

        assertThat(originalRepository.heartbeat(observation(GENERATION_A, false)))
                .satisfies(snapshot -> {
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("MEMBER_UNHEALTHY");
                    assertThat(snapshot.healthyReplicaCount()).isZero();
                });
    }

    @Test
    void oneStableScopeCannotSelfAdmitTwoLiveDeploymentGenerations() {
        var oldRepository = repository(policy(
                "deployment-old", "replica-old", Set.of("replica-old")));
        var newRepository = repository(policy(
                "deployment-new", "replica-new", Set.of("replica-new")));

        assertThat(oldRepository.heartbeat(observation(GENERATION_A, true)).converged()).isTrue();
        assertThat(newRepository.heartbeat(observation(GENERATION_A, true)))
                .satisfies(snapshot -> {
                    assertThat(snapshot.converged()).isFalse();
                    assertThat(snapshot.status()).isEqualTo("COHORT_NOT_ACTIVE");
                    assertThat(snapshot.blockers()).contains("COHORT_NOT_ACTIVE");
                });
        assertThat(oldRepository.snapshot().converged()).isTrue();
    }

    @Test
    void databaseCorruptionAndPolicyDefectsCannotBecomeConverged() {
        TestSecretAuthorityTrustCohortPolicy policy =
                policy("deployment-a", "replica-a", Set.of("replica-a"));
        var repository = repository(policy);
        repository.heartbeat(observation(GENERATION_A, true));
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_authority_cohort_members
                SET snapshot_fingerprint = ?
                WHERE scope_id = ? AND cohort_id = ? AND instance_id = ?
                """, GENERATION_B, policy.asDatabasePolicy().scopeId(),
                policy.cohortId(), policy.instanceId());

        assertThat(repository.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.converged()).isFalse();
            assertThat(snapshot.blockers()).contains("INVENTORY_CORRUPT", "MEMBER_MISSING");
        });
        assertThatThrownBy(() -> new TestSecretAuthorityTrustCohortPolicy(
                "scope-a", "cohort-a", "replica-a", "not-a-uuid", ARTIFACT,
                Set.of("replica-a"), "secret-authority.example",
                TestSecretAuthorityResponse.SCHEMA_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSecretAuthorityTrustCohortPolicy(
                "scope-a", "cohort-a", "replica-a", UUID.randomUUID().toString(), ARTIFACT,
                Set.of("replica-b"), "secret-authority.example",
                TestSecretAuthorityResponse.SCHEMA_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testSecretNamespaceDoesNotCollideWithStabilityAuthorityScope() {
        TestSecretAuthorityTrustCohortPolicy secretPolicy =
                policy("shared-deployment", "replica-a", Set.of("replica-a"));
        var secretRepository = repository(secretPolicy);
        TestSuiteStabilityAuthorityCohortPolicy stabilityPolicy =
                new TestSuiteStabilityAuthorityCohortPolicy(
                        secretPolicy.scopeId(), secretPolicy.cohortId(), "replica-a",
                        UUID.randomUUID().toString(), ARTIFACT, Set.of("replica-a"),
                        "stability-authority.example", "bloge.protocol.v1",
                        Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1),
                        TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation
                                .localConfigured());
        var stabilityRepository = new DatabaseTestSuiteStabilityAuthorityCohortRepository(
                database.jdbc(), objectMapper, stabilityPolicy, database.transactionManager());
        stabilityRepository.init();

        assertThat(secretRepository.heartbeat(observation(GENERATION_A, true)).converged())
                .isTrue();
        assertThat(stabilityRepository.heartbeat(stabilityMember(stabilityPolicy)).converged())
                .isTrue();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(DISTINCT scope_id)
                FROM rg_test_suite_stability_authority_cohort_members
                WHERE cohort_id = ?
                """, Integer.class, secretPolicy.cohortId())).isEqualTo(2);
    }

    private DatabaseTestSecretAuthorityTrustCohortRepository repository(
            TestSecretAuthorityTrustCohortPolicy policy) {
        var repository = new DatabaseTestSecretAuthorityTrustCohortRepository(
                database.jdbc(), objectMapper, policy, database.transactionManager());
        repository.init();
        return repository;
    }

    private TestSecretAuthorityTrustCohortPolicy policy(
            String cohortId, String instanceId, Set<String> expected) {
        return new TestSecretAuthorityTrustCohortPolicy(
                "test-secret-scope", cohortId, instanceId, UUID.randomUUID().toString(),
                ARTIFACT, expected, "secret-authority.example",
                TestSecretAuthorityResponse.SCHEMA_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1));
    }

    private DynamicJwksTestSecretAuthorityTrustStore.CohortObservation observation(
            String generation, boolean available) {
        return new DynamicJwksTestSecretAuthorityTrustStore.CohortObservation(
                DynamicJwksTestSecretAuthorityTrustStore.CohortObservation.SCHEMA_VERSION,
                available, available ? "HEALTHY" : "UNAVAILABLE",
                available ? generation : "", available ? 1 : 0,
                available ? REFRESHED_AT : null);
    }

    private TestSuiteStabilityAuthorityCohortRepository.Member stabilityMember(
            TestSuiteStabilityAuthorityCohortPolicy policy) {
        return new TestSuiteStabilityAuthorityCohortRepository.Member(
                TestSuiteStabilityAuthorityCohortRepository.Member.SCHEMA_VERSION,
                policy.scopeId(), policy.cohortId(), policy.instanceId(), policy.startupId(),
                policy.artifactFingerprint(), policy.cohortFingerprint(objectMapper),
                policy.protocolVersion(), policy.authorityId(), "DYNAMIC_JWKS_ED25519",
                true, "HEALTHY", GENERATION_A, 0, "", 1, REFRESHED_AT);
    }
}
