package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSecretAuthorityExternalNonEquivocationConfigurationTest {

    private final TestRuntimeConfiguration configuration = new TestRuntimeConfiguration();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void requiredExternalAnchorFailsBeforeEitherLocalFloorCanBeUsed() {
        ObjectProvider<TestSecretAuthorityExternalSequenceAnchor> empty = provider();
        when(empty.orderedStream()).thenReturn(Stream.empty());
        try (TestRuntimeDatabase database = database("required")) {
            assertThatThrownBy(() -> configuration
                    .testSecretAuthorityServingInventoryPublicationFloor(
                            database, objectMapper, empty, "secret-fleet", false, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "requires external test-secret inventory non-equivocation");
            assertThatThrownBy(() -> configuration
                    .testSecretAuthorityServingInventoryTrustRootFloor(
                            database, objectMapper, empty, "secret-fleet", "inventory-roots",
                            false, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "requires external test-secret inventory non-equivocation");
        }
    }

    @Test
    void oneSafeAnchorWrapsBothLocalFloorsWithByzantineTruth() {
        TestSecretAuthorityExternalSequenceAnchor anchor = anchor(true, true);
        ObjectProvider<TestSecretAuthorityExternalSequenceAnchor> configured = provider();
        when(configured.orderedStream()).thenAnswer(ignored -> Stream.of(anchor));
        try (TestRuntimeDatabase database = database("configured")) {
            TestSecretAuthorityServingInventoryPublicationFloor publication = configuration
                    .testSecretAuthorityServingInventoryPublicationFloor(
                            database, objectMapper, configured, "secret-fleet", true, true);
            TestSecretAuthorityServingInventoryTrustRootFloor roots = configuration
                    .testSecretAuthorityServingInventoryTrustRootFloor(
                            database, objectMapper, configured, "secret-fleet", "inventory-roots",
                            true, true);

            assertThat(publication).isInstanceOf(
                    ExternallyAnchoredTestSecretAuthorityServingInventoryPublicationFloor.class);
            assertThat(roots).isInstanceOf(
                    ExternallyAnchoredTestSecretAuthorityServingInventoryTrustRootFloor.class);
            assertThat(publication.durable()).isTrue();
            assertThat(publication.externallyAnchored()).isTrue();
            assertThat(publication.byzantineQuorumAnchored()).isTrue();
            assertThat(roots.durable()).isTrue();
            assertThat(roots.externallyAnchored()).isTrue();
            assertThat(roots.byzantineQuorumAnchored()).isTrue();
        }
    }

    @Test
    void disabledModeRejectsAHiddenAnchorAndUnavailableModeRejectsStartup() {
        ObjectProvider<TestSecretAuthorityExternalSequenceAnchor> hidden = provider();
        when(hidden.orderedStream()).thenAnswer(ignored -> Stream.of(anchor(true, true)));
        try (TestRuntimeDatabase database = database("hidden")) {
            assertThatThrownBy(() -> configuration
                    .testSecretAuthorityServingInventoryPublicationFloor(
                            database, objectMapper, hidden, "secret-fleet", false, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires exactly one anchor");
        }

        ObjectProvider<TestSecretAuthorityExternalSequenceAnchor> unavailable = provider();
        when(unavailable.orderedStream()).thenAnswer(ignored -> Stream.of(anchor(false, false)));
        try (TestRuntimeDatabase database = database("unavailable")) {
            assertThatThrownBy(() -> configuration
                    .testSecretAuthorityServingInventoryTrustRootFloor(
                            database, objectMapper, unavailable, "secret-fleet",
                            "inventory-roots", true, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("anchor is unavailable");
        }
    }

    @Test
    void stagingFaultPolicyCannotBeDowngradedToSingleNotaryMode() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});

        assertThatThrownBy(() -> configuration.testSecretAuthorityExternalSequenceAnchor(
                objectMapper, environment, "secret-transparency", "notary-set-a",
                1, 0, 0, "[]", "[]", 3000, 5, 15, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not meet deployment fault policy");
    }

    @Test
    void stagingAcceptsAnExplicitFourNotaryThreeSignatureConfiguration() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        List<Map<String, Object>> keys = new ArrayList<>();
        List<Map<String, Object>> endpoints = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            String authorityId = "notary-" + index;
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            keys.add(Map.of(
                    "authorityId", authorityId,
                    "keyId", "key-" + index,
                    "publicKeyBase64", Base64.getEncoder().encodeToString(
                            pair.getPublic().getEncoded()),
                    "notBefore", "2020-01-01T00:00:00Z",
                    "expiresAt", "2099-01-01T00:00:00Z",
                    "enabled", true,
                    "revoked", false));
            endpoints.add(Map.of(
                    "authorityId", authorityId,
                    "failureDomain", "region-" + index,
                    "uri", "https://notary-" + index + ".example/v1/append"));
        }

        TestSecretAuthorityExternalSequenceAnchor anchor = configuration
                .testSecretAuthorityExternalSequenceAnchor(
                        objectMapper, environment, "secret-transparency", "notary-set-a",
                        3, 1, 1, objectMapper.writeValueAsString(keys),
                        objectMapper.writeValueAsString(endpoints), 3000, 5, 15, false);

        assertThat(anchor.descriptor())
                .extracting(TestSuiteStabilityExternalSequenceAnchor.Descriptor::available,
                        TestSuiteStabilityExternalSequenceAnchor.Descriptor::byzantineQuorum,
                        TestSuiteStabilityExternalSequenceAnchor.Descriptor::authorityCount,
                        TestSuiteStabilityExternalSequenceAnchor.Descriptor::signatureThreshold)
                .containsExactly(true, true, 4, 3);
        assertThat(configuration.testSecretAuthorityExternalSequenceAnchorHealth(anchor))
                .isInstanceOf(TestSecretAuthorityExternalSequenceAnchorHealth.class);
    }

    private static TestRuntimeDatabase database(String suffix) {
        return new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:test-secret-external-" + suffix + '-' + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1",
                "sa", "", 2));
    }

    private static TestSecretAuthorityExternalSequenceAnchor anchor(
            boolean available,
            boolean byzantine) {
        TestSecretAuthorityExternalSequenceAnchor anchor =
                mock(TestSecretAuthorityExternalSequenceAnchor.class);
        int authorities = byzantine ? 4 : 1;
        int threshold = byzantine ? 3 : 1;
        int faults = byzantine ? 1 : 0;
        when(anchor.descriptor()).thenReturn(
                new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                        TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                        available, available, available, available && byzantine,
                        authorities, threshold, faults, authorities, Map.of()));
        return anchor;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TestSecretAuthorityExternalSequenceAnchor> provider() {
        return mock(ObjectProvider.class);
    }
}
