package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

        assertThatThrownBy(() -> configuredAuthority(true, true, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable publication floor");
    }

    @Test
    void managedTrustRootsAreRequiredExplicitlyAndForbidStaticRuntimeKeys() {
        assertThatThrownBy(() -> configuredAuthority(
                true, true, true, false, "", publicationFloorProvider(), rootProvider()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires managed serving-inventory trust roots");

        ObjectProvider<TestSuiteStabilityServingInventoryPublicationFloor> floors =
                publicationFloorProvider();
        TestSuiteStabilityServingInventoryPublicationFloor floor =
                mock(TestSuiteStabilityServingInventoryPublicationFloor.class);
        when(floor.durable()).thenReturn(true);
        when(floors.orderedStream()).thenAnswer(ignored -> Stream.of(floor));
        ObjectProvider<DynamicTestSuiteStabilityServingInventoryTrustRootAuthority> roots =
                rootProvider();
        when(roots.orderedStream()).thenAnswer(ignored -> Stream.of(
                mock(DynamicTestSuiteStabilityServingInventoryTrustRootAuthority.class)));

        assertThatThrownBy(() -> configuredAuthority(
                true, true, true, true, "", floors, roots))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbid static runtime keys");

        assertThatThrownBy(() -> configuration
                .dynamicTestSuiteStabilityServingInventoryTrustRootAuthority(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .findAndRegisterModules(),
                        mock(TestSuiteStabilityServingInventoryTrustRootFloor.class),
                        "scope-a", "inventory-roots", "sha256:" + "a".repeat(64),
                        "deployment-root.example", 1, "[]",
                        "witness-root.example", 1, "[]",
                        "https://roots.example/current", 30, 3000, 5, 60, false,
                        "legacy-runtime.example", 0, "[]", "", 0, "[]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbid static runtime keys");
    }

    @Test
    void externalNonEquivocationIsRequiredExplicitlyAndWrapsBothLocalFloors() {
        ObjectProvider<TestSuiteStabilityExternalSequenceAnchor> empty = anchorProvider();
        when(empty.orderedStream()).thenReturn(Stream.empty());
        assertThatThrownBy(() -> configuration
                .testSuiteStabilityServingInventoryPublicationFloor(
                        mock(TestRuntimeDatabase.class), new ObjectMapper(), empty,
                        "scope-a", false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires external serving-inventory non-equivocation");

        TestSuiteStabilityExternalSequenceAnchor anchor =
                mock(TestSuiteStabilityExternalSequenceAnchor.class);
        when(anchor.descriptor()).thenReturn(
                new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                        TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                        true, true, true, true, 4, 3, 1, 4, java.util.Map.of()));
        ObjectProvider<TestSuiteStabilityExternalSequenceAnchor> configured = anchorProvider();
        when(configured.orderedStream()).thenAnswer(ignored -> Stream.of(anchor));
        try (TestRuntimeDatabase database = new TestRuntimeDatabase(
                new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:external-anchor-config-" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1",
                        "sa", "", 2))) {
            var publication = configuration
                    .testSuiteStabilityServingInventoryPublicationFloor(
                            database, new ObjectMapper().findAndRegisterModules(), configured,
                            "scope-a", true, true);
            var roots = configuration.testSuiteStabilityServingInventoryTrustRootFloor(
                    database, new ObjectMapper().findAndRegisterModules(), configured,
                    "scope-a", "inventory-roots", true, true);

            assertThat(publication)
                    .isInstanceOf(
                            ExternallyAnchoredTestSuiteStabilityServingInventoryPublicationFloor.class);
            assertThat(roots)
                    .isInstanceOf(
                            ExternallyAnchoredTestSuiteStabilityServingInventoryTrustRootFloor.class);
            assertThat(publication.externallyAnchored()).isTrue();
            assertThat(roots.externallyAnchored()).isTrue();
        }
    }

    @Test
    void deploymentFaultPolicyCannotBeDowngradedToSingleNotaryMode() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        assertThatThrownBy(() -> configuration.testSuiteStabilityExternalSequenceAnchor(
                new ObjectMapper().findAndRegisterModules(),
                environment, mock(TestRuntimeDatabase.class), "scope-a",
                "inventory-transparency", "notary-set-a", 1, 0, 0,
                "[]", "[]", 3000, 5, 15, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not meet the deployment fault policy");
    }

    @Test
    void stagingSuiteAnchorRequiresManagedBootstrapRootChain() {
        String prefix =
                "gateway.testing.stability-jobs.authority.http.jwks.cohort.signed-inventory.remote.external-anchor";
        MockEnvironment environment = new MockEnvironment()
                .withProperty(prefix + ".managed-trust.enabled", "true")
                .withProperty(prefix + ".managed-trust.bootstrap-roots.enabled", "false");
        environment.setActiveProfiles("staging");

        assertThatThrownBy(() -> configuration.testSuiteStabilityExternalSequenceAnchor(
                new ObjectMapper().findAndRegisterModules(), environment,
                mock(TestRuntimeDatabase.class), "scope-a", "inventory-transparency",
                "notary-set-a", 3, 1, 1, "[]", "[]", 3000, 5, 15, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires managed bootstrap-root trust");
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
        return configuredAuthority(remoteEnabled, remoteRequired, false, false, inventoryJson,
                publicationFloorProvider(), rootProvider());
    }

    private TestSuiteStabilityServingInventoryAuthority configuredAuthority(
            boolean remoteEnabled,
            boolean remoteRequired,
            boolean managedRootsRequired,
            boolean managedRootsEnabled,
            String inventoryJson,
            ObjectProvider<TestSuiteStabilityServingInventoryPublicationFloor> floors,
            ObjectProvider<DynamicTestSuiteStabilityServingInventoryTrustRootAuthority> roots) {
        return configuration.testSuiteStabilityServingInventoryAuthority(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                floors, roots,
                "inventory.example", "sha256:" + "b".repeat(64), 1, "[]",
                inventoryJson, remoteEnabled, remoteRequired,
                managedRootsEnabled, managedRootsRequired,
                "https://inventory.example/v1/current", 30, 3000, 60, false,
                "inventory-witness.example", 1, "[]",
                "scope-a", "cohort-a", "replica-a", "sha256:" + "f".repeat(64));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TestSuiteStabilityServingInventoryAuthority> provider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TestSuiteStabilityServingInventoryPublicationFloor>
            publicationFloorProvider() {
        ObjectProvider<TestSuiteStabilityServingInventoryPublicationFloor> provider =
                mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DynamicTestSuiteStabilityServingInventoryTrustRootAuthority>
            rootProvider() {
        ObjectProvider<DynamicTestSuiteStabilityServingInventoryTrustRootAuthority> provider =
                mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TestSuiteStabilityExternalSequenceAnchor> anchorProvider() {
        return mock(ObjectProvider.class);
    }
}
