package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSecretAuthorityTrustCohortMonitorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void localGenerationChangeClosesGateUntilPublishedAndCloseWithdrawsStartup() {
        TestSecretAuthorityTrustCohortRepository repository =
                mock(TestSecretAuthorityTrustCohortRepository.class);
        DynamicJwksTestSecretAuthorityTrustStore trustStore =
                mock(DynamicJwksTestSecretAuthorityTrustStore.class);
        TestSecretAuthorityTrustCohortPolicy policy = policy();
        var generationA = observation("sha256:" + "a".repeat(64));
        var generationB = observation("sha256:" + "b".repeat(64));
        when(trustStore.cohortObservation()).thenReturn(generationA);
        when(repository.heartbeat(any())).thenReturn(converged());
        when(repository.snapshot()).thenReturn(converged());
        TestSecretAuthorityTrustCohortMonitor monitor =
                new TestSecretAuthorityTrustCohortMonitor(
                        repository, trustStore, policy, objectMapper, false);

        assertThat(monitor.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.status()).isEqualTo("CONVERGED");
            assertThat(descriptor.databaseAuthority()).isTrue();
            assertThat(descriptor.exactConfiguredInventory()).isTrue();
        });
        when(trustStore.cohortObservation()).thenReturn(observation(
                generationA.snapshotFingerprint(),
                Instant.parse("2026-07-20T00:01:00Z")));
        assertThat(monitor.descriptor().available()).isTrue();

        when(trustStore.cohortObservation()).thenReturn(generationB);
        assertThat(monitor.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.status()).isEqualTo("LOCAL_OBSERVATION_UNPUBLISHED");
        });
        verify(repository, times(2)).snapshot();

        assertThat(monitor.publishNow()).isTrue();
        assertThat(monitor.descriptor().available()).isTrue();
        monitor.close();
        assertThat(monitor.descriptor().status()).isEqualTo("CLOSED");
        verify(repository).withdraw(policy.instanceId(), policy.startupId());
    }

    @Test
    void databaseFailureAndHealthProjectionRemainAggregateOnly() {
        TestSecretAuthorityTrustCohortRepository repository =
                mock(TestSecretAuthorityTrustCohortRepository.class);
        DynamicJwksTestSecretAuthorityTrustStore trustStore =
                mock(DynamicJwksTestSecretAuthorityTrustStore.class);
        when(trustStore.cohortObservation()).thenReturn(
                observation("sha256:" + "a".repeat(64)));
        when(repository.heartbeat(any())).thenReturn(converged());
        when(repository.snapshot()).thenThrow(new IllegalStateException("database unavailable"));
        TestSecretAuthorityTrustCohortMonitor monitor =
                new TestSecretAuthorityTrustCohortMonitor(
                        repository, trustStore, policy(), objectMapper, false);

        assertThat(new TestSecretAuthorityTrustCohortHealth(monitor).health())
                .satisfies(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails())
                            .containsEntry("status", "STORE_UNAVAILABLE")
                            .containsEntry("expectedReplicaCount", 1)
                            .doesNotContainKeys("scopeId", "cohortId", "instanceId", "startupId",
                                    "snapshotFingerprint", "artifactFingerprint", "keyId");
                });
        monitor.close();
    }

    @Test
    void invalidDescriptorCountsAndUnsafePolicyTimingAreRejected() {
        assertThatThrownBy(() ->
                new TestSecretAuthorityTrustCohortGate.Descriptor(
                        TestSecretAuthorityTrustCohortGate.Descriptor.SCHEMA_VERSION,
                        true, true, "CONVERGED", 2, 1, 1, 1,
                        30, true, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new TestSecretAuthorityTrustCohortPolicy(
                        "scope-a", "cohort-a", "replica-a", UUID.randomUUID().toString(),
                        "sha256:" + "f".repeat(64), Set.of("replica-a"),
                        "secret-authority.example", TestSecretAuthorityResponse.SCHEMA_VERSION,
                        Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three heartbeats");
    }

    private TestSecretAuthorityTrustCohortPolicy policy() {
        return new TestSecretAuthorityTrustCohortPolicy(
                "scope-a", "cohort-a", "replica-a", UUID.randomUUID().toString(),
                "sha256:" + "f".repeat(64), Set.of("replica-a"),
                "secret-authority.example", TestSecretAuthorityResponse.SCHEMA_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofHours(1));
    }

    private static DynamicJwksTestSecretAuthorityTrustStore.CohortObservation observation(
            String fingerprint) {
        return observation(fingerprint, Instant.parse("2026-07-20T00:00:00Z"));
    }

    private static DynamicJwksTestSecretAuthorityTrustStore.CohortObservation observation(
            String fingerprint, Instant refreshedAt) {
        return new DynamicJwksTestSecretAuthorityTrustStore.CohortObservation(
                DynamicJwksTestSecretAuthorityTrustStore.CohortObservation.SCHEMA_VERSION,
                true, "HEALTHY", fingerprint, 1, refreshedAt);
    }

    private static TestSecretAuthorityTrustCohortRepository.Snapshot converged() {
        Instant observed = Instant.parse("2026-07-20T00:00:01Z");
        return new TestSecretAuthorityTrustCohortRepository.Snapshot(
                TestSecretAuthorityTrustCohortRepository.Snapshot.SCHEMA_VERSION,
                true, "CONVERGED", 1, 1, 1, 1,
                0, 0, 0, 0, 0, 0, 0,
                observed, observed.plusSeconds(3), List.of());
    }
}
