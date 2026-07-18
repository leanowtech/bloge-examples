package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityAuthorityCohortMonitorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void localChangeClosesGateUntilPublishedAndCloseWithdrawsExactStartup() {
        TestSuiteStabilityAuthorityCohortRepository repository =
                mock(TestSuiteStabilityAuthorityCohortRepository.class);
        DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore =
                mock(DynamicJwksTestSuiteStabilityAuthorityTrustStore.class);
        TestSuiteStabilityAuthorityCohortPolicy policy = policy();
        var first = observation("sha256:" + "a".repeat(64));
        var second = observation("sha256:" + "b".repeat(64));
        when(trustStore.cohortObservation()).thenReturn(first);
        when(repository.heartbeat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(converged(0));
        when(repository.snapshot()).thenReturn(converged(0));
        TestSuiteStabilityAuthorityCohortMonitor monitor =
                new TestSuiteStabilityAuthorityCohortMonitor(
                        repository, trustStore, policy, objectMapper, false);

        assertThat(monitor.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.status()).isEqualTo("CONVERGED");
            assertThat(descriptor.expectedReplicaCount()).isOne();
        });

        when(trustStore.cohortObservation()).thenReturn(observation(
                "sha256:" + "a".repeat(64),
                Instant.parse("2026-07-19T00:00:01Z")));
        assertThat(monitor.descriptor().available()).isTrue();

        when(trustStore.cohortObservation()).thenReturn(second);
        assertThat(monitor.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.status()).isEqualTo("LOCAL_OBSERVATION_UNPUBLISHED");
        });
        verify(repository, times(2)).snapshot();

        assertThat(monitor.publishNow()).isTrue();
        assertThat(monitor.descriptor().available()).isTrue();
        assertThat(new TestSuiteStabilityAuthorityCohortHealth(monitor)
                .health().getStatus()).isEqualTo(Status.UP);
        monitor.close();
        assertThat(monitor.descriptor().status()).isEqualTo("CLOSED");
        verify(repository).withdraw(policy.instanceId(), policy.startupId());
    }

    @Test
    void storeFailureAndAggregateHealthRemainPayloadFree() {
        TestSuiteStabilityAuthorityCohortRepository repository =
                mock(TestSuiteStabilityAuthorityCohortRepository.class);
        DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore =
                mock(DynamicJwksTestSuiteStabilityAuthorityTrustStore.class);
        when(trustStore.cohortObservation()).thenReturn(
                observation("sha256:" + "a".repeat(64)));
        when(repository.heartbeat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(converged(0));
        when(repository.snapshot()).thenThrow(new IllegalStateException("db unavailable"));
        TestSuiteStabilityAuthorityCohortMonitor monitor =
                new TestSuiteStabilityAuthorityCohortMonitor(
                        repository, trustStore, policy(), objectMapper, false);

        TestSuiteStabilityAuthorityCohortHealth health =
                new TestSuiteStabilityAuthorityCohortHealth(monitor);
        assertThat(health.health()).satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(Status.DOWN);
            assertThat(result.getDetails()).containsEntry("status", "STORE_UNAVAILABLE")
                    .doesNotContainKeys("cohortId", "instanceId", "startupId",
                            "snapshotFingerprint", "artifactFingerprint");
        });
        monitor.close();
    }

    @Test
    void externalInventoryExpiryClosesGateAndRemainsAggregateOnly() {
        TestSuiteStabilityAuthorityCohortRepository repository =
                mock(TestSuiteStabilityAuthorityCohortRepository.class);
        DynamicJwksTestSuiteStabilityAuthorityTrustStore trustStore =
                mock(DynamicJwksTestSuiteStabilityAuthorityTrustStore.class);
        TestSuiteStabilityServingInventoryAuthority inventoryAuthority =
                mock(TestSuiteStabilityServingInventoryAuthority.class);
        var verified = inventoryObservation(true, "VERIFIED",
                DynamicTestSuiteStabilityServingInventoryAuthority.SOURCE_TYPE);
        when(inventoryAuthority.observation()).thenReturn(verified);
        when(trustStore.cohortObservation()).thenReturn(
                observation("sha256:" + "a".repeat(64)));
        when(repository.heartbeat(org.mockito.ArgumentMatchers.any()))
                .thenReturn(converged(1));
        when(repository.snapshot()).thenReturn(converged(1));
        TestSuiteStabilityAuthorityCohortPolicy policy = policy(verified);
        TestSuiteStabilityAuthorityCohortMonitor monitor =
                new TestSuiteStabilityAuthorityCohortMonitor(
                        repository, trustStore, inventoryAuthority,
                        policy, objectMapper, false);

        assertThat(monitor.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.externallyAttestedInventory()).isTrue();
            assertThat(descriptor.dynamicallyRefreshedInventory()).isTrue();
            assertThat(descriptor.witnessedInventoryPublications()).isTrue();
            assertThat(descriptor.durableInventoryPublicationFloor()).isTrue();
        });

        when(inventoryAuthority.observation()).thenReturn(
                inventoryObservation(false, "EXPIRED",
                        DynamicTestSuiteStabilityServingInventoryAuthority.SOURCE_TYPE));
        assertThat(monitor.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.status()).isEqualTo("SERVING_INVENTORY_EXPIRED");
            assertThat(descriptor.externallyAttestedInventory()).isTrue();
        });
        assertThat(new TestSuiteStabilityAuthorityCohortHealth(monitor).health().getDetails())
                .containsEntry("externallyAttestedInventory", true)
                .containsEntry("dynamicallyRefreshedInventory", true)
                .containsEntry("witnessedInventoryPublications", true)
                .containsEntry("durableInventoryPublicationFloor", true)
                .doesNotContainKeys("inventoryId", "materialFingerprint",
                        "policyFingerprint", "instanceIds");

        when(inventoryAuthority.observation()).thenReturn(
                new TestSuiteStabilityServingInventoryAuthority.Observation(
                        TestSuiteStabilityServingInventoryAuthority.Observation.SCHEMA_VERSION,
                        true, true, true, "VERIFIED", "STATIC_SIGNED_ED25519_M_OF_N",
                        17, "sha256:" + "c".repeat(64), 17,
                        "sha256:" + "e".repeat(64), "sha256:" + "d".repeat(64),
                        List.of("replica-a"), Instant.parse("2026-07-20T00:00:00Z"),
                        2, 2));
        assertThat(monitor.descriptor().status())
                .isEqualTo("SERVING_INVENTORY_DIVERGED");
        monitor.close();
    }

    private TestSuiteStabilityAuthorityCohortPolicy policy() {
        return new TestSuiteStabilityAuthorityCohortPolicy(
                "scope-a", "cohort-a", "replica-a", UUID.randomUUID().toString(),
                "sha256:" + "f".repeat(64), Set.of("replica-a"), "iam.example",
                ToolStudioResourceGatewayProtocol.VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1),
                TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation
                        .localConfigured());
    }

    private TestSuiteStabilityAuthorityCohortPolicy policy(
            TestSuiteStabilityServingInventoryAuthority.Observation inventory) {
        return new TestSuiteStabilityAuthorityCohortPolicy(
                "scope-a", "cohort-a", "replica-a", UUID.randomUUID().toString(),
                "sha256:" + "f".repeat(64), Set.of("replica-a"), "iam.example",
                ToolStudioResourceGatewayProtocol.VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1),
                TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation
                        .external(inventory));
    }

    private static TestSuiteStabilityServingInventoryAuthority.Observation inventoryObservation(
            boolean available, String status) {
        return inventoryObservation(available, status, "STATIC_SIGNED_ED25519_M_OF_N");
    }

    private static TestSuiteStabilityServingInventoryAuthority.Observation inventoryObservation(
            boolean available, String status, String sourceType) {
        return new TestSuiteStabilityServingInventoryAuthority.Observation(
                TestSuiteStabilityServingInventoryAuthority.Observation.SCHEMA_VERSION,
                true, true, available, status, sourceType,
                17, "sha256:" + "c".repeat(64), 17,
                "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                List.of("replica-a"), Instant.parse("2026-07-20T00:00:00Z"),
                2, 2);
    }

    private static DynamicJwksTestSuiteStabilityAuthorityTrustStore.CohortObservation observation(
            String fingerprint) {
        return observation(fingerprint, Instant.parse("2026-07-19T00:00:00Z"));
    }

    private static DynamicJwksTestSuiteStabilityAuthorityTrustStore.CohortObservation observation(
            String fingerprint,
            Instant refreshedAt) {
        return new DynamicJwksTestSuiteStabilityAuthorityTrustStore.CohortObservation(
                "bloge.testSuiteStabilityAuthorityTrustCohortObservation.v1",
                true, "HEALTHY", fingerprint, 1,
                refreshedAt);
    }

    private static TestSuiteStabilityAuthorityCohortRepository.Snapshot converged(
            int servingInventoryGenerations) {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        return new TestSuiteStabilityAuthorityCohortRepository.Snapshot(
                "bloge.testSuiteStabilityAuthorityCohortSnapshot.v1",
                true, "CONVERGED", 1, 1, 1, 1, servingInventoryGenerations,
                0, 0, 0, 0, 0, 0, 0,
                now, now.plusSeconds(3), List.of());
    }
}
