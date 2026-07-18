package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteStabilityServingInventoryConfigurationTest {

    private final TestRuntimeConfiguration configuration = new TestRuntimeConfiguration();

    @Test
    void requiredSignedInventoryFailsClosedWhenNoAuthorityIsConfigured() {
        ObjectProvider<TestSuiteStabilityServingInventoryAuthority> provider = provider();
        when(provider.orderedStream()).thenReturn(Stream.empty());

        assertThatThrownBy(() -> policy(provider, "replica-a", false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires deployment-signed serving inventory");
    }

    @Test
    void verifiedInventoryIsTheAuthoritativeExpectedSetAndLocalListIsOnlyAnAssertion() {
        TestSuiteStabilityServingInventoryAuthority authority =
                mock(TestSuiteStabilityServingInventoryAuthority.class);
        when(authority.observation()).thenReturn(observation());
        ObjectProvider<TestSuiteStabilityServingInventoryAuthority> provider = provider();
        when(provider.orderedStream()).thenAnswer(ignored -> Stream.of(authority));

        TestSuiteStabilityAuthorityCohortPolicy policy = policy(
                provider, "replica-a,replica-b", true, true);

        assertThat(policy.expectedInstanceIds())
                .containsExactlyInAnyOrder("replica-a", "replica-b");
        assertThat(policy.servingInventory()).satisfies(attestation -> {
            assertThat(attestation.externallyAttested()).isTrue();
            assertThat(attestation.revision()).isEqualTo(17);
            assertThat(attestation.materialFingerprint())
                    .isEqualTo("sha256:" + "c".repeat(64));
        });

        assertThatThrownBy(() -> policy(provider, "replica-a", true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventories disagree");
    }

    @Test
    void dynamicWitnessedSourceCanBeRequiredAndCannotMixWithStaticDocument() {
        assertThatThrownBy(() -> configuredAuthority(false, true, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires dynamic witnessed serving inventory");

        assertThatThrownBy(() -> configuredAuthority(true, true, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot also use a static inventory document");
    }

    private TestSuiteStabilityAuthorityCohortPolicy policy(
            ObjectProvider<TestSuiteStabilityServingInventoryAuthority> provider,
            String configuredInstances,
            boolean enabled,
            boolean required) {
        return configuration.testSuiteStabilityAuthorityCohortPolicy(
                provider, "scope-a", "cohort-a", "replica-a",
                "sha256:" + "f".repeat(64), configuredInstances, "iam.example",
                1, 3, 3600, enabled, required);
    }

    private static TestSuiteStabilityServingInventoryAuthority.Observation observation() {
        return new TestSuiteStabilityServingInventoryAuthority.Observation(
                TestSuiteStabilityServingInventoryAuthority.Observation.SCHEMA_VERSION,
                true, true, true, "VERIFIED", "STATIC_SIGNED_ED25519_M_OF_N",
                17, "sha256:" + "c".repeat(64), 17,
                "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                List.of("replica-a", "replica-b"),
                Instant.parse("2026-07-20T00:00:00Z"), 2, 2);
    }

    private TestSuiteStabilityServingInventoryAuthority configuredAuthority(
            boolean remoteEnabled,
            boolean remoteRequired,
            String inventoryJson) {
        return configuration.testSuiteStabilityServingInventoryAuthority(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                "inventory.example", "sha256:" + "b".repeat(64), 1, "[]",
                inventoryJson, remoteEnabled, remoteRequired,
                "https://inventory.example/v1/current", 30, 3000, 60, false,
                "inventory-witness.example", 1, "[]",
                "scope-a", "cohort-a", "replica-a", "sha256:" + "f".repeat(64));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TestSuiteStabilityServingInventoryAuthority> provider() {
        return mock(ObjectProvider.class);
    }
}
