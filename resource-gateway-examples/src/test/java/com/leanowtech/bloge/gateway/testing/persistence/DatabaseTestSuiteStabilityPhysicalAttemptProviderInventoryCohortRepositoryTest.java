package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.CohortBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepositoryTest {

    private static final String ARTIFACT = fingerprint('a');
    private static final String PROTOCOL = "tool-studio-resource-gateway.v1";
    private static final String START_A = "00000000-0000-0000-0000-000000000001";
    private static final String START_B = "00000000-0000-0000-0000-000000000002";
    private static final String START_C = "00000000-0000-0000-0000-000000000003";

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority sourceA;
    private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority sourceB;
    private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority sourceC;

    @BeforeEach
    void setUp() {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:physical-provider-cohort-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 6));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        sourceA = mock(DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class);
        sourceB = mock(DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class);
        sourceC = mock(DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void exactSignedReplicaSetConvergesAcrossIndependentProcessStarts() {
        CohortBinding binding = binding(1, List.of("replica-a", "replica-b"), true);
        when(sourceA.cohortBinding()).thenReturn(binding);
        when(sourceB.cohortBinding()).thenReturn(binding);
        var replicaA = repository(sourceA, "replica-a", ARTIFACT, PROTOCOL);
        var replicaB = repository(sourceB, "replica-b", ARTIFACT, PROTOCOL);

        assertThat(replicaA.heartbeat(START_A)).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.status()).isEqualTo("MEMBER_MISSING");
            assertThat(observed.expectedReplicas()).isEqualTo(2);
            assertThat(observed.readyReplicas()).isOne();
        });
        assertThat(replicaB.heartbeat(START_B)).satisfies(observed -> {
            assertThat(observed.available()).isTrue();
            assertThat(observed.status()).isEqualTo("CONVERGED");
            assertThat(observed.readyReplicas()).isEqualTo(2);
            assertThat(observed.distinctInventoryGenerations()).isOne();
        });
        assertThat(replicaA.observation().available()).isTrue();
    }

    @Test
    void signedExpectedSetExpansionCannotBeMaskedByLocalConfiguration() {
        CohortBinding generationOne = binding(
                1, List.of("replica-a", "replica-b"), true);
        when(sourceA.cohortBinding()).thenReturn(generationOne);
        when(sourceB.cohortBinding()).thenReturn(generationOne);
        var replicaA = repository(sourceA, "replica-a", ARTIFACT, PROTOCOL);
        var replicaB = repository(sourceB, "replica-b", ARTIFACT, PROTOCOL);
        replicaA.heartbeat(START_A);
        assertThat(replicaB.heartbeat(START_B).available()).isTrue();

        CohortBinding generationTwo = binding(
                2, List.of("replica-a", "replica-b", "replica-c"), true);
        when(sourceA.cohortBinding()).thenReturn(generationTwo);
        when(sourceB.cohortBinding()).thenReturn(generationTwo);
        when(sourceC.cohortBinding()).thenReturn(generationTwo);

        assertThat(replicaA.observation()).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.expectedReplicas()).isEqualTo(3);
            assertThat(observed.status()).isEqualTo("MEMBER_MISSING");
        });
        replicaA.heartbeat(START_A);
        assertThat(replicaB.heartbeat(START_B).available()).isFalse();
        var replicaC = repository(sourceC, "replica-c", ARTIFACT, PROTOCOL);
        assertThat(replicaC.heartbeat(START_C)).satisfies(observed -> {
            assertThat(observed.available()).isTrue();
            assertThat(observed.inventorySourceSequence()).isEqualTo(2);
            assertThat(observed.expectedReplicas()).isEqualTo(3);
        });
    }

    @Test
    void duplicateAndUnexpectedLiveProcessesRemainVisible() {
        CohortBinding binding = binding(1, List.of("replica-a"), true);
        when(sourceA.cohortBinding()).thenReturn(binding);
        when(sourceB.cohortBinding()).thenReturn(binding);
        var firstStart = repository(sourceA, "replica-a", ARTIFACT, PROTOCOL);
        var secondStart = repository(sourceB, "replica-a", ARTIFACT, PROTOCOL);
        firstStart.heartbeat(START_A);

        assertThat(secondStart.heartbeat(START_B)).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.status()).isEqualTo("DUPLICATE_REPLICA");
            assertThat(observed.readyReplicas()).isZero();
        });

        secondStart.withdraw(START_B);
        assertThat(firstStart.observation().available()).isTrue();
        var unexpected = repository(sourceB, "replica-b", ARTIFACT, PROTOCOL);
        assertThat(unexpected.heartbeat(START_B)).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.status()).isEqualTo("UNEXPECTED_MEMBER");
        });
    }

    @Test
    void generationArtifactProtocolAndAvailabilityDivergenceFailClosed() {
        CohortBinding first = binding(1, List.of("replica-a", "replica-b"), true);
        CohortBinding second = binding(2, List.of("replica-a", "replica-b"), true);
        when(sourceA.cohortBinding()).thenReturn(first);
        when(sourceB.cohortBinding()).thenReturn(second);
        var replicaA = repository(sourceA, "replica-a", ARTIFACT, PROTOCOL);
        var replicaB = repository(sourceB, "replica-b", ARTIFACT, PROTOCOL);
        replicaA.heartbeat(START_A);
        assertThat(replicaB.heartbeat(START_B).status())
                .isEqualTo("INVENTORY_GENERATION_DIVERGED");

        when(sourceA.cohortBinding()).thenReturn(second);
        replicaA.heartbeat(START_A);
        var wrongArtifact = repository(sourceB, "replica-b", fingerprint('c'), PROTOCOL);
        assertThat(wrongArtifact.heartbeat(START_B).status())
                .isEqualTo("ARTIFACT_DIVERGED");

        wrongArtifact.withdraw(START_B);
        var wrongProtocol = repository(sourceB, "replica-b", ARTIFACT, "wrong.protocol");
        assertThat(wrongProtocol.heartbeat(START_B).status())
                .isEqualTo("PROTOCOL_DIVERGED");

        CohortBinding revoked = binding(3, List.of("replica-a", "replica-b"), false);
        when(sourceA.cohortBinding()).thenReturn(revoked);
        assertThat(replicaA.heartbeat(START_A)).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.status()).isEqualTo("SOURCE_UNAVAILABLE");
        });
    }

    @Test
    void tamperedRowsAreExcludedAndReportedAsCorrupt() {
        CohortBinding binding = binding(1, List.of("replica-a"), true);
        when(sourceA.cohortBinding()).thenReturn(binding);
        var repository = repository(sourceA, "replica-a", ARTIFACT, PROTOCOL);
        assertThat(repository.heartbeat(START_A).available()).isTrue();
        database.jdbc().update("""
                UPDATE rg_test_physical_provider_inventory_cohort_members
                SET artifact_fingerprint = ?
                WHERE scope_id = ? AND cohort_id = ? AND replica_id = ?
                """, fingerprint('f'), binding.scopeId(), binding.cohortId(), "replica-a");

        assertThat(repository.observation()).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.status()).isEqualTo("INVENTORY_CORRUPT");
            assertThat(observed.readyReplicas()).isZero();
        });
    }

    @Test
    void repositoryReconstructionPreservesLiveCohortState() {
        CohortBinding binding = binding(1, List.of("replica-a"), true);
        when(sourceA.cohortBinding()).thenReturn(binding);
        repository(sourceA, "replica-a", ARTIFACT, PROTOCOL).heartbeat(START_A);

        assertThat(repository(sourceA, "replica-a", ARTIFACT, PROTOCOL)
                .observation().available()).isTrue();
    }

    private DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
            repository(
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority source,
            String replicaId,
            String artifact,
            String protocol) {
        var policy = new
                DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
                        .LocalPolicy(
                replicaId, artifact, protocol, Duration.ofSeconds(10),
                Duration.ofSeconds(20));
        var repository = new
                DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository(
                database.jdbc(), objectMapper, source, policy, database.transactionManager());
        repository.init();
        return repository;
    }

    private static CohortBinding binding(
            long sequence, List<String> expectedReplicas, boolean available) {
        return new CohortBinding(CohortBinding.SCHEMA_VERSION,
                "physical-attempt-providers", "release-2026-07-22", expectedReplicas,
                available, sequence, fingerprint(sequence == 1 ? 'd' : sequence == 2 ? 'e' : 'f'),
                Instant.parse("2026-07-22T01:00:00Z"));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
